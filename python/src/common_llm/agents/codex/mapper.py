# -*- coding: utf-8 -*-
"""
把 Codex App Server 的通知与审批请求映射为统一 AgentEvent
创建日期：2026-11-16
修改日期：2026-11-16
"""
import json
from dataclasses import dataclass, field
from typing import Any

from common_llm.core.agent_support import AgentEventFactory, truncate_by_code_points
from common_llm.core.errors import ErrorCategory, ErrorInfo, build_error_info
from common_llm.core.types import AgentEvent, AgentUsage, ApprovalDecision, FileChange, FileChangeKind

AGENT_NAME = "codex"
TOOL_ITEM_TYPES = ("commandExecution", "mcpToolCall", "webSearch")
FAILED_ITEM_STATUSES = ("failed", "declined")
COMMAND_APPROVAL_METHOD = "item/commandExecution/requestApproval"
FILE_CHANGE_APPROVAL_METHOD = "item/fileChange/requestApproval"
APPROVAL_METHODS = (COMMAND_APPROVAL_METHOD, FILE_CHANGE_APPROVAL_METHOD)


@dataclass
class WireNotification:
    """
    App Server 发出的通知
    """

    method: str
    params: dict[str, Any]


@dataclass
class WireApproval:
    """
    审批请求及审批处理器给出的决定
    """

    method: str
    params: dict[str, Any]
    decision: ApprovalDecision


@dataclass
class WireServerRequest:
    """
    审批以外的服务端请求，适配层回复空结果
    """

    method: str
    params: dict[str, Any]


@dataclass
class WireClosed:
    """
    传输层结束标记，error 为读取线程退出时的异常
    """

    error: BaseException


@dataclass
class TokenTotals:
    """
    累计 token 用量快照
    """

    values: dict[str, Any] = field(default_factory=dict)


def subtract_count(current: Any, baseline: Any) -> int | None:
    """
    当前累计值减去基线值，基线缺失时按 0 计
    """
    if not isinstance(current, int):
        return None

    if not isinstance(baseline, int):
        return current

    return current - baseline


def build_turn_usage(turn_total: dict[str, Any] | None, baseline: dict[str, Any] | None) -> AgentUsage | None:
    """
    用本轮最后一次累计值减去本轮开始前的累计值得到单个 run 的用量
    """
    if turn_total is None:
        return None

    base = baseline or {}
    return AgentUsage(
        input_tokens=subtract_count(turn_total.get("inputTokens"), base.get("inputTokens")),
        output_tokens=subtract_count(turn_total.get("outputTokens"), base.get("outputTokens")),
        cached_input_tokens=subtract_count(turn_total.get("cachedInputTokens"), base.get("cachedInputTokens")),
        reasoning_tokens=subtract_count(turn_total.get("reasoningOutputTokens"), base.get("reasoningOutputTokens")),
        total_tokens=subtract_count(turn_total.get("totalTokens"), base.get("totalTokens")),
        cost_usd=None,
        num_turns=None,
    )


def build_tool_input(item: dict[str, Any]) -> tuple[str, dict[str, Any] | None]:
    """
    计算工具条目的 toolName 与 input
    """
    item_type = item.get("type")
    if item_type == "commandExecution":
        return "command_execution", {"command": item.get("command"), "cwd": item.get("cwd")}

    if item_type == "mcpToolCall":
        arguments = item.get("arguments")
        tool_input: dict[str, Any] | None = None
        if isinstance(arguments, dict):
            tool_input = arguments

        return f"{item.get('server')}.{item.get('tool')}", tool_input

    return "web_search", {"query": item.get("query")}


def map_file_change_kind(kind: Any) -> FileChangeKind:
    """
    取 kind.type 作为变更类型，无法识别时按 update 处理
    """
    raw_kind = kind
    if isinstance(kind, dict):
        raw_kind = kind.get("type", "update")

    if raw_kind == "add":
        return "add"

    if raw_kind == "delete":
        return "delete"

    return "update"


def build_turn_error(turn_error: Any) -> ErrorInfo:
    """
    把失败 turn 的 error 字段转为 PROVIDER_ERROR
    """
    message_text = "Codex turn 执行失败"
    vendor_code: str | None = None
    if isinstance(turn_error, dict):
        message_text = str(turn_error.get("message") or message_text)
        if turn_error.get("codexErrorInfo") is not None:
            vendor_code = json.dumps(turn_error.get("codexErrorInfo"), ensure_ascii=False)

    return build_error_info(ErrorCategory.PROVIDER_ERROR, message_text, provider=AGENT_NAME, retriable=False, vendor_code=vendor_code)


class CodexTurnMapper:
    """
    有状态的映射器，只处理当前 run 的 turn，并按累计值差额计算用量
    """

    def __init__(self, factory: AgentEventFactory, max_tool_output_chars: int) -> None:
        self.factory = factory
        self.max_tool_output_chars = max_tool_output_chars
        self.model: str | None = None
        self.working_directory = ""
        self.turn_id: str | None = None
        self.baseline_total: dict[str, Any] | None = None
        self.turn_total: dict[str, Any] | None = None
        self.final_text = ""

    def record_thread(self, thread_id: str, model: str | None, working_directory: str) -> None:
        """
        记录 thread/start 或 thread/resume 响应中的会话信息
        """
        self.factory.session_id = thread_id
        self.model = model
        self.working_directory = working_directory

    def map_approval(self, approval: WireApproval) -> list[AgentEvent]:
        """
        映射审批请求，approvalId 缺失时取 itemId
        """
        if self.turn_id is None:
            return []

        params = approval.params
        approval_id = str(params.get("approvalId") or params.get("itemId") or "")
        if approval.method == FILE_CHANGE_APPROVAL_METHOD:
            summary = str(params.get("reason") or "file change")
            return [self.factory.approval_requested(approval_id, "file_change", summary, approval.decision)]

        summary = str(params.get("command") or "")
        return [self.factory.approval_requested(approval_id, "command", summary, approval.decision)]

    def map_notification(self, notification: WireNotification) -> list[AgentEvent]:
        """
        映射单条通知，返回按顺序排列的事件
        """
        method = notification.method
        params = notification.params

        if method == "thread/tokenUsage/updated":
            self._record_token_usage(params)
            return []

        if method == "turn/started":
            return self._map_turn_started(params)

        if self.turn_id is None:
            return [self.factory.raw_vendor_event(method, params)]

        if method == "item/agentMessage/delta":
            return [self.factory.message(str(params.get("delta", "")), True)]

        if method == "item/started":
            return self._map_item_started(params)

        if method == "item/completed":
            return self._map_item_completed(params)

        if method == "turn/completed":
            return self._map_turn_completed(params)

        return [self.factory.raw_vendor_event(method, params)]

    def _record_token_usage(self, params: dict[str, Any]) -> None:
        token_usage = params.get("tokenUsage")
        if not isinstance(token_usage, dict) or not isinstance(token_usage.get("total"), dict):
            return

        if self.turn_id is not None and params.get("turnId") == self.turn_id:
            self.turn_total = token_usage["total"]
            return

        self.baseline_total = token_usage["total"]

    def _map_turn_started(self, params: dict[str, Any]) -> list[AgentEvent]:
        turn = params.get("turn")
        if self.turn_id is not None or not isinstance(turn, dict):
            return [self.factory.raw_vendor_event("turn/started", params)]

        self.turn_id = str(turn.get("id"))
        self.final_text = ""
        return [self.factory.started(self.model, self.working_directory)]

    def _map_item_started(self, params: dict[str, Any]) -> list[AgentEvent]:
        item = params.get("item")
        if not isinstance(item, dict) or item.get("type") not in TOOL_ITEM_TYPES:
            return []

        tool_name, tool_input = build_tool_input(item)
        return [self.factory.tool_started(str(item.get("id")), tool_name, tool_input)]

    def _map_item_completed(self, params: dict[str, Any]) -> list[AgentEvent]:
        item = params.get("item")
        if not isinstance(item, dict):
            return []

        item_type = item.get("type")
        if item_type == "agentMessage":
            self.final_text = str(item.get("text") or "")
            return [self.factory.message(self.final_text, False)]

        if item_type in TOOL_ITEM_TYPES:
            tool_name, _ = build_tool_input(item)
            exit_code = item.get("exitCode")
            is_error = item.get("status") in FAILED_ITEM_STATUSES
            if isinstance(exit_code, int) and exit_code != 0:
                is_error = True

            output = item.get("aggregatedOutput")
            truncated = truncate_by_code_points(output if isinstance(output, str) else None, self.max_tool_output_chars)
            return [self.factory.tool_completed(str(item.get("id")), tool_name, truncated, is_error)]

        if item_type == "fileChange":
            changes: list[FileChange] = []
            raw_changes = item.get("changes")
            if isinstance(raw_changes, list):
                for change in raw_changes:
                    if not isinstance(change, dict):
                        continue

                    changes.append(FileChange(path=str(change.get("path")), kind=map_file_change_kind(change.get("kind"))))

            return [self.factory.file_changed(changes)]

        return []

    def _map_turn_completed(self, params: dict[str, Any]) -> list[AgentEvent]:
        turn = params.get("turn")
        if not isinstance(turn, dict):
            return [self.factory.raw_vendor_event("turn/completed", params)]

        if str(turn.get("id")) != self.turn_id:
            return [self.factory.raw_vendor_event("turn/completed", params)]

        events: list[AgentEvent] = []
        usage = build_turn_usage(self.turn_total, self.baseline_total)
        if usage is not None:
            events.append(self.factory.usage(usage))

        status = turn.get("status")
        if status == "completed":
            result = self.factory.build_result("completed", self.final_text, usage, None)
            events.append(self.factory.completed(result))
        elif status == "interrupted":
            events.append(self.factory.cancelled())
        else:
            events.append(self.factory.failed(build_turn_error(turn.get("error"))))

        return events
