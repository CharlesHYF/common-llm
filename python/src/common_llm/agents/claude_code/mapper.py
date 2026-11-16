# -*- coding: utf-8 -*-
"""
把 Claude Agent SDK 消息映射为统一 AgentEvent
创建日期：2026-11-16
修改日期：2026-11-16
"""
from dataclasses import dataclass
from typing import Any

from claude_agent_sdk import (
    AssistantMessage,
    RateLimitEvent,
    ResultMessage,
    StreamEvent,
    SystemMessage,
    TextBlock,
    ToolResultBlock,
    ToolUseBlock,
    UserMessage,
)

from common_llm.core.agent_support import AgentEventFactory, truncate_by_code_points
from common_llm.core.errors import ErrorCategory, ErrorInfo, build_error_info
from common_llm.core.types import AgentEvent, AgentUsage, ApprovalDecision

AGENT_NAME = "claude_code"


@dataclass
class PendingApproval:
    """
    审批回调先于对应 tool_started 到达时暂存的审批结果
    """

    approval_id: str
    summary: str
    decision: ApprovalDecision


def stringify_tool_result(content: Any) -> str | None:
    """
    把 tool_result 的 content 转为字符串，数组形式只拼接 text 元素
    """
    if content is None:
        return None

    if isinstance(content, str):
        return content

    text_parts: list[str] = []
    if isinstance(content, list):
        for part in content:
            if isinstance(part, dict) and part.get("type") == "text":
                text_parts.append(str(part.get("text", "")))

    return "\n".join(text_parts)


def read_int(values: dict[str, Any], key: str) -> int | None:
    """
    读取整数字段，缺失或类型不符时返回 None
    """
    value = values.get(key)
    if isinstance(value, bool) or not isinstance(value, int):
        return None

    return value


def build_claude_usage(message: ResultMessage) -> AgentUsage:
    """
    按契约口径计算 run 用量，inputTokens 为含缓存的总输入量
    """
    usage = message.usage or {}
    input_tokens = read_int(usage, "input_tokens")
    cache_creation = read_int(usage, "cache_creation_input_tokens") or 0
    cache_read = read_int(usage, "cache_read_input_tokens") or 0
    total_input: int | None = None
    if input_tokens is not None:
        total_input = input_tokens + cache_creation + cache_read

    output_tokens = read_int(usage, "output_tokens")
    total_tokens: int | None = None
    if total_input is not None and output_tokens is not None:
        total_tokens = total_input + output_tokens

    output_details = usage.get("output_tokens_details")
    reasoning_tokens: int | None = None
    if isinstance(output_details, dict):
        reasoning_tokens = read_int(output_details, "thinking_tokens")

    return AgentUsage(
        input_tokens=total_input,
        output_tokens=output_tokens,
        cached_input_tokens=read_int(usage, "cache_read_input_tokens"),
        reasoning_tokens=reasoning_tokens,
        total_tokens=total_tokens,
        cost_usd=message.total_cost_usd,
        num_turns=message.num_turns,
    )


def build_result_error(message: ResultMessage) -> ErrorInfo:
    """
    把失败的 result 消息转为 PROVIDER_ERROR
    """
    error_messages: list[str] = []
    for error_text in message.errors or []:
        error_messages.append(str(error_text))

    message_text = "; ".join(error_messages)
    if not message_text:
        message_text = f"Claude Code 执行失败：{message.subtype}"

    return build_error_info(ErrorCategory.PROVIDER_ERROR, message_text, provider=AGENT_NAME, retriable=False, vendor_code=message.subtype)


class ClaudeMessageMapper:
    """
    有状态的消息映射器，记录工具名、已报告的审批与暂存的审批结果
    """

    def __init__(self, factory: AgentEventFactory, max_tool_output_chars: int) -> None:
        self.factory = factory
        self.max_tool_output_chars = max_tool_output_chars
        self.tool_names: dict[str, str] = {}
        self.started_tool_ids: set[str] = set()
        self.reported_approval_ids: set[str] = set()
        self.pending_approvals: dict[str, PendingApproval] = {}
        self.final_text = ""

    def record_approval(self, approval: PendingApproval) -> list[AgentEvent]:
        """
        处理审批回调结果，对应工具已开始时立即返回事件，否则暂存到 tool_started 之后
        """
        if approval.approval_id in self.started_tool_ids or not approval.approval_id:
            return self._build_approval_events(approval)

        self.pending_approvals[approval.approval_id] = approval
        return []

    def _build_approval_events(self, approval: PendingApproval) -> list[AgentEvent]:
        if approval.approval_id in self.reported_approval_ids:
            return []

        self.reported_approval_ids.add(approval.approval_id)
        return [self.factory.approval_requested(approval.approval_id, "tool", approval.summary, approval.decision)]

    def map_message(self, message: Any, is_interrupted: bool) -> list[AgentEvent]:
        """
        映射单条 SDK 消息，返回按顺序排列的事件
        """
        if isinstance(message, SystemMessage):
            return self._map_system(message)

        if isinstance(message, StreamEvent):
            return self._map_stream_event(message)

        if isinstance(message, AssistantMessage):
            return self._map_assistant(message)

        if isinstance(message, UserMessage):
            return self._map_user(message)

        if isinstance(message, ResultMessage):
            return self._map_result(message, is_interrupted)

        if isinstance(message, RateLimitEvent):
            payload = {
                "rate_limit_info": message.rate_limit_info.raw,
            }
            return [self.factory.raw_vendor_event("rate_limit_event", payload)]

        return [self.factory.raw_vendor_event(type(message).__name__, {})]

    def _map_system(self, message: SystemMessage) -> list[AgentEvent]:
        data = message.data
        if message.subtype == "init":
            session_id = data.get("session_id")
            if isinstance(session_id, str):
                self.factory.session_id = session_id

            model = data.get("model")
            cwd = data.get("cwd")
            return [self.factory.started(model if isinstance(model, str) else None, str(cwd or ""))]

        if message.subtype == "permission_denied":
            approval = PendingApproval(
                approval_id=str(data.get("tool_use_id") or ""),
                summary=str(data.get("tool_name") or ""),
                decision="decline",
            )
            return self._build_approval_events(approval)

        return [self.factory.raw_vendor_event(f"system/{message.subtype}", dict(data))]

    def _map_stream_event(self, message: StreamEvent) -> list[AgentEvent]:
        event = message.event
        delta = event.get("delta")
        if event.get("type") != "content_block_delta" or not isinstance(delta, dict):
            return []

        if delta.get("type") != "text_delta":
            return []

        return [self.factory.message(str(delta.get("text", "")), True)]

    def _map_assistant(self, message: AssistantMessage) -> list[AgentEvent]:
        events: list[AgentEvent] = []
        for block in message.content:
            if isinstance(block, TextBlock):
                self.final_text = block.text
                events.append(self.factory.message(block.text, False))
                continue

            if not isinstance(block, ToolUseBlock):
                continue

            self.tool_names[block.id] = block.name
            self.started_tool_ids.add(block.id)
            events.append(self.factory.tool_started(block.id, block.name, block.input))
            pending = self.pending_approvals.pop(block.id, None)
            if pending is not None:
                events.extend(self._build_approval_events(pending))

        return events

    def _map_user(self, message: UserMessage) -> list[AgentEvent]:
        if not isinstance(message.content, list):
            return []

        events: list[AgentEvent] = []
        for block in message.content:
            if not isinstance(block, ToolResultBlock):
                continue

            output = truncate_by_code_points(stringify_tool_result(block.content), self.max_tool_output_chars)
            events.append(self.factory.tool_completed(block.tool_use_id, self.tool_names.get(block.tool_use_id), output, block.is_error is True))

        return events

    def _map_result(self, message: ResultMessage, is_interrupted: bool) -> list[AgentEvent]:
        if self.factory.session_id is None and message.session_id:
            self.factory.session_id = message.session_id

        usage = build_claude_usage(message)
        events: list[AgentEvent] = [self.factory.usage(usage)]
        if is_interrupted:
            events.append(self.factory.cancelled())
            return events

        if message.subtype == "success" and message.is_error is not True:
            result = self.factory.build_result("completed", message.result or "", usage, None)
            events.append(self.factory.completed(result))
            return events

        events.append(self.factory.failed(build_result_error(message)))
        return events
