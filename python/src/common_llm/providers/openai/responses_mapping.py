# -*- coding: utf-8 -*-
"""
统一请求与 OpenAI Responses API 参数、响应之间的映射
创建日期：2026-11-16
修改日期：2026-11-16
"""
from typing import Any

from common_llm.core.model_support import ModelCallContext, build_model_response, collect_text, read_mapping
from common_llm.core.types import (
    FinishReason,
    ImagePart,
    Message,
    ModelResponse,
    ResponseContentPart,
    TextPart,
    ToolCallPart,
    ToolResultPart,
    Usage,
)

# Responses API 中由统一请求字段负责的参数名
RESPONSES_MAPPED_KEYS = (
    "model",
    "input",
    "instructions",
    "tools",
    "tool_choice",
    "text",
    "temperature",
    "max_output_tokens",
    "stream",
)


def build_image_url(part: ImagePart) -> str:
    """
    url 原样返回，base64 转为 data URI
    """
    if part.url:
        return part.url

    return f"data:{part.mime_type};base64,{part.base64}"


def build_user_content(message: Message) -> list[dict[str, Any]]:
    """
    把 user 消息内容转为 input_text 与 input_image 片段
    """
    content: list[dict[str, Any]] = []
    for part in message.content:
        if isinstance(part, TextPart):
            content.append({"type": "input_text", "text": part.text})
        elif isinstance(part, ImagePart):
            content.append({"type": "input_image", "image_url": build_image_url(part), "detail": "auto"})

    return content


def build_responses_input(messages: list[Message]) -> tuple[str | None, list[dict[str, Any]]]:
    """
    system 消息合并为 instructions，其余消息转为 input 条目
    """
    instructions: list[str] = []
    items: list[dict[str, Any]] = []
    for message in messages:
        if message.role == "system":
            instructions.append(collect_text(message.content))
            continue

        if message.role == "user":
            items.append({"role": "user", "content": build_user_content(message)})
            continue

        for part in message.content:
            if isinstance(part, TextPart) and message.role == "assistant":
                items.append({"role": "assistant", "content": part.text})
            elif isinstance(part, ToolCallPart):
                items.append({"type": "function_call", "call_id": part.id, "name": part.name, "arguments": part.arguments})
            elif isinstance(part, ToolResultPart):
                items.append({"type": "function_call_output", "call_id": part.tool_call_id, "output": part.content})

    if not instructions:
        return None, items

    return "\n".join(instructions), items


def build_responses_params(context: ModelCallContext) -> dict[str, Any]:
    """
    组装 responses.create 的参数，providerOptions 经 extra_body 透传
    """
    request = context.request
    instructions, input_items = build_responses_input(request.messages)
    params: dict[str, Any] = {
        "model": context.model,
        "input": input_items,
        "timeout": context.timeout_seconds,
    }
    if instructions is not None:
        params["instructions"] = instructions

    if request.temperature is not None:
        params["temperature"] = request.temperature

    if request.max_output_tokens is not None:
        params["max_output_tokens"] = request.max_output_tokens

    if request.tools:
        tools: list[dict[str, Any]] = []
        for tool in request.tools:
            tool_param: dict[str, Any] = {
                "type": "function",
                "name": tool.name,
                "parameters": tool.parameters,
            }
            if tool.description is not None:
                tool_param["description"] = tool.description

            tools.append(tool_param)

        params["tools"] = tools

    if request.tool_choice is not None:
        if request.tool_choice.type == "tool":
            params["tool_choice"] = {"type": "function", "name": request.tool_choice.name}
        else:
            params["tool_choice"] = request.tool_choice.type

    response_format = request.response_format
    if response_format is not None and response_format.type == "json_schema":
        format_param: dict[str, Any] = {
            "type": "json_schema",
            "name": response_format.name,
            "schema": response_format.json_schema,
        }
        if response_format.strict is not None:
            format_param["strict"] = response_format.strict

        params["text"] = {"format": format_param}
    elif response_format is not None:
        params["text"] = {"format": {"type": "text"}}

    if context.extra_body:
        params["extra_body"] = context.extra_body

    return params


def map_responses_usage(usage: dict[str, Any] | None) -> Usage:
    """
    映射 Responses usage
    """
    raw_usage = usage or {}
    input_details = read_mapping(raw_usage, "input_tokens_details")
    output_details = read_mapping(raw_usage, "output_tokens_details")
    return Usage(
        input_tokens=raw_usage.get("input_tokens"),
        output_tokens=raw_usage.get("output_tokens"),
        cached_input_tokens=input_details.get("cached_tokens"),
        reasoning_tokens=output_details.get("reasoning_tokens"),
        total_tokens=raw_usage.get("total_tokens"),
    )


def map_responses_response(
    provider: str,
    response: dict[str, Any],
    trace_id: str,
    request_id: str | None,
    include_raw: bool,
) -> ModelResponse:
    """
    把 Response 对象映射为 ModelResponse
    """
    content_parts: list[ResponseContentPart] = []
    has_tool_call = False
    for output_item in response.get("output") or []:
        item_type = output_item.get("type")
        if item_type == "message":
            for content in output_item.get("content") or []:
                if content.get("type") == "output_text":
                    content_parts.append(TextPart(text=content.get("text") or ""))
        elif item_type == "function_call":
            has_tool_call = True
            content_parts.append(
                ToolCallPart(
                    id=str(output_item.get("call_id")),
                    name=str(output_item.get("name")),
                    arguments=output_item.get("arguments") or "",
                )
            )

    status = response.get("status")
    incomplete_reason = read_mapping(response, "incomplete_details").get("reason")
    vendor_finish_reason = status
    finish_reason: FinishReason = "other"
    if has_tool_call:
        finish_reason = "tool_calls"
    elif status == "completed":
        finish_reason = "stop"
    elif status == "incomplete":
        vendor_finish_reason = incomplete_reason
        if incomplete_reason == "max_output_tokens":
            finish_reason = "length"
        elif incomplete_reason == "content_filter":
            finish_reason = "content_filter"

    raw_response: dict[str, Any] | None = None
    if include_raw:
        raw_response = response

    return build_model_response(
        provider=provider,
        model=response.get("model"),
        response_id=response.get("id"),
        content_parts=content_parts,
        finish_reason=finish_reason,
        vendor_finish_reason=vendor_finish_reason,
        usage=map_responses_usage(response.get("usage")),
        trace_id=trace_id,
        request_id=request_id,
        raw_response=raw_response,
    )
