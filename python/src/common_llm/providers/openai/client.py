# -*- coding: utf-8 -*-
"""
基于官方 openai SDK 的 OpenAI 适配器，支持 Responses 与 Chat Completions 两种协议
创建日期：2026-11-16
修改日期：2026-11-16
"""
from collections.abc import AsyncGenerator
from typing import Any

import openai
from openai import AsyncOpenAI

from common_llm.core.errors import CommonLlmError, ErrorCategory, build_error, classify_http_status
from common_llm.core.ids import IdFactory
from common_llm.core.model_client import BaseModelClient
from common_llm.core.model_support import (
    ModelCallContext,
    ModelEventSequencer,
    build_model_response,
    dump_sdk_object,
    read_error_body,
    read_mapping,
)
from common_llm.core.settings import ProviderSettings
from common_llm.core.types import (
    FEATURE_REASONING,
    FEATURE_STREAMING,
    FEATURE_STRUCTURED_OUTPUT,
    FEATURE_TOOL_CALLS,
    FEATURE_VISION,
    ModelEvent,
    ModelResponse,
    ResponseContentPart,
    TextPart,
    ToolCallPart,
)
from common_llm.providers.openai.chat_mapping import (
    CHAT_MAPPED_KEYS,
    build_chat_params,
    map_chat_completion,
    map_chat_finish,
    map_chat_usage,
)
from common_llm.providers.openai.responses_mapping import (
    RESPONSES_MAPPED_KEYS,
    build_responses_params,
    map_responses_response,
    map_responses_usage,
)


def read_request_id(source: Any) -> str | None:
    """
    读取 SDK 响应对象上的 x-request-id
    """
    request_id = getattr(source, "_request_id", None)
    if isinstance(request_id, str):
        return request_id

    return None


def read_stream_request_id(stream: Any) -> str | None:
    """
    读取流式响应头中的 x-request-id
    """
    response = getattr(stream, "response", None)
    if response is None:
        return None

    value = response.headers.get("x-request-id")
    if isinstance(value, str):
        return value

    return None


class OpenAiBaseClient(BaseModelClient):
    """
    OpenAI 两种协议共用的 SDK 客户端创建与错误映射
    """

    def __init__(self, settings: ProviderSettings, trace_id_factory: IdFactory | None = None) -> None:
        super().__init__(settings, trace_id_factory)
        client_options: dict[str, Any] = {
            "api_key": settings.api_key,
            "timeout": openai.Timeout(settings.timeout_seconds, connect=settings.connect_timeout_seconds),
            "max_retries": settings.max_retries,
        }
        if settings.base_url:
            client_options["base_url"] = settings.base_url

        self._client = AsyncOpenAI(**client_options)

    def native_client(self) -> AsyncOpenAI:
        return self._client

    async def close_native_client(self) -> None:
        await self._client.close()

    def map_exception(self, error: Exception) -> CommonLlmError:
        if isinstance(error, openai.APIStatusError):
            body = read_error_body(error.body)
            code = body.get("code")
            vendor_code = code or body.get("type")
            category, retriable = classify_http_status(error.status_code, code == "insufficient_quota", False)
            return build_error(
                category,
                f"OpenAI 返回 HTTP {error.status_code}：{body.get('message') or error.message}",
                provider=self.name,
                retriable=retriable,
                request_id=error.request_id,
                vendor_code=str(vendor_code) if vendor_code is not None else None,
                status_code=error.status_code,
                cause=error,
            )

        if isinstance(error, openai.APITimeoutError):
            return build_error(ErrorCategory.TIMEOUT, "OpenAI 请求超时", provider=self.name, cause=error)

        if isinstance(error, openai.APIConnectionError):
            return build_error(ErrorCategory.NETWORK_ERROR, "无法连接 OpenAI 服务", provider=self.name, cause=error)

        if isinstance(error, openai.OpenAIError):
            return build_error(ErrorCategory.PROVIDER_ERROR, "OpenAI SDK 调用失败", provider=self.name, cause=error)

        return build_error(ErrorCategory.INTERNAL_ERROR, "OpenAI 适配层内部错误", provider=self.name, cause=error)


class OpenAiResponsesClient(OpenAiBaseClient):
    """
    api=responses 时使用 responses.create
    """

    mapped_option_keys = RESPONSES_MAPPED_KEYS
    features = (FEATURE_STREAMING, FEATURE_TOOL_CALLS, FEATURE_STRUCTURED_OUTPUT, FEATURE_VISION, FEATURE_REASONING)

    async def invoke_generate(self, context: ModelCallContext) -> ModelResponse:
        create_response: Any = self._client.responses.create
        response = await create_response(**build_responses_params(context))
        include_raw = context.request.include_raw_response is True
        return map_responses_response(self.name, dump_sdk_object(response), context.trace_id, read_request_id(response), include_raw)

    async def invoke_stream(self, context: ModelCallContext, sequencer: ModelEventSequencer) -> AsyncGenerator[ModelEvent, None]:
        create_response: Any = self._client.responses.create
        params = build_responses_params(context)
        params["stream"] = True
        stream = await create_response(**params)
        request_id = read_stream_request_id(stream)
        include_raw = context.request.include_raw_response is True
        tool_indexes: dict[str, int] = {}
        try:
            async for raw_event in stream:
                event = dump_sdk_object(raw_event)
                event_type = event.get("type")
                if event_type == "response.created":
                    response = read_mapping(event, "response")
                    yield sequencer.started(response.get("model"), response.get("id"))
                elif event_type == "response.output_text.delta":
                    text_event = sequencer.text(event.get("delta"))
                    if text_event is not None:
                        yield text_event
                elif event_type == "response.output_item.added" and read_mapping(event, "item").get("type") == "function_call":
                    item = read_mapping(event, "item")
                    tool_index = len(tool_indexes)
                    tool_indexes[str(item.get("id"))] = tool_index
                    delta_event = sequencer.tool_delta(tool_index, item.get("call_id"), item.get("name"), item.get("arguments") or "")
                    if delta_event is not None:
                        yield delta_event
                elif event_type == "response.function_call_arguments.delta":
                    tool_index = tool_indexes.get(str(event.get("item_id")), 0)
                    delta_event = sequencer.tool_delta(tool_index, None, None, event.get("delta"))
                    if delta_event is not None:
                        yield delta_event
                elif event_type in ("response.completed", "response.incomplete"):
                    final = read_mapping(event, "response")
                    mapped = map_responses_response(self.name, final, context.trace_id, request_id, include_raw)
                    if final.get("usage") is not None:
                        yield sequencer.usage(mapped.usage)

                    yield sequencer.completed(mapped)
                    return
                elif event_type in ("response.failed", "error"):
                    raise self.build_stream_error(event, request_id)
        finally:
            await stream.close()

    def build_stream_error(self, event: dict[str, Any], request_id: str | None) -> CommonLlmError:
        """
        把流中的 response.failed 或 error 事件转为 PROVIDER_ERROR
        """
        error_body = read_mapping(read_mapping(event, "response"), "error") or event
        code = error_body.get("code")
        return build_error(
            ErrorCategory.PROVIDER_ERROR,
            f"OpenAI 流式响应失败：{error_body.get('message') or code}",
            provider=self.name,
            request_id=request_id,
            vendor_code=str(code) if code is not None else None,
        )


class OpenAiChatClient(OpenAiBaseClient):
    """
    api=chat_completions 时使用 chat.completions.create，适用于只支持该协议的兼容端点
    """

    mapped_option_keys = CHAT_MAPPED_KEYS
    features = (FEATURE_STREAMING, FEATURE_TOOL_CALLS, FEATURE_STRUCTURED_OUTPUT, FEATURE_VISION)

    async def invoke_generate(self, context: ModelCallContext) -> ModelResponse:
        create_completion: Any = self._client.chat.completions.create
        completion = await create_completion(**build_chat_params(context, False))
        include_raw = context.request.include_raw_response is True
        return map_chat_completion(self.name, dump_sdk_object(completion), context.trace_id, read_request_id(completion), include_raw)

    async def invoke_stream(self, context: ModelCallContext, sequencer: ModelEventSequencer) -> AsyncGenerator[ModelEvent, None]:
        create_completion: Any = self._client.chat.completions.create
        stream = await create_completion(**build_chat_params(context, True))
        request_id = read_stream_request_id(stream)
        model: str | None = None
        response_id: str | None = None
        is_started = False
        text_parts: list[str] = []
        tool_calls: dict[int, dict[str, str]] = {}
        finish_reason: str | None = None
        raw_usage: dict[str, Any] | None = None
        try:
            async for raw_chunk in stream:
                chunk = dump_sdk_object(raw_chunk)
                if not is_started:
                    is_started = True
                    model = chunk.get("model")
                    response_id = chunk.get("id")
                    yield sequencer.started(model, response_id)

                if chunk.get("usage"):
                    raw_usage = chunk["usage"]

                for choice in chunk.get("choices") or []:
                    delta = read_mapping(choice, "delta")
                    content = delta.get("content")
                    if content:
                        text_parts.append(content)
                        text_event = sequencer.text(content)
                        if text_event is not None:
                            yield text_event

                    for tool_delta in delta.get("tool_calls") or []:
                        tool_index = int(tool_delta.get("index") or 0)
                        function = read_mapping(tool_delta, "function")
                        arguments_delta = function.get("arguments") or ""
                        if tool_index not in tool_calls:
                            tool_calls[tool_index] = {"id": str(tool_delta.get("id")), "name": str(function.get("name")), "arguments": ""}

                        tool_calls[tool_index]["arguments"] += arguments_delta
                        delta_event = sequencer.tool_delta(tool_index, tool_delta.get("id"), function.get("name"), arguments_delta)
                        if delta_event is not None:
                            yield delta_event

                    if choice.get("finish_reason"):
                        finish_reason = choice["finish_reason"]
        finally:
            await stream.close()

        content_parts: list[ResponseContentPart] = []
        full_text = "".join(text_parts)
        if full_text:
            content_parts.append(TextPart(text=full_text))

        for tool_index in sorted(tool_calls.keys()):
            tool_call = tool_calls[tool_index]
            content_parts.append(ToolCallPart(id=tool_call["id"], name=tool_call["name"], arguments=tool_call["arguments"]))

        usage = map_chat_usage(raw_usage)
        if raw_usage is not None:
            yield sequencer.usage(usage)

        response = build_model_response(
            provider=self.name,
            model=model,
            response_id=response_id,
            content_parts=content_parts,
            finish_reason=map_chat_finish(finish_reason),
            vendor_finish_reason=finish_reason,
            usage=usage,
            trace_id=context.trace_id,
            request_id=request_id,
            raw_response=None,
        )
        yield sequencer.completed(response)


def create_openai_client(settings: ProviderSettings, trace_id_factory: IdFactory | None = None) -> OpenAiBaseClient:
    """
    按 api 字段创建对应协议的客户端
    """
    if settings.api == "chat_completions":
        return OpenAiChatClient(settings, trace_id_factory)

    return OpenAiResponsesClient(settings, trace_id_factory)


__all__ = [
    "OpenAiBaseClient",
    "OpenAiChatClient",
    "OpenAiResponsesClient",
    "create_openai_client",
    "map_responses_usage",
]
