# -*- coding: utf-8 -*-
"""
基于官方 google-genai SDK 的 Gemini generateContent 适配器
创建日期：2026-11-16
修改日期：2026-11-16
"""
import base64
import json
from collections.abc import AsyncGenerator
from typing import Any

from google import genai
from google.genai import errors as genai_errors
from google.genai import types

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
    ModelResponse,
    ResponseContentPart,
    TextPart,
    ToolCallPart,
    ToolResultPart,
    Usage,
)

CONTENT_FILTER_REASONS = ("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY")
# 顶层请求体中由统一请求字段负责的键，extra_body 按深度合并，两种大小写都要检查
MAPPED_KEYS = (
    "contents",
    "systemInstruction",
    "system_instruction",
    "tools",
    "toolConfig",
    "tool_config",
)
GENERATION_CONFIG_KEYS = ("generationConfig", "generation_config")
MAPPED_GENERATION_KEYS = (
    "temperature",
    "maxOutputTokens",
    "max_output_tokens",
    "responseMimeType",
    "response_mime_type",
    "responseJsonSchema",
    "response_json_schema",
    "responseSchema",
    "response_schema",
)
TIMEOUT_ERROR_NAMES = ("TimeoutException", "ReadTimeout", "ConnectTimeout", "WriteTimeout", "PoolTimeout")
NETWORK_ERROR_NAMES = ("ConnectError", "NetworkError", "RemoteProtocolError", "ReadError", "WriteError")


def map_gemini_finish(finish_reason: str | None, has_tool_call: bool) -> FinishReason:
    """
    含函数调用时为 tool_calls，其余按 finishReason 映射
    """
    if has_tool_call:
        return "tool_calls"

    if finish_reason == "STOP":
        return "stop"

    if finish_reason == "MAX_TOKENS":
        return "length"

    if finish_reason in CONTENT_FILTER_REASONS:
        return "content_filter"

    return "other"


def map_gemini_usage(usage: types.GenerateContentResponseUsageMetadata | None) -> Usage:
    """
    映射 usageMetadata
    """
    if usage is None:
        return Usage()

    return Usage(
        input_tokens=usage.prompt_token_count,
        output_tokens=usage.candidates_token_count,
        cached_input_tokens=usage.cached_content_token_count,
        reasoning_tokens=usage.thoughts_token_count,
        total_tokens=usage.total_token_count,
    )


def read_finish_reason(candidate: types.Candidate) -> str | None:
    """
    读取 finishReason 的字符串值
    """
    if candidate.finish_reason is None:
        return None

    return str(candidate.finish_reason.value)


def collect_response_parts(response: types.GenerateContentResponse, tool_count: int) -> tuple[list[ResponseContentPart], str | None]:
    """
    提取首个候选的文本与函数调用，跳过 thought 部分，缺少 id 的函数调用按出现顺序生成 id
    """
    content_parts: list[ResponseContentPart] = []
    if not response.candidates:
        return content_parts, None

    candidate = response.candidates[0]
    parts: list[types.Part] = []
    if candidate.content is not None and candidate.content.parts is not None:
        parts = candidate.content.parts

    next_tool_count = tool_count
    for part in parts:
        if part.thought is True:
            continue

        if part.text is not None:
            content_parts.append(TextPart(text=part.text))
        elif part.function_call is not None:
            function_call = part.function_call
            call_id = function_call.id or f"gemini_call_{next_tool_count}"
            next_tool_count += 1
            content_parts.append(ToolCallPart(id=call_id, name=function_call.name or "", arguments=compact_json(function_call.args or {})))

    return content_parts, read_finish_reason(candidate)


def has_tool_call_part(content_parts: list[ResponseContentPart]) -> bool:
    """
    判断内容片段中是否含工具调用
    """
    for part in content_parts:
        if isinstance(part, ToolCallPart):
            return True

    return False


def build_media_part(part: ImagePart) -> types.Part:
    """
    url 转为 file_data，base64 转为 inline_data
    """
    if part.url:
        return types.Part(file_data=types.FileData(file_uri=part.url, mime_type=part.mime_type))

    return types.Part(inline_data=types.Blob(data=base64.b64decode(part.base64 or ""), mime_type=part.mime_type))


def find_tool_names(messages: list[Message]) -> dict[str, str]:
    """
    记录工具调用 id 与函数名的对应关系，用于组装 functionResponse
    """
    tool_names: dict[str, str] = {}
    for message in messages:
        for part in message.content:
            if isinstance(part, ToolCallPart):
                tool_names[part.id] = part.name

    return tool_names


def build_gemini_contents(messages: list[Message]) -> tuple[str | None, list[types.Content]]:
    """
    system 消息合并为 systemInstruction，assistant 映射为 model 角色，tool 结果映射为 functionResponse
    """
    tool_names = find_tool_names(messages)
    system_texts: list[str] = []
    contents: list[types.Content] = []
    for message in messages:
        if message.role == "system":
            system_texts.append(collect_text(message.content))
            continue

        parts: list[types.Part] = []
        for part in message.content:
            if isinstance(part, TextPart):
                parts.append(types.Part(text=part.text))
            elif isinstance(part, ImagePart):
                parts.append(build_media_part(part))
            elif isinstance(part, ToolCallPart):
                arguments = json.loads(part.arguments) if part.arguments else {}
                parts.append(types.Part(function_call=types.FunctionCall(id=part.id, name=part.name, args=arguments)))
            elif isinstance(part, ToolResultPart):
                result_key = "error" if part.is_error else "output"
                function_response = types.FunctionResponse(id=part.tool_call_id, name=tool_names.get(part.tool_call_id, ""), response={result_key: part.content})
                parts.append(types.Part(function_response=function_response))

        role = "model" if message.role == "assistant" else "user"
        contents.append(types.Content(role=role, parts=parts))

    if not system_texts:
        return None, contents

    return "\n".join(system_texts), contents


def is_named_error(error: BaseException, names: tuple[str, ...]) -> bool:
    """
    按异常类及其父类名称判断类型，避免直接依赖底层 HTTP 库
    """
    for error_class in type(error).__mro__:
        if error_class.__name__ in names:
            return True

    return False


class GeminiModelClient(BaseModelClient):
    """
    Gemini 适配器，providerOptions 经 HttpOptions.extra_body 透传
    """

    features = (FEATURE_STREAMING, FEATURE_TOOL_CALLS, FEATURE_STRUCTURED_OUTPUT, FEATURE_VISION, FEATURE_REASONING)
    mapped_option_keys = MAPPED_KEYS

    def __init__(self, settings: ProviderSettings, trace_id_factory: IdFactory | None = None) -> None:
        super().__init__(settings, trace_id_factory)
        # attempts 含首次请求，因此为 max_retries + 1；SDK 不支持单独的连接超时，connect_timeout_seconds 被忽略
        http_options = types.HttpOptions(
            base_url=settings.base_url,
            timeout=int(settings.timeout_seconds * 1000),
            retry_options=types.HttpRetryOptions(attempts=settings.max_retries + 1),
        )
        self._client = genai.Client(api_key=settings.api_key, http_options=http_options)

    def native_client(self) -> genai.Client:
        return self._client

    async def close_native_client(self) -> None:
        await self._client.aio.aclose()

    def merge_options(self, request: Any) -> dict[str, Any]:
        merged = super().merge_options(request)
        for config_key in GENERATION_CONFIG_KEYS:
            generation_config = merged.get(config_key)
            if not isinstance(generation_config, dict):
                continue

            for key in generation_config:
                if key in MAPPED_GENERATION_KEYS:
                    raise build_error(
                        ErrorCategory.CONFIG_INVALID,
                        f"providerOptions.{config_key}.{key} 与已映射字段重名，请改用统一请求字段",
                        provider=self.name,
                    )

        return merged

    def build_config(self, context: ModelCallContext, system_text: str | None) -> types.GenerateContentConfig:
        """
        组装 GenerateContentConfig，单次超时与 extra_body 经 http_options 传入
        """
        request = context.request
        config = types.GenerateContentConfig(
            http_options=types.HttpOptions(
                timeout=int(context.timeout_seconds * 1000),
                extra_body=context.extra_body or None,
            ),
        )
        if system_text is not None:
            config.system_instruction = system_text

        if request.temperature is not None:
            config.temperature = request.temperature

        if request.max_output_tokens is not None:
            config.max_output_tokens = request.max_output_tokens

        if request.tools:
            declarations: list[types.FunctionDeclaration] = []
            for tool in request.tools:
                declarations.append(types.FunctionDeclaration(name=tool.name, description=tool.description, parameters_json_schema=tool.parameters))

            config.tools = [types.Tool(function_declarations=declarations)]

        tool_choice = request.tool_choice
        if tool_choice is not None:
            mode = types.FunctionCallingConfigMode.AUTO
            allowed_names: list[str] | None = None
            if tool_choice.type == "none":
                mode = types.FunctionCallingConfigMode.NONE
            elif tool_choice.type == "required":
                mode = types.FunctionCallingConfigMode.ANY
            elif tool_choice.type == "tool":
                mode = types.FunctionCallingConfigMode.ANY
                allowed_names = [tool_choice.name or ""]

            config.tool_config = types.ToolConfig(function_calling_config=types.FunctionCallingConfig(mode=mode, allowed_function_names=allowed_names))

        response_format = request.response_format
        if response_format is not None and response_format.type == "json_schema":
            config.response_mime_type = "application/json"
            config.response_json_schema = response_format.json_schema

        return config

    async def invoke_generate(self, context: ModelCallContext) -> ModelResponse:
        system_text, contents = build_gemini_contents(context.request.messages)
        response = await self._client.aio.models.generate_content(model=context.model, contents=contents, config=self.build_config(context, system_text))
        content_parts, finish_reason = collect_response_parts(response, 0)
        raw_response: dict[str, Any] | None = None
        if context.request.include_raw_response is True:
            raw_response = dump_sdk_object(response)

        return build_model_response(
            provider=self.name,
            model=response.model_version,
            response_id=response.response_id,
            content_parts=content_parts,
            finish_reason=map_gemini_finish(finish_reason, has_tool_call_part(content_parts)),
            vendor_finish_reason=finish_reason,
            usage=map_gemini_usage(response.usage_metadata),
            trace_id=context.trace_id,
            request_id=None,
            raw_response=raw_response,
        )

    async def invoke_stream(self, context: ModelCallContext, sequencer: ModelEventSequencer) -> AsyncGenerator[ModelEvent, None]:
        system_text, contents = build_gemini_contents(context.request.messages)
        chunks = await self._client.aio.models.generate_content_stream(model=context.model, contents=contents, config=self.build_config(context, system_text))
        is_started = False
        model: str | None = None
        response_id: str | None = None
        text_parts: list[str] = []
        tool_parts: list[ToolCallPart] = []
        finish_reason: str | None = None
        usage_metadata: types.GenerateContentResponseUsageMetadata | None = None
        try:
            async for chunk in chunks:
                if not is_started:
                    is_started = True
                    model = chunk.model_version
                    response_id = chunk.response_id
                    yield sequencer.started(model, response_id)

                content_parts, chunk_finish = collect_response_parts(chunk, len(tool_parts))
                for part in content_parts:
                    if isinstance(part, TextPart):
                        text_parts.append(part.text)
                        text_event = sequencer.text(part.text)
                        if text_event is not None:
                            yield text_event
                    elif isinstance(part, ToolCallPart):
                        delta_event = sequencer.tool_delta(len(tool_parts), part.id, part.name, part.arguments)
                        tool_parts.append(part)
                        if delta_event is not None:
                            yield delta_event

                if chunk_finish:
                    finish_reason = chunk_finish

                if chunk.usage_metadata is not None:
                    usage_metadata = chunk.usage_metadata
        finally:
            close_stream = getattr(chunks, "aclose", None)
            if close_stream is not None:
                await close_stream()

        final_parts: list[ResponseContentPart] = []
        full_text = "".join(text_parts)
        if full_text:
            final_parts.append(TextPart(text=full_text))

        for tool_part in tool_parts:
            final_parts.append(tool_part)

        usage = map_gemini_usage(usage_metadata)
        if usage_metadata is not None:
            yield sequencer.usage(usage)

        response = build_model_response(
            provider=self.name,
            model=model,
            response_id=response_id,
            content_parts=final_parts,
            finish_reason=map_gemini_finish(finish_reason, len(tool_parts) > 0),
            vendor_finish_reason=finish_reason,
            usage=usage,
            trace_id=context.trace_id,
            request_id=None,
            raw_response=None,
        )
        yield sequencer.completed(response)

    def map_exception(self, error: Exception) -> CommonLlmError:
        if isinstance(error, genai_errors.APIError):
            details_text = json.dumps(error.details, ensure_ascii=False, default=str)
            is_auth_failure = error.code == 400 and "API_KEY_INVALID" in details_text
            category, retriable = classify_http_status(error.code, False, is_auth_failure)
            return build_error(
                category,
                f"Gemini 返回 HTTP {error.code}：{error.message}",
                provider=self.name,
                retriable=retriable,
                vendor_code=error.status,
                status_code=error.code,
                cause=error,
            )

        if is_named_error(error, TIMEOUT_ERROR_NAMES) or isinstance(error, TimeoutError):
            return build_error(ErrorCategory.TIMEOUT, "Gemini 请求超时", provider=self.name, cause=error)

        if is_named_error(error, NETWORK_ERROR_NAMES):
            return build_error(ErrorCategory.NETWORK_ERROR, "无法连接 Gemini 服务", provider=self.name, cause=error)

        return build_error(ErrorCategory.INTERNAL_ERROR, "Gemini 适配层内部错误", provider=self.name, cause=error)
