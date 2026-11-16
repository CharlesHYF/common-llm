# -*- coding: utf-8 -*-
"""
模型适配层共用的请求校验、原生参数合并、响应组装与流式事件编号工具
创建日期：2026-11-16
修改日期：2026-11-16
"""
import json
from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from typing import Any

from common_llm.core.errors import ErrorCategory, ErrorInfo, build_error
from common_llm.core.types import (
    FinishReason,
    ImagePart,
    ModelCompletedEvent,
    ModelErrorEvent,
    ModelRequest,
    ModelResponse,
    ModelStartedEvent,
    ModelTextDeltaEvent,
    ModelToolCallDeltaEvent,
    ModelUsageEvent,
    ResponseContentPart,
    TextPart,
    ToolCall,
    ToolCallPart,
    Usage,
)


@dataclass
class ModelCallContext:
    """
    单次模型调用在适配层内部使用的已校验参数
    """

    request: ModelRequest
    model: str
    trace_id: str
    timeout_seconds: float
    extra_body: dict[str, Any]


def compact_json(value: Any) -> str:
    """
    序列化为紧凑 JSON 字符串，保留非 ASCII 字符
    """
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def collect_text(parts: Iterable[TextPart | Any]) -> str:
    """
    按顺序拼接全部文本片段
    """
    texts: list[str] = []
    for part in parts:
        if isinstance(part, TextPart):
            texts.append(part.text)

    return "".join(texts)


def validate_model_request(request: ModelRequest, provider: str) -> None:
    """
    校验请求结构，不合法时抛出 CONFIG_INVALID
    """
    if request.provider != provider:
        raise build_error(ErrorCategory.CONFIG_INVALID, f"请求的 provider {request.provider} 与客户端 {provider} 不一致", provider=provider)

    if not request.messages:
        raise build_error(ErrorCategory.CONFIG_INVALID, "messages 至少包含 1 条消息", provider=provider)

    if request.tool_choice is not None and request.tool_choice.type == "tool" and not request.tool_choice.name:
        raise build_error(ErrorCategory.CONFIG_INVALID, "toolChoice.type 为 tool 时必须提供 name", provider=provider)

    response_format = request.response_format
    if response_format is not None and response_format.type == "json_schema":
        if not response_format.name or response_format.json_schema is None:
            raise build_error(ErrorCategory.CONFIG_INVALID, "responseFormat.type 为 json_schema 时必须提供 name 与 schema", provider=provider)

    for message in request.messages:
        if not message.content:
            raise build_error(ErrorCategory.CONFIG_INVALID, "每条消息的 content 至少包含 1 个片段", provider=provider)

        for part in message.content:
            if isinstance(part, ImagePart):
                validate_image_part(part, provider)


def validate_image_part(part: ImagePart, provider: str) -> None:
    """
    校验图片片段只提供 url 或 base64 加 mimeType 其中一种
    """
    has_url = bool(part.url)
    has_base64 = bool(part.base64)
    if has_url == has_base64:
        raise build_error(ErrorCategory.CONFIG_INVALID, "图片片段必须且只能提供 url 或 base64 其中之一", provider=provider)

    if has_base64 and not part.mime_type:
        raise build_error(ErrorCategory.CONFIG_INVALID, "base64 图片必须提供 mimeType", provider=provider)


def resolve_model_name(request: ModelRequest, default_model: str | None, provider: str) -> str:
    """
    取请求中的 model，缺省时取配置的 default_model，两者都没有时报 CONFIG_INVALID
    """
    if request.model:
        return request.model

    if default_model:
        return default_model

    raise build_error(ErrorCategory.CONFIG_INVALID, f"请求未指定 model，且 provider {provider} 未配置 default_model", provider=provider)


def merge_provider_options(
    native_options: Mapping[str, Any],
    provider_options: Mapping[str, Any] | None,
    mapped_keys: Iterable[str],
    provider: str,
) -> dict[str, Any]:
    """
    合并配置级 native_options 与请求级 providerOptions，键与已映射字段重名时报 CONFIG_INVALID
    """
    merged: dict[str, Any] = {}
    for key, value in native_options.items():
        merged[key] = value

    if provider_options is not None:
        for key, value in provider_options.items():
            merged[key] = value

    mapped_key_set = set(mapped_keys)
    for key in merged:
        if key in mapped_key_set:
            raise build_error(
                ErrorCategory.CONFIG_INVALID,
                f"providerOptions 的键 {key} 与已映射字段重名，请改用统一请求字段",
                provider=provider,
            )

    return merged


def build_tool_calls(content_parts: Iterable[ResponseContentPart]) -> list[ToolCall]:
    """
    从内容片段中提取工具调用列表
    """
    tool_calls: list[ToolCall] = []
    for part in content_parts:
        if isinstance(part, ToolCallPart):
            tool_calls.append(ToolCall(id=part.id, name=part.name, arguments=part.arguments))

    return tool_calls


def build_model_response(
    *,
    provider: str,
    model: str | None,
    response_id: str | None,
    content_parts: list[ResponseContentPart],
    finish_reason: FinishReason,
    vendor_finish_reason: str | None,
    usage: Usage,
    trace_id: str,
    request_id: str | None,
    raw_response: dict[str, Any] | None,
) -> ModelResponse:
    """
    组装 ModelResponse，text 与 toolCalls 由内容片段计算
    """
    return ModelResponse(
        provider=provider,
        model=model,
        id=response_id,
        content_parts=content_parts,
        text=collect_text(content_parts),
        tool_calls=build_tool_calls(content_parts),
        finish_reason=finish_reason,
        vendor_finish_reason=vendor_finish_reason,
        usage=usage,
        trace_id=trace_id,
        request_id=request_id,
        raw_response=raw_response,
    )


def sum_counts(first: int | None, second: int | None) -> int | None:
    """
    两个计数都可取时求和，否则返回 None
    """
    if first is None or second is None:
        return None

    return first + second


def read_mapping(container: Mapping[str, Any] | None, key: str) -> dict[str, Any]:
    """
    读取嵌套字典字段，缺失或为 null 时返回空字典
    """
    if container is None:
        return {}

    value = container.get(key)
    if isinstance(value, dict):
        return value

    return {}


class ModelEventSequencer:
    """
    为流式事件填充 traceId、provider 与递增 sequence，并保证只有一个终止事件
    """

    def __init__(self, provider: str, trace_id: str) -> None:
        self.provider = provider
        self.trace_id = trace_id
        self._next_sequence = 0
        self.is_terminated = False

    def _take_sequence(self) -> int:
        sequence = self._next_sequence
        self._next_sequence += 1
        return sequence

    def started(self, model: str | None, response_id: str | None) -> ModelStartedEvent:
        return ModelStartedEvent(trace_id=self.trace_id, sequence=self._take_sequence(), provider=self.provider, model=model, id=response_id)

    def text(self, text: str | None) -> ModelTextDeltaEvent | None:
        # 空字符串增量不发出
        if not text:
            return None

        return ModelTextDeltaEvent(trace_id=self.trace_id, sequence=self._take_sequence(), provider=self.provider, text=text)

    def tool_delta(self, index: int, call_id: str | None, name: str | None, arguments_delta: str | None) -> ModelToolCallDeltaEvent | None:
        # 首个增量带 id 与 name，即使参数为空也发出；后续空参数增量不发出
        if call_id is None and name is None and not arguments_delta:
            return None

        return ModelToolCallDeltaEvent(
            trace_id=self.trace_id,
            sequence=self._take_sequence(),
            provider=self.provider,
            index=index,
            id=call_id,
            name=name,
            arguments_delta=arguments_delta or "",
        )

    def usage(self, usage: Usage) -> ModelUsageEvent:
        return ModelUsageEvent(trace_id=self.trace_id, sequence=self._take_sequence(), provider=self.provider, usage=usage)

    def completed(self, response: ModelResponse) -> ModelCompletedEvent:
        self.is_terminated = True
        return ModelCompletedEvent(trace_id=self.trace_id, sequence=self._take_sequence(), provider=self.provider, response=response)

    def error(self, info: ErrorInfo) -> ModelErrorEvent:
        self.is_terminated = True
        return ModelErrorEvent(trace_id=self.trace_id, sequence=self._take_sequence(), provider=self.provider, error=info)


def dump_sdk_object(value: Any) -> dict[str, Any]:
    """
    把 SDK 返回的 pydantic 对象转为字段名与厂商 JSON 一致的字典
    """
    dump_method = getattr(value, "model_dump", None)
    if dump_method is None:
        if isinstance(value, dict):
            return value

        return {}

    dumped = dump_method(mode="json", by_alias=True, warnings=False)
    if isinstance(dumped, dict):
        return dumped

    return {}


def read_error_body(body: object) -> dict[str, Any]:
    """
    读取 SDK 异常中的错误对象，body 带外层 error 字段时取内层
    """
    if not isinstance(body, dict):
        return {}

    inner = body.get("error")
    if isinstance(inner, dict):
        return inner

    return body
