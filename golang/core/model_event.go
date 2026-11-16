// 模型流式事件结构与按序号递增的事件组装器
// 创建日期：2026-11-16
// 修改日期：2026-11-16

package core

import (
	"encoding/json"
)

// ModelEventType 模型流式事件类型。
type ModelEventType string

const (
	ModelEventStarted       ModelEventType = "started"
	ModelEventTextDelta     ModelEventType = "text_delta"
	ModelEventToolCallDelta ModelEventType = "tool_call_delta"
	ModelEventUsage         ModelEventType = "usage"
	ModelEventCompleted     ModelEventType = "completed"
	ModelEventError         ModelEventType = "error"
)

// ModelEvent 模型流式事件，按 Type 决定哪些字段有效。
type ModelEvent struct {
	Type           ModelEventType
	TraceID        string
	Sequence       int
	Provider       string
	Model          *string
	ID             *string
	Text           string
	Index          int
	Name           *string
	ArgumentsDelta string
	Usage          *Usage
	Response       *ModelResponse
	Error          *ErrorInfo
}

// IsTerminal 判断事件是否为终止事件。
func (e ModelEvent) IsTerminal() bool {
	return e.Type == ModelEventCompleted || e.Type == ModelEventError
}

// MarshalJSON 按事件类型输出契约字段。
func (e ModelEvent) MarshalJSON() ([]byte, error) {
	fields := map[string]any{
		"type":     e.Type,
		"traceId":  e.TraceID,
		"sequence": e.Sequence,
		"provider": e.Provider,
	}

	switch e.Type {
	case ModelEventStarted:
		fields["model"] = e.Model
		fields["id"] = e.ID
	case ModelEventTextDelta:
		fields["text"] = e.Text
	case ModelEventToolCallDelta:
		fields["index"] = e.Index
		fields["id"] = e.ID
		fields["name"] = e.Name
		fields["argumentsDelta"] = e.ArgumentsDelta
	case ModelEventUsage:
		fields["usage"] = e.Usage
	case ModelEventCompleted:
		fields["response"] = e.Response
	case ModelEventError:
		fields["error"] = e.Error
	}

	return json.Marshal(fields)
}

// ModelEventBuilder 为同一个流组装事件，统一填写 traceId、provider 与递增序号。
type ModelEventBuilder struct {
	provider     string
	traceID      string
	nextSequence int
}

// NewModelEventBuilder 创建事件组装器，序号从 0 开始。
func NewModelEventBuilder(provider string, traceID string) *ModelEventBuilder {
	return &ModelEventBuilder{
		provider: provider,
		traceID:  traceID,
	}
}

// TraceID 返回本流的 traceId。
func (b *ModelEventBuilder) TraceID() string {
	return b.traceID
}

// newEvent 创建带公共字段的事件并递增序号
func (b *ModelEventBuilder) newEvent(eventType ModelEventType) ModelEvent {
	event := ModelEvent{
		Type:     eventType,
		TraceID:  b.traceID,
		Sequence: b.nextSequence,
		Provider: b.provider,
	}
	b.nextSequence++

	return event
}

// Started 组装 started 事件。
func (b *ModelEventBuilder) Started(model *string, responseID *string) ModelEvent {
	event := b.newEvent(ModelEventStarted)
	event.Model = model
	event.ID = responseID

	return event
}

// TextDelta 组装 text_delta 事件。
func (b *ModelEventBuilder) TextDelta(text string) ModelEvent {
	event := b.newEvent(ModelEventTextDelta)
	event.Text = text

	return event
}

// ToolCallDelta 组装 tool_call_delta 事件，id 与 name 只在首个增量中出现。
func (b *ModelEventBuilder) ToolCallDelta(index int, callID *string, name *string, argumentsDelta string) ModelEvent {
	event := b.newEvent(ModelEventToolCallDelta)
	event.Index = index
	event.ID = callID
	event.Name = name
	event.ArgumentsDelta = argumentsDelta

	return event
}

// UsageEvent 组装 usage 事件。
func (b *ModelEventBuilder) UsageEvent(usage Usage) ModelEvent {
	event := b.newEvent(ModelEventUsage)
	event.Usage = &usage

	return event
}

// Completed 组装 completed 终止事件。
func (b *ModelEventBuilder) Completed(response *ModelResponse) ModelEvent {
	event := b.newEvent(ModelEventCompleted)
	event.Response = response

	return event
}

// ErrorEvent 组装 error 终止事件。
func (b *ModelEventBuilder) ErrorEvent(err *Error) ModelEvent {
	event := b.newEvent(ModelEventError)
	info := err.Info
	event.Error = &info

	return event
}

// ShouldEmitToolDelta 判断工具调用增量是否需要发出，id、name 都为空且参数为空时跳过。
func ShouldEmitToolDelta(callID *string, name *string, argumentsDelta string) bool {
	return callID != nil || name != nil || argumentsDelta != ""
}
