# -*- coding: utf-8 -*-
"""
统一请求与 OpenAI Chat Completions 参数、响应之间的映射
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
from common_llm.providers.openai.responses_mapping import build_image_url

# Chat Completions 中由统一请求字段负责的参数名
CHAT_MAPPED_KEYS = (
    "model",
    "messages",
    "tools",
    "tool_choice",
    "response_format",
    "temperature",
    "max_completion_tokens",
    "stream",
    "stream_options",
)


def build_chat_messages(messages: list[Message]) -> list[dict[str, Any]]:
    """
    转为 Chat Completions messages，system 保留为 system 角色
    """
    chat_messages: list[dict[str, Any]] = []
    for message in messages:
        if message.role == "system":
            chat_messages.append({"role": "system", "content": collect_text(message.content)})
            continue

        if message.role == "user":
            content: list[dict[str, Any]] = []
            for part in message.content:
                if isinstance(part, TextPart):
                    content.append({"type": "text", "text": part.text})
                elif isinstance(part, ImagePart):
                    content.append({"type": "image_url", "image_url": {"url": build_image_url(part)}})

            chat_messages.append({"role": "user", "content": content})
            continue

        if message.role == "assistant":
            chat_messages.append(build_assistant_message(message))
            continue

        for part in message.content:
            if isinstance(part, ToolResultPart):
                chat_messages.append({"role": "tool", "tool_call_id": part.tool_call_id, "content": part.content})

    return chat_messages


def build_assistant_message(message: Message) -> dict[str, Any]:
    """
    assistant 消息的文本与工具调用合并为一条
    """
    tool_calls: list[dict[str, Any]] = []
    for part in message.content:
        if isinstance(part, ToolCallPart):
            tool_calls.append({"id": part.id, "type": "function", "function": {"name": part.name, "arguments": part.arguments}})

    text = collect_text(message.content)
    assistant_message: dict[str, Any] = {
        "role": "assistant",
        "content": text or None,
    }
    if tool_calls:
        assistant_message["tool_calls"] = tool_calls

    return assistant_message


def build_chat_params(context: ModelCallContext, is_stream: bool) -> dict[str, Any]:
    """
    组装 chat.completions.create 的参数，流式请求附带 include_usage
    """
    request = context.request
    params: dict[str, Any] = {
        "model": context.model,
        "messages": build_chat_messages(request.messages),
        "timeout": context.timeout_seconds,
    }
    if is_stream:
        params["stream"] = True
        params["stream_options"] = {"include_usage": True}

    if request.temperature is not None:
        params["temperature"] = request.temperature

    if request.max_output_tokens is not None:
        params["max_completion_tokens"] = request.max_output_tokens

    if request.tools:
        tools: list[dict[str, Any]] = []
        for tool in request.tools:
            function: dict[str, Any] = {
                "name": tool.name,
                "parameters": tool.parameters,
            }
            if tool.description is not None:
                function["description"] = tool.description

            tools.append({"type": "function", "function": function})

        params["tools"] = tools

    if request.tool_choice is not None:
        if request.tool_choice.type == "tool":
            params["tool_choice"] = {"type": "function", "function": {"name": request.tool_choice.name}}
        else:
            params["tool_choice"] = request.tool_choice.type

    response_format = request.response_format
    if response_format is not None and response_format.type == "json_schema":
        json_schema: dict[str, Any] = {
            "name": response_format.name,
            "schema": response_format.json_schema,
        }
        if response_format.strict is not None:
            json_schema["strict"] = response_format.strict

        params["response_format"] = {"type": "json_schema", "json_schema": json_schema}
    elif response_format is not None:
        params["response_format"] = {"type": "text"}

    if context.extra_body:
        params["extra_body"] = context.extra_body

    return params


def map_chat_finish(finish_reason: str | None) -> FinishReason:
    """
    映射 Chat Completions finish_reason
    """
    if finish_reason == "stop":
        return "stop"

    if finish_reason == "length":
        return "length"

    if finish_reason in ("tool_calls", "function_call"):
        return "tool_calls"

    if finish_reason == "content_filter":
        return "content_filter"

    return "other"


def map_chat_usage(usage: dict[str, Any] | None) -> Usage:
    """
    映射 Chat Completions usage
    """
    raw_usage = usage or {}
    prompt_details = read_mapping(raw_usage, "prompt_tokens_details")
    completion_details = read_mapping(raw_usage, "completion_tokens_details")
    return Usage(
        input_tokens=raw_usage.get("prompt_tokens"),
        output_tokens=raw_usage.get("completion_tokens"),
        cached_input_tokens=prompt_details.get("cached_tokens"),
        reasoning_tokens=completion_details.get("reasoning_tokens"),
        total_tokens=raw_usage.get("total_tokens"),
    )


def map_chat_completion(
    provider: str,
    completion: dict[str, Any],
    trace_id: str,
    request_id: str | None,
    include_raw: bool,
) -> ModelResponse:
    """
    把 ChatCompletion 映射为 ModelResponse，只取第一个 choice
    """
    choices = completion.get("choices") or []
    choice: dict[str, Any] = {}
    if choices:
        choice = choices[0]

    message = read_mapping(choice, "message")
    content_parts: list[ResponseContentPart] = []
    if message.get("content"):
        content_parts.append(TextPart(text=str(message["content"])))

    for tool_call in message.get("tool_calls") or []:
        function = read_mapping(tool_call, "function")
        content_parts.append(ToolCallPart(id=str(tool_call.get("id")), name=str(function.get("name")), arguments=function.get("arguments") or ""))

    finish_reason = choice.get("finish_reason")
    raw_response: dict[str, Any] | None = None
    if include_raw:
        raw_response = completion

    return build_model_response(
        provider=provider,
        model=completion.get("model"),
        response_id=completion.get("id"),
        content_parts=content_parts,
        finish_reason=map_chat_finish(finish_reason),
        vendor_finish_reason=finish_reason,
        usage=map_chat_usage(completion.get("usage")),
        trace_id=trace_id,
        request_id=request_id,
        raw_response=raw_response,
    )
