# -*- coding: utf-8 -*-
"""
基于官方 anthropic SDK 的 Messages API 适配器
创建日期：2026-11-16
修改日期：2026-11-16
"""
import json
from collections.abc import AsyncGenerator
from typing import Any

import anthropic
from anthropic import AsyncAnthropic

from common_llm.core.errors import CommonLlmError, ErrorCategory, build_error, classify_http_status
from common_llm.core.ids import IdFactory
from common_llm.core.model_client import BaseModelClient
from common_llm.core.model_support import (
    ModelCallContext,
    ModelEventSequencer,
    build_model_response,
    collect_text,
    compact_json,
    dump_sdk_object,
    merge_provider_options,
    read_error_body,
    read_mapping,
    sum_counts,
)
from common_llm.core.settings import ProviderSettings
from common_llm.core.types import (
    FEATURE_REASONING,
    FEATURE_STREAMING,
    FEATURE_STRUCTURED_OUTPUT,
    FEATURE_TOOL_CALLS,
    FEATURE_VISION,
    FinishReason,
    ImagePart,
    Message,
    ModelEvent,
    ModelRequest,
    ModelResponse,
    ResponseContentPart,
    TextPart,
    ToolCallPart,
    ToolResultPart,
    Usage,
)

MAX_TOKENS_OPTION = "max_tokens"
MAPPED_KEYS = (
    "model",
    "messages",
    "system",
    "tools",
    "tool_choice",
    "output_config",
    "max_tokens",
    "stream",
)


def map_anthropic_finish(stop_reason: str | None) -> FinishReason:
    """
    映射 stop_reason
    """
    if stop_reason in ("end_turn", "stop_sequence"):
        return "stop"

    if stop_reason == "max_tokens":
        return "length"

    if stop_reason == "tool_use":
        return "tool_calls"

    if stop_reason == "refusal":
        return "content_filter"

    return "other"


def map_anthropic_usage(usage: dict[str, Any] | None) -> Usage:
    """
    inputTokens 为 input_tokens 加两类缓存 token 的总输入量
    """
    raw_usage = usage or {}
    input_tokens = raw_usage.get("input_tokens")
    total_input: int | None = None
    if input_tokens is not None:
        total_input = input_tokens + (raw_usage.get("cache_creation_input_tokens") or 0) + (raw_usage.get("cache_read_input_tokens") or 0)

    output_tokens = raw_usage.get("output_tokens")
    return Usage(
        input_tokens=total_input,
        output_tokens=output_tokens,
        cached_input_tokens=raw_usage.get("cache_read_input_tokens"),
        reasoning_tokens=None,
        total_tokens=sum_counts(total_input, output_tokens),
    )


def build_image_block(part: ImagePart) -> dict[str, Any]:
    """
    转为 image 内容块
    """
    if part.url:
        return {"type": "image", "source": {"type": "url", "url": part.url}}

    return {"type": "image", "source": {"type": "base64", "media_type": part.mime_type, "data": part.base64}}


def parse_tool_arguments(arguments: str) -> Any:
    """
    把 JSON 字符串参数解析为对象，空字符串按空对象处理
    """
    if not arguments:
        return {}

    return json.loads(arguments)


def build_anthropic_messages(messages: list[Message]) -> tuple[str | None, list[dict[str, Any]]]:
    """
    system 消息合并为 system 参数，tool 消息转为 user 角色的 tool_result 块
    """
    system_texts: list[str] = []
    converted: list[dict[str, Any]] = []
    for message in messages:
        if message.role == "system":
            system_texts.append(collect_text(message.content))
            continue

        blocks: list[dict[str, Any]] = []
        for part in message.content:
            if isinstance(part, TextPart):
                blocks.append({"type": "text", "text": part.text})
            elif isinstance(part, ImagePart):
                blocks.append(build_image_block(part))
            elif isinstance(part, ToolCallPart):
                blocks.append({"type": "tool_use", "id": part.id, "name": part.name, "input": parse_tool_arguments(part.arguments)})
            elif isinstance(part, ToolResultPart):
                result_block: dict[str, Any] = {
                    "type": "tool_result",
                    "tool_use_id": part.tool_call_id,
                    "content": part.content,
                }
                if part.is_error is not None:
                    result_block["is_error"] = part.is_error

                blocks.append(result_block)

        role = "assistant" if message.role == "assistant" else "user"
        converted.append({"role": role, "content": blocks})

    if not system_texts:
        return None, converted

    return "\n".join(system_texts), converted


def map_anthropic_message(provider: str, message: dict[str, Any], trace_id: str, request_id: str | None, include_raw: bool) -> ModelResponse:
    """
    把 Message 对象映射为 ModelResponse
    """
    content_parts: list[ResponseContentPart] = []
    for block in message.get("content") or []:
        if block.get("type") == "text":
            content_parts.append(TextPart(text=block.get("text") or ""))
        elif block.get("type") == "tool_use":
            content_parts.append(ToolCallPart(id=str(block.get("id")), name=str(block.get("name")), arguments=compact_json(block.get("input") or {})))

    stop_reason = message.get("stop_reason")
    raw_response: dict[str, Any] | None = None
    if include_raw:
        raw_response = message

    return build_model_response(
        provider=provider,
        model=message.get("model"),
        response_id=message.get("id"),
        content_parts=content_parts,
        finish_reason=map_anthropic_finish(stop_reason),
        vendor_finish_reason=stop_reason,
        usage=map_anthropic_usage(message.get("usage")),
        trace_id=trace_id,
        request_id=request_id,
        raw_response=raw_response,
    )


class AnthropicModelClient(BaseModelClient):
    """
    Anthropic Messages API 适配器，max_tokens 缺省取 native_options.max_tokens
    """

    mapped_option_keys = MAPPED_KEYS
    features = (FEATURE_STREAMING, FEATURE_TOOL_CALLS, FEATURE_STRUCTURED_OUTPUT, FEATURE_VISION, FEATURE_REASONING)

    def __init__(self, settings: ProviderSettings, trace_id_factory: IdFactory | None = None) -> None:
        super().__init__(settings, trace_id_factory)
        client_options: dict[str, Any] = {
            "api_key": settings.api_key,
            "timeout": anthropic.Timeout(settings.timeout_seconds, connect=settings.connect_timeout_seconds),
            "max_retries": settings.max_retries,
        }
        if settings.base_url:
            client_options["base_url"] = settings.base_url

        self._client = AsyncAnthropic(**client_options)

    def native_client(self) -> AsyncAnthropic:
        return self._client

    async def close_native_client(self) -> None:
        await self._client.close()

    def merge_options(self, request: ModelRequest) -> dict[str, Any]:
        # 配置级 native_options.max_tokens 是 maxOutputTokens 的默认值，不作为额外参数透传
        native_options: dict[str, Any] = {}
        for key, value in self.settings.native_options.items():
            if key != MAX_TOKENS_OPTION:
                native_options[key] = value

        return merge_provider_options(native_options, request.provider_options, self.mapped_option_keys, self.name)

    def resolve_max_tokens(self, request: ModelRequest) -> int:
        """
        取 maxOutputTokens，缺省取 native_options.max_tokens，都没有时报 CONFIG_INVALID
        """
        if request.max_output_tokens is not None:
            return request.max_output_tokens

        configured = self.settings.native_options.get(MAX_TOKENS_OPTION)
        if isinstance(configured, int) and not isinstance(configured, bool):
            return configured

        raise build_error(ErrorCategory.CONFIG_INVALID, "Anthropic 请求必须提供 maxOutputTokens 或配置 native_options.max_tokens", provider=self.name)

    def build_params(self, context: ModelCallContext) -> dict[str, Any]:
        """
        组装 messages.create 参数，providerOptions 经 extra_body 透传
        """
        request = context.request
        if request.temperature is not None:
            raise build_error(
                ErrorCategory.SDK_UNSUPPORTED_FEATURE,
                "anthropic 2.8.0 的 messages.create 不提供 temperature 参数，如需设置请通过 providerOptions 传入",
                provider=self.name,
            )

        system_text, messages = build_anthropic_messages(request.messages)
        params: dict[str, Any] = {
            "model": context.model,
            "messages": messages,
            "max_tokens": self.resolve_max_tokens(request),
            "timeout": context.timeout_seconds,
        }
        if system_text is not None:
            params["system"] = system_text

        if request.tools:
            tools: list[dict[str, Any]] = []
            for tool in request.tools:
                tool_param: dict[str, Any] = {
                    "name": tool.name,
                    "input_schema": tool.parameters,
                }
                if tool.description is not None:
                    tool_param["description"] = tool.description

                tools.append(tool_param)

            params["tools"] = tools

        tool_choice = request.tool_choice
        if tool_choice is not None:
            if tool_choice.type == "tool":
                params["tool_choice"] = {"type": "tool", "name": tool_choice.name}
            elif tool_choice.type == "required":
                params["tool_choice"] = {"type": "any"}
            else:
                params["tool_choice"] = {"type": tool_choice.type}

        response_format = request.response_format
        if response_format is not None and response_format.type == "json_schema":
            params["output_config"] = {"format": {"type": "json_schema", "schema": response_format.json_schema}}

        if context.extra_body:
            params["extra_body"] = context.extra_body

        return params

    async def invoke_generate(self, context: ModelCallContext) -> ModelResponse:
        create_message: Any = self._client.messages.create
        message = await create_message(**self.build_params(context))
        request_id = getattr(message, "_request_id", None)
        include_raw = context.request.include_raw_response is True
        return map_anthropic_message(self.name, dump_sdk_object(message), context.trace_id, request_id, include_raw)

    async def invoke_stream(self, context: ModelCallContext, sequencer: ModelEventSequencer) -> AsyncGenerator[ModelEvent, None]:
        create_message: Any = self._client.messages.create
        params = self.build_params(context)
        params["stream"] = True
        stream = await create_message(**params)
        request_id = stream.response.headers.get("request-id")
        message: dict[str, Any] = {}
        raw_usage: dict[str, Any] = {}
        blocks: dict[int, dict[str, str]] = {}
        tool_indexes: dict[int, int] = {}
        stop_reason: str | None = None
        try:
            async for raw_event in stream:
                event = dump_sdk_object(raw_event)
                event_type = event.get("type")
                if event_type == "message_start":
                    message = read_mapping(event, "message")
                    for key, value in read_mapping(message, "usage").items():
                        raw_usage[key] = value

                    yield sequencer.started(message.get("model"), message.get("id"))
                elif event_type == "content_block_start":
                    block = read_mapping(event, "content_block")
                    block_index = int(event.get("index") or 0)
                    if block.get("type") == "text":
                        blocks[block_index] = {"type": "text", "text": block.get("text") or ""}
                        text_event = sequencer.text(block.get("text"))
                        if text_event is not None:
                            yield text_event
                    elif block.get("type") == "tool_use":
                        tool_index = len(tool_indexes)
                        tool_indexes[block_index] = tool_index
                        blocks[block_index] = {"type": "tool_call", "id": str(block.get("id")), "name": str(block.get("name")), "arguments": ""}
                        delta_event = sequencer.tool_delta(tool_index, block.get("id"), block.get("name"), "")
                        if delta_event is not None:
                            yield delta_event
                elif event_type == "content_block_delta":
                    block_index = int(event.get("index") or 0)
                    delta = read_mapping(event, "delta")
                    if delta.get("type") == "text_delta" and block_index in blocks:
                        blocks[block_index]["text"] += delta.get("text") or ""
                        text_event = sequencer.text(delta.get("text"))
                        if text_event is not None:
                            yield text_event
                    elif delta.get("type") == "input_json_delta" and block_index in tool_indexes:
                        blocks[block_index]["arguments"] += delta.get("partial_json") or ""
                        delta_event = sequencer.tool_delta(tool_indexes[block_index], None, None, delta.get("partial_json"))
                        if delta_event is not None:
                            yield delta_event
                elif event_type == "message_delta":
                    stop_reason = read_mapping(event, "delta").get("stop_reason")
                    # message_delta 的非空用量字段覆盖 message_start 中的值
                    for key, value in read_mapping(event, "usage").items():
                        if value is not None:
                            raw_usage[key] = value
                elif event_type == "message_stop":
                    usage = map_anthropic_usage(raw_usage)
                    yield sequencer.usage(usage)
                    response = build_model_response(
                        provider=self.name,
                        model=message.get("model"),
                        response_id=message.get("id"),
                        content_parts=self.build_stream_parts(blocks),
                        finish_reason=map_anthropic_finish(stop_reason),
                        vendor_finish_reason=stop_reason,
                        usage=usage,
                        trace_id=context.trace_id,
                        request_id=request_id,
                        raw_response=None,
                    )
                    yield sequencer.completed(response)
                    return
        finally:
            await stream.close()

    def build_stream_parts(self, blocks: dict[int, dict[str, str]]) -> list[ResponseContentPart]:
        """
        按 block 序号组装累积内容，无参数的工具调用补为空对象
        """
        content_parts: list[ResponseContentPart] = []
        for block_index in sorted(blocks.keys()):
            block = blocks[block_index]
            if block["type"] == "text":
                content_parts.append(TextPart(text=block["text"]))
                continue

            content_parts.append(ToolCallPart(id=block["id"], name=block["name"], arguments=block["arguments"] or "{}"))

        return content_parts

    def map_exception(self, error: Exception) -> CommonLlmError:
        if isinstance(error, anthropic.APIStatusError):
            body = read_error_body(error.body)
            vendor_code = body.get("type")
            category, retriable = classify_http_status(error.status_code, False, False)
            return build_error(
                category,
                f"Anthropic 返回 HTTP {error.status_code}：{body.get('message') or error.message}",
                provider=self.name,
                retriable=retriable,
                request_id=error.request_id,
                vendor_code=str(vendor_code) if vendor_code is not None else None,
                status_code=error.status_code,
                cause=error,
            )

        if isinstance(error, anthropic.APITimeoutError):
            return build_error(ErrorCategory.TIMEOUT, "Anthropic 请求超时", provider=self.name, cause=error)

        if isinstance(error, anthropic.APIConnectionError):
            return build_error(ErrorCategory.NETWORK_ERROR, "无法连接 Anthropic 服务", provider=self.name, cause=error)

        if isinstance(error, anthropic.AnthropicError):
            return build_error(ErrorCategory.PROVIDER_ERROR, "Anthropic SDK 调用失败", provider=self.name, cause=error)

        return build_error(ErrorCategory.INTERNAL_ERROR, "Anthropic 适配层内部错误", provider=self.name, cause=error)
