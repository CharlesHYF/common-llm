// Coding Agent 的请求、结果、用量、审批与事件结构
// 创建日期：2026-11-16
// 修改日期：2026-11-16

package core

import (
	"context"
	"encoding/json"
)

// Agent 名称
const (
	AgentClaudeCode = "claude_code"
	AgentCodex      = "codex"
)

// AgentStatus run 的终止状态。
type AgentStatus string

const (
	AgentStatusCompleted AgentStatus = "completed"
	AgentStatusFailed    AgentStatus = "failed"
	AgentStatusCancelled AgentStatus = "cancelled"
)

// AgentEventType Agent 事件类型。
type AgentEventType string

const (
	AgentEventStarted           AgentEventType = "started"
	AgentEventMessage           AgentEventType = "message"
	AgentEventToolStarted       AgentEventType = "tool_started"
	AgentEventToolCompleted     AgentEventType = "tool_completed"
	AgentEventFileChanged       AgentEventType = "file_changed"
	AgentEventApprovalRequested AgentEventType = "approval_requested"
	AgentEventUsage             AgentEventType = "usage"
	AgentEventCompleted         AgentEventType = "completed"
	AgentEventFailed            AgentEventType = "failed"
	AgentEventCancelled         AgentEventType = "cancelled"
	AgentEventRawVendor         AgentEventType = "raw_vendor_event"
)

// ApprovalKind 审批请求类别。
type ApprovalKind string

const (
	ApprovalKindCommand    ApprovalKind = "command"
	ApprovalKindFileChange ApprovalKind = "file_change"
	ApprovalKindTool       ApprovalKind = "tool"
)

// ApprovalDecision 审批处理结果。
type ApprovalDecision string

const (
	DecisionApprove           ApprovalDecision = "approve"
	DecisionApproveForSession ApprovalDecision = "approve_for_session"
	DecisionDecline           ApprovalDecision = "decline"
	DecisionCancel            ApprovalDecision = "cancel"
)

// ApprovalRequest 传给审批处理器的请求内容。
type ApprovalRequest struct {
	RunID      string
	SessionID  string
	ApprovalID string
	Kind       ApprovalKind
	Summary    string
	Payload    map[string]any
}

// ApprovalHandler 审批处理器，返回错误时按 decline 处理。
type ApprovalHandler func(ctx context.Context, request ApprovalRequest) (ApprovalDecision, error)

// AgentRequest 统一的 Coding Agent 调用请求。
type AgentRequest struct {
	Agent            string         `json:"agent"`
	Prompt           string         `json:"prompt"`
	WorkingDirectory string         `json:"workingDirectory,omitempty"`
	SessionID        string         `json:"sessionId,omitempty"`
	Model            string         `json:"model,omitempty"`
	TimeoutSeconds   *float64       `json:"timeoutSeconds,omitempty"`
	PermissionMode   string         `json:"permissionMode,omitempty"`
	SandboxMode      string         `json:"sandboxMode,omitempty"`
	AllowedTools     []string       `json:"allowedTools,omitempty"`
	EnvAllowlist     []string       `json:"envAllowlist,omitempty"`
	NativeOptions    map[string]any `json:"nativeOptions,omitempty"`
	TraceID          string         `json:"traceId,omitempty"`
	// ApprovalHandler 只在语言内使用，不进入契约 JSON
	ApprovalHandler ApprovalHandler `json:"-"`
}

// AgentUsage Agent 用量，取不到的统计值为 nil。
type AgentUsage struct {
	InputTokens       *int64   `json:"inputTokens"`
	OutputTokens      *int64   `json:"outputTokens"`
	CachedInputTokens *int64   `json:"cachedInputTokens"`
	ReasoningTokens   *int64   `json:"reasoningTokens"`
	TotalTokens       *int64   `json:"totalTokens"`
	CostUSD           *float64 `json:"costUsd"`
	NumTurns          *int64   `json:"numTurns"`
}

// AgentResult 一次 run 的结果。
type AgentResult struct {
	RunID     string       `json:"runId"`
	SessionID *string      `json:"sessionId"`
	Status    AgentStatus  `json:"status"`
	FinalText string       `json:"finalText"`
	Events    []AgentEvent `json:"events,omitempty"`
	Usage     *AgentUsage  `json:"usage"`
	ExitCode  *int         `json:"exitCode"`
	Error     *ErrorInfo   `json:"error"`
}

// FileChange 一处文件变更。
type FileChange struct {
	Path string `json:"path"`
	Kind string `json:"kind"`
}

// AgentEvent Agent 流式事件，按 Type 决定哪些字段有效。
type AgentEvent struct {
	Type             AgentEventType
	TraceID          string
	RunID            string
	SessionID        *string
	Sequence         int
	Agent            string
	Model            *string
	WorkingDirectory string
	Role             string
	Text             string
	IsDelta          bool
	ToolCallID       string
	ToolName         *string
	Input            map[string]any
	Output           *string
	IsError          bool
	Changes          []FileChange
	ApprovalID       string
	Kind             ApprovalKind
	Summary          string
	Decision         ApprovalDecision
	Usage            *AgentUsage
	Result           *AgentResult
	Error            *ErrorInfo
	VendorType       string
	Payload          map[string]any
}

// IsTerminal 判断事件是否为终止事件。
func (e AgentEvent) IsTerminal() bool {
	return e.Type == AgentEventCompleted || e.Type == AgentEventFailed || e.Type == AgentEventCancelled
}

// MarshalJSON 按事件类型输出契约字段。
func (e AgentEvent) MarshalJSON() ([]byte, error) {
	fields := map[string]any{
		"type":      e.Type,
		"traceId":   e.TraceID,
		"runId":     e.RunID,
		"sessionId": e.SessionID,
		"sequence":  e.Sequence,
		"agent":     e.Agent,
	}

	switch e.Type {
	case AgentEventStarted:
		fields["model"] = e.Model
		fields["workingDirectory"] = e.WorkingDirectory
	case AgentEventMessage:
		fields["role"] = e.Role
		fields["text"] = e.Text
		fields["isDelta"] = e.IsDelta
	case AgentEventToolStarted:
		fields["toolCallId"] = e.ToolCallID
		fields["toolName"] = e.ToolName
		fields["input"] = e.Input
	case AgentEventToolCompleted:
		fields["toolCallId"] = e.ToolCallID
		fields["toolName"] = e.ToolName
		fields["output"] = e.Output
		fields["isError"] = e.IsError
	case AgentEventFileChanged:
		fields["changes"] = e.Changes
	case AgentEventApprovalRequested:
		fields["approvalId"] = e.ApprovalID
		fields["kind"] = e.Kind
		fields["summary"] = e.Summary
		fields["decision"] = e.Decision
	case AgentEventUsage:
		fields["usage"] = e.Usage
	case AgentEventCompleted:
		fields["result"] = e.Result
	case AgentEventFailed:
		fields["error"] = e.Error
	case AgentEventRawVendor:
		fields["vendorType"] = e.VendorType
		fields["payload"] = e.Payload
	}

	return json.Marshal(fields)
}

// AgentEventBuilder 为同一个 run 组装事件，统一填写公共字段与递增序号。
type AgentEventBuilder struct {
	agent        string
	traceID      string
	runID        string
	sessionID    *string
	nextSequence int
}

// NewAgentEventBuilder 创建事件组装器，序号从 0 开始。
func NewAgentEventBuilder(agent string, traceID string, runID string) *AgentEventBuilder {
	return &AgentEventBuilder{
		agent:   agent,
		traceID: traceID,
		runID:   runID,
	}
}

// SetSessionID 记录会话 ID，此后的事件都携带该值。
func (b *AgentEventBuilder) SetSessionID(sessionID string) {
	b.sessionID = NonEmptyStringPtr(sessionID)
}

// SessionID 返回当前会话 ID，未知时为 nil。
func (b *AgentEventBuilder) SessionID() *string {
	return b.sessionID
}

// RunID 返回本次 run 的 ID。
func (b *AgentEventBuilder) RunID() string {
	return b.runID
}

// NewEvent 创建带公共字段的事件并递增序号，调用方再填写类型相关字段。
func (b *AgentEventBuilder) NewEvent(eventType AgentEventType) AgentEvent {
	event := AgentEvent{
		Type:      eventType,
		TraceID:   b.traceID,
		RunID:     b.runID,
		SessionID: b.sessionID,
		Sequence:  b.nextSequence,
		Agent:     b.agent,
	}
	b.nextSequence++

	return event
}

// BuildResult 组装 AgentResult，携带当前会话 ID。
func (b *AgentEventBuilder) BuildResult(status AgentStatus, finalText string, usage *AgentUsage, exitCode *int, errorInfo *ErrorInfo) *AgentResult {
	return &AgentResult{
		RunID:     b.runID,
		SessionID: b.sessionID,
		Status:    status,
		FinalText: finalText,
		Usage:     usage,
		ExitCode:  exitCode,
		Error:     errorInfo,
	}
}
