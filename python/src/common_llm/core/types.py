# -*- coding: utf-8 -*-
"""
模型调用与 Agent 调用的请求、响应、事件与能力契约类型
创建日期：2026-11-16
修改日期：2026-11-16
"""
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from typing import Annotated, Any, Literal

from pydantic import Field, SerializeAsAny

from common_llm.core.contract import ContractModel
from common_llm.core.errors import ErrorInfo

MessageRole = Literal["system", "user", "assistant", "tool"]
FinishReason = Literal["stop", "length", "tool_calls", "content_filter", "other"]
AgentName = Literal["claude_code", "codex"]
AgentStatus = Literal["completed", "failed", "cancelled"]
SandboxMode = Literal["read-only", "workspace-write", "danger-full-access"]
ApprovalKind = Literal["command", "file_change", "tool"]
ApprovalDecision = Literal["approve", "approve_for_session", "decline", "cancel"]
FileChangeKind = Literal["add", "update", "delete"]

FEATURE_STREAMING = "streaming"
FEATURE_TOOL_CALLS = "tool_calls"
FEATURE_STRUCTURED_OUTPUT = "structured_output"
FEATURE_VISION = "vision"
FEATURE_REASONING = "reasoning"
FEATURE_SESSION_RESUME = "session_resume"
FEATURE_APPROVALS = "approvals"
FEATURE_INTERRUPT = "interrupt"
FEATURE_COST = "cost"


class TextPart(ContractModel):
    """
    文本内容片段
    """

    type: Literal["text"] = "text"
    text: str


class ImagePart(ContractModel):
    """
    图片内容片段，url 与 base64 二选一，base64 时必须带 mime_type
    """

    type: Literal["image"] = "image"
    url: str | None = None
    base64: str | None = None
    mime_type: str | None = None


class ToolCallPart(ContractModel):
    """
    assistant 消息中的工具调用，arguments 为 JSON 字符串
    """

    type: Literal["tool_call"] = "tool_call"
    id: str
    name: str
    arguments: str


class ToolResultPart(ContractModel):
    """
    tool 消息中的工具执行结果
    """

    type: Literal["tool_result"] = "tool_result"
    tool_call_id: str
    content: str
    is_error: bool | None = None


ContentPart = Annotated[TextPart | ImagePart | ToolCallPart | ToolResultPart, Field(discriminator="type")]
ResponseContentPart = Annotated[TextPart | ToolCallPart, Field(discriminator="type")]


class Message(ContractModel):
    """
    对话消息，content 为有序的内容片段
    """

    role: MessageRole
    content: list[ContentPart]

    @classmethod
    def from_text(cls, role: MessageRole, text: str) -> "Message":
        """
        创建只含一段文本的消息
        """
        return cls(role=role, content=[TextPart(text=text)])


class ToolDefinition(ContractModel):
    """
    可供模型调用的工具定义，parameters 为 JSON Schema 对象
    """

    name: str
    description: str | None = None
    parameters: dict[str, Any]


class ToolChoice(ContractModel):
    """
    工具选择策略，type 为 tool 时必须指定 name
    """

    type: Literal["auto", "none", "required", "tool"]
    name: str | None = None


class ResponseFormat(ContractModel):
    """
    输出格式，type 为 json_schema 时必须提供 name 与 schema
    """

    type: Literal["text", "json_schema"]
    name: str | None = None
    json_schema: dict[str, Any] | None = Field(default=None, alias="schema")
    strict: bool | None = None


class ModelRequest(ContractModel):
    """
    统一的模型调用请求
    """

    provider: str
    model: str | None = None
    messages: list[Message]
    temperature: float | None = None
    max_output_tokens: int | None = None
    timeout_seconds: float | None = None
    tools: list[ToolDefinition] | None = None
    tool_choice: ToolChoice | None = None
    response_format: ResponseFormat | None = None
    provider_options: dict[str, Any] | None = None
    include_raw_response: bool | None = None
    trace_id: str | None = None
    request_id: str | None = None
    metadata: dict[str, str] | None = None


class ToolCall(ContractModel):
    """
    响应中的工具调用
    """

    id: str
    name: str
    arguments: str


class Usage(ContractModel):
    """
    模型 token 用量，取不到的字段为 None
    """

    input_tokens: int | None = None
    output_tokens: int | None = None
    cached_input_tokens: int | None = None
    reasoning_tokens: int | None = None
    total_tokens: int | None = None


class ModelResponse(ContractModel):
    """
    统一的模型调用响应
    """

    provider: str
    model: str | None = None
    id: str | None = None
    content_parts: list[ResponseContentPart]
    text: str
    tool_calls: list[ToolCall]
    finish_reason: FinishReason
    vendor_finish_reason: str | None = None
    usage: Usage
    trace_id: str
    request_id: str | None = None
    raw_response: dict[str, Any] | None = None


class ModelEvent(ContractModel):
    """
    流式模型事件基类
    """

    type: str
    trace_id: str
    sequence: int
    provider: str


class ModelStartedEvent(ModelEvent):
    """
    流的第一个事件
    """

    type: Literal["started"] = "started"
    model: str | None = None
    id: str | None = None


class ModelTextDeltaEvent(ModelEvent):
    """
    文本增量
    """

    type: Literal["text_delta"] = "text_delta"
    text: str


class ModelToolCallDeltaEvent(ModelEvent):
    """
    工具调用增量，id 与 name 只在该工具调用的首个增量中出现
    """

    type: Literal["tool_call_delta"] = "tool_call_delta"
    index: int
    id: str | None = None
    name: str | None = None
    arguments_delta: str


class ModelUsageEvent(ModelEvent):
    """
    用量事件，每个流最多一次，紧挨在 completed 之前
    """

    type: Literal["usage"] = "usage"
    usage: Usage


class ModelCompletedEvent(ModelEvent):
    """
    成功终止事件，response 为累积结果
    """

    type: Literal["completed"] = "completed"
    response: ModelResponse


class ModelErrorEvent(ModelEvent):
    """
    失败终止事件
    """

    type: Literal["error"] = "error"
    error: ErrorInfo


class AgentRequest(ContractModel):
    """
    统一的 Coding Agent 调用请求
    """

    agent: AgentName
    prompt: str
    working_directory: str | None = None
    session_id: str | None = None
    model: str | None = None
    timeout_seconds: float | None = None
    permission_mode: str | None = None
    sandbox_mode: SandboxMode | None = None
    allowed_tools: list[str] | None = None
    env_allowlist: list[str] | None = None
    native_options: dict[str, Any] | None = None
    trace_id: str | None = None


class AgentUsage(ContractModel):
    """
    Agent 用量，在 Usage 基础上增加成本与轮数
    """

    input_tokens: int | None = None
    output_tokens: int | None = None
    cached_input_tokens: int | None = None
    reasoning_tokens: int | None = None
    total_tokens: int | None = None
    cost_usd: float | None = None
    num_turns: int | None = None


class FileChange(ContractModel):
    """
    文件变更条目
    """

    path: str
    kind: FileChangeKind


class AgentEvent(ContractModel):
    """
    流式 Agent 事件基类
    """

    type: str
    trace_id: str
    run_id: str
    session_id: str | None = None
    sequence: int
    agent: AgentName


class AgentResult(ContractModel):
    """
    单次 Agent run 的结果
    """

    run_id: str
    session_id: str | None = None
    status: AgentStatus
    final_text: str
    events: list[SerializeAsAny[AgentEvent]] | None = None
    usage: AgentUsage | None = None
    exit_code: int | None = None
    error: ErrorInfo | None = None


class AgentStartedEvent(AgentEvent):
    """
    run 开始
    """

    type: Literal["started"] = "started"
    model: str | None = None
    working_directory: str


class AgentMessageEvent(AgentEvent):
    """
    assistant 或 user 文本消息
    """

    type: Literal["message"] = "message"
    role: Literal["assistant", "user"]
    text: str
    is_delta: bool


class AgentToolStartedEvent(AgentEvent):
    """
    工具开始执行
    """

    type: Literal["tool_started"] = "tool_started"
    tool_call_id: str
    tool_name: str
    input: dict[str, Any] | None = None


class AgentToolCompletedEvent(AgentEvent):
    """
    工具执行结束，output 已按 max_tool_output_chars 截断
    """

    type: Literal["tool_completed"] = "tool_completed"
    tool_call_id: str
    tool_name: str | None = None
    output: str | None = None
    is_error: bool


class AgentFileChangedEvent(AgentEvent):
    """
    文件变更
    """

    type: Literal["file_changed"] = "file_changed"
    changes: list[FileChange]


class AgentApprovalRequestedEvent(AgentEvent):
    """
    审批请求及其处理结果
    """

    type: Literal["approval_requested"] = "approval_requested"
    approval_id: str
    kind: ApprovalKind
    summary: str
    decision: ApprovalDecision


class AgentUsageEvent(AgentEvent):
    """
    run 用量
    """

    type: Literal["usage"] = "usage"
    usage: AgentUsage


class AgentCompletedEvent(AgentEvent):
    """
    成功终止事件
    """

    type: Literal["completed"] = "completed"
    result: AgentResult


class AgentFailedEvent(AgentEvent):
    """
    失败终止事件
    """

    type: Literal["failed"] = "failed"
    error: ErrorInfo


class AgentCancelledEvent(AgentEvent):
    """
    取消终止事件
    """

    type: Literal["cancelled"] = "cancelled"


class AgentRawVendorEvent(AgentEvent):
    """
    无法映射为标准事件的厂商原始事件
    """

    type: Literal["raw_vendor_event"] = "raw_vendor_event"
    vendor_type: str
    payload: dict[str, Any]


AGENT_TERMINAL_EVENT_TYPES = ("completed", "failed", "cancelled")


class ProviderCapability(ContractModel):
    """
    Provider 实际支持的能力集合
    """

    provider: str
    features: list[str]


class AgentCapability(ContractModel):
    """
    Agent 的实现方式、运行时探测结果与能力集合
    """

    agent: AgentName
    implementation: Literal["sdk", "cli", "app_server"]
    executable_path: str | None = None
    version: str | None = None
    features: list[str]
    event_types: list[str]
    supported_options: list[str] | None = None


@dataclass
class ApprovalRequest:
    """
    传给审批处理器的请求内容，raw 为厂商原始参数
    """

    approval_id: str
    kind: ApprovalKind
    summary: str
    agent: AgentName
    run_id: str
    raw: dict[str, Any] = field(default_factory=dict)


ApprovalHandler = Callable[[ApprovalRequest], Awaitable[ApprovalDecision]]
