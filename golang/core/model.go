// 模型调用的请求、响应、内容片段与用量结构
// 创建日期：2026-11-16
// 修改日期：2026-11-16

package core

import (
	"encoding/json"
)

// MessageRole 消息角色。
type MessageRole string

const (
	RoleSystem    MessageRole = "system"
	RoleUser      MessageRole = "user"
	RoleAssistant MessageRole = "assistant"
	RoleTool      MessageRole = "tool"
)

// ContentPartType 内容片段类型。
type ContentPartType string

const (
	PartText       ContentPartType = "text"
	PartImage      ContentPartType = "image"
	PartToolCall   ContentPartType = "tool_call"
	PartToolResult ContentPartType = "tool_result"
)

// ToolChoiceType 工具选择方式。
type ToolChoiceType string

const (
	ToolChoiceAuto     ToolChoiceType = "auto"
	ToolChoiceNone     ToolChoiceType = "none"
	ToolChoiceRequired ToolChoiceType = "required"
	ToolChoiceTool     ToolChoiceType = "tool"
)

// ResponseFormatType 输出格式类型。
type ResponseFormatType string

const (
	ResponseFormatText       ResponseFormatType = "text"
	ResponseFormatJSONSchema ResponseFormatType = "json_schema"
)

// FinishReason 统一的结束原因。
type FinishReason string

const (
	FinishStop          FinishReason = "stop"
	FinishLength        FinishReason = "length"
	FinishToolCalls     FinishReason = "tool_calls"
	FinishContentFilter FinishReason = "content_filter"
	FinishOther         FinishReason = "other"
)

// ModelRequest 统一的模型调用请求。
type ModelRequest struct {
	Provider           string            `json:"provider"`
	Model              string            `json:"model,omitempty"`
	Messages           []Message         `json:"messages"`
	Temperature        *float64          `json:"temperature,omitempty"`
	MaxOutputTokens    *int64            `json:"maxOutputTokens,omitempty"`
	TimeoutSeconds     *float64          `json:"timeoutSeconds,omitempty"`
	Tools              []ToolDefinition  `json:"tools,omitempty"`
	ToolChoice         *ToolChoice       `json:"toolChoice,omitempty"`
	ResponseFormat     *ResponseFormat   `json:"responseFormat,omitempty"`
	ProviderOptions    map[string]any    `json:"providerOptions,omitempty"`
	IncludeRawResponse bool              `json:"includeRawResponse,omitempty"`
	TraceID            string            `json:"traceId,omitempty"`
	RequestID          string            `json:"requestId,omitempty"`
	Metadata           map[string]string `json:"metadata,omitempty"`
}

// Message 一条对话消息。
type Message struct {
	Role    MessageRole   `json:"role"`
	Content []ContentPart `json:"content"`
}

// ContentPart 内容片段，按 Type 决定哪些字段有效。
type ContentPart struct {
	Type       ContentPartType
	Text       string
	URL        string
	Base64     string
	MimeType   string
	ID         string
	Name       string
	Arguments  string
	ToolCallID string
	Content    string
	IsError    bool
}

// contentPartJSON 内容片段的序列化形态，可选字段为空时不输出
type contentPartJSON struct {
	Type       ContentPartType `json:"type"`
	Text       *string         `json:"text,omitempty"`
	URL        *string         `json:"url,omitempty"`
	Base64     *string         `json:"base64,omitempty"`
	MimeType   *string         `json:"mimeType,omitempty"`
	ID         *string         `json:"id,omitempty"`
	Name       *string         `json:"name,omitempty"`
	Arguments  *string         `json:"arguments,omitempty"`
	ToolCallID *string         `json:"toolCallId,omitempty"`
	Content    *string         `json:"content,omitempty"`
	IsError    *bool           `json:"isError,omitempty"`
}

// MarshalJSON 按片段类型输出契约字段。
func (p ContentPart) MarshalJSON() ([]byte, error) {
	encoded := contentPartJSON{Type: p.Type}

	switch p.Type {
	case PartText:
		encoded.Text = StringPtr(p.Text)
	case PartImage:
		encoded.URL = NonEmptyStringPtr(p.URL)
		encoded.Base64 = NonEmptyStringPtr(p.Base64)
		encoded.MimeType = NonEmptyStringPtr(p.MimeType)
	case PartToolCall:
		encoded.ID = StringPtr(p.ID)
		encoded.Name = StringPtr(p.Name)
		encoded.Arguments = StringPtr(p.Arguments)
	case PartToolResult:
		encoded.ToolCallID = StringPtr(p.ToolCallID)
		encoded.Content = StringPtr(p.Content)
		encoded.IsError = BoolPtr(p.IsError)
	}

	return json.Marshal(encoded)
}

// UnmarshalJSON 解析契约 JSON 形态的内容片段。
func (p *ContentPart) UnmarshalJSON(raw []byte) error {
	var decoded contentPartJSON
	if err := json.Unmarshal(raw, &decoded); err != nil {
		return err
	}

	*p = ContentPart{Type: decoded.Type}
	p.Text = derefString(decoded.Text)
	p.URL = derefString(decoded.URL)
	p.Base64 = derefString(decoded.Base64)
	p.MimeType = derefString(decoded.MimeType)
	p.ID = derefString(decoded.ID)
	p.Name = derefString(decoded.Name)
	p.Arguments = derefString(decoded.Arguments)
	p.ToolCallID = derefString(decoded.ToolCallID)
	p.Content = derefString(decoded.Content)

	if decoded.IsError != nil {
		p.IsError = *decoded.IsError
	}

	return nil
}

// derefString 读取可空字符串，nil 返回空字符串
func derefString(value *string) string {
	if value == nil {
		return ""
	}

	return *value
}

// TextPart 创建文本片段。
func TextPart(text string) ContentPart {
	return ContentPart{
		Type: PartText,
		Text: text,
	}
}

// ImageURLPart 创建 URL 形式的图片片段。
func ImageURLPart(imageURL string) ContentPart {
	return ContentPart{
		Type: PartImage,
		URL:  imageURL,
	}
}

// ImageBase64Part 创建 base64 形式的图片片段。
func ImageBase64Part(base64Data string, mimeType string) ContentPart {
	return ContentPart{
		Type:     PartImage,
		Base64:   base64Data,
		MimeType: mimeType,
	}
}

// ToolCallPart 创建 assistant 消息中的工具调用片段，arguments 为 JSON 字符串。
func ToolCallPart(callID string, name string, arguments string) ContentPart {
	return ContentPart{
		Type:      PartToolCall,
		ID:        callID,
		Name:      name,
		Arguments: arguments,
	}
}

// ToolResultPart 创建 tool 消息中的工具结果片段。
func ToolResultPart(toolCallID string, content string, isError bool) ContentPart {
	return ContentPart{
		Type:       PartToolResult,
		ToolCallID: toolCallID,
		Content:    content,
		IsError:    isError,
	}
}

// TextMessage 创建只含一段文本的消息。
func TextMessage(role MessageRole, text string) Message {
	return Message{
		Role:    role,
		Content: []ContentPart{TextPart(text)},
	}
}

// ToolDefinition 可供模型调用的函数工具定义，Parameters 为 JSON Schema 对象。
type ToolDefinition struct {
	Name        string         `json:"name"`
	Description string         `json:"description,omitempty"`
	Parameters  map[string]any `json:"parameters"`
}

// ToolChoice 工具选择策略，Type 为 tool 时 Name 必填。
type ToolChoice struct {
	Type ToolChoiceType `json:"type"`
	Name string         `json:"name,omitempty"`
}

// ResponseFormat 输出格式，Type 为 json_schema 时 Name 与 Schema 必填。
type ResponseFormat struct {
	Type   ResponseFormatType `json:"type"`
	Name   string             `json:"name,omitempty"`
	Schema map[string]any     `json:"schema,omitempty"`
	Strict *bool              `json:"strict,omitempty"`
}

// ToolCall 模型返回的一次工具调用，Arguments 为 JSON 字符串。
type ToolCall struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	Arguments string `json:"arguments"`
}

// Usage 模型用量，取不到的统计值为 nil。
type Usage struct {
	InputTokens       *int64 `json:"inputTokens"`
	OutputTokens      *int64 `json:"outputTokens"`
	CachedInputTokens *int64 `json:"cachedInputTokens"`
	ReasoningTokens   *int64 `json:"reasoningTokens"`
	TotalTokens       *int64 `json:"totalTokens"`
}

// ModelResponse 统一的模型调用响应。
type ModelResponse struct {
	Provider           string          `json:"provider"`
	Model              *string         `json:"model"`
	ID                 *string         `json:"id"`
	ContentParts       []ContentPart   `json:"contentParts"`
	Text               string          `json:"text"`
	ToolCalls          []ToolCall      `json:"toolCalls"`
	FinishReason       FinishReason    `json:"finishReason"`
	VendorFinishReason *string         `json:"vendorFinishReason"`
	Usage              Usage           `json:"usage"`
	TraceID            string          `json:"traceId"`
	RequestID          *string         `json:"requestId"`
	RawResponse        json.RawMessage `json:"rawResponse,omitempty"`
}

// BuildModelResponse 按内容片段汇总 text 与 toolCalls，组装完整响应。
func BuildModelResponse(provider string, model *string, responseID *string, contentParts []ContentPart, finishReason FinishReason, vendorFinishReason *string, usage Usage, traceID string, requestID *string) *ModelResponse {
	text := ""
	toolCalls := []ToolCall{}
	parts := contentParts

	if parts == nil {
		parts = []ContentPart{}
	}

	for _, part := range parts {
		if part.Type == PartText {
			text += part.Text
			continue
		}

		if part.Type == PartToolCall {
			toolCalls = append(toolCalls, ToolCall{
				ID:        part.ID,
				Name:      part.Name,
				Arguments: part.Arguments,
			})
		}
	}

	return &ModelResponse{
		Provider:           provider,
		Model:              model,
		ID:                 responseID,
		ContentParts:       parts,
		Text:               text,
		ToolCalls:          toolCalls,
		FinishReason:       finishReason,
		VendorFinishReason: vendorFinishReason,
		Usage:              usage,
		TraceID:            traceID,
		RequestID:          requestID,
	}
}

// SumCounts 两个可空计数求和，任一为 nil 时返回 nil。
func SumCounts(first *int64, second *int64) *int64 {
	if first == nil || second == nil {
		return nil
	}

	return Int64Ptr(*first + *second)
}
