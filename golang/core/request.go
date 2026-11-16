// 模型与 Agent 请求的公共校验与 providerOptions 合并
// 创建日期：2026-11-16
// 修改日期：2026-11-16

package core

import (
	"fmt"
	"sort"
	"strings"
)

// ValidateModelRequest 校验模型请求的必填字段与片段组合是否合法。
func ValidateModelRequest(request *ModelRequest, provider string) error {
	if request == nil {
		return NewError(CategoryConfigInvalid, "ModelRequest 不能为空", provider)
	}

	if len(request.Messages) == 0 {
		return NewError(CategoryConfigInvalid, "messages 至少需要 1 条", provider)
	}

	for messageIndex, message := range request.Messages {
		if err := validateMessage(message, messageIndex, provider); err != nil {
			return err
		}
	}

	for _, tool := range request.Tools {
		if strings.TrimSpace(tool.Name) == "" {
			return NewError(CategoryConfigInvalid, "tools 中存在空的 name", provider)
		}
	}

	if request.ToolChoice != nil {
		if err := validateToolChoice(request.ToolChoice, provider); err != nil {
			return err
		}
	}

	if request.ResponseFormat != nil {
		if err := validateResponseFormat(request.ResponseFormat, provider); err != nil {
			return err
		}
	}

	if request.TimeoutSeconds != nil && *request.TimeoutSeconds <= 0 {
		return NewError(CategoryConfigInvalid, "timeoutSeconds 必须大于 0", provider)
	}

	return nil
}

// validateMessage 校验单条消息的角色与片段
func validateMessage(message Message, messageIndex int, provider string) error {
	switch message.Role {
	case RoleSystem, RoleUser, RoleAssistant, RoleTool:
	default:
		return NewError(CategoryConfigInvalid, fmt.Sprintf("messages[%d].role 取值非法：%s", messageIndex, message.Role), provider)
	}

	if len(message.Content) == 0 {
		return NewError(CategoryConfigInvalid, fmt.Sprintf("messages[%d].content 至少需要 1 个片段", messageIndex), provider)
	}

	for _, part := range message.Content {
		if err := validateContentPart(message.Role, part, messageIndex, provider); err != nil {
			return err
		}
	}

	return nil
}

// validateContentPart 校验片段类型与所在消息角色是否匹配
func validateContentPart(role MessageRole, part ContentPart, messageIndex int, provider string) error {
	switch part.Type {
	case PartText:
		return nil
	case PartImage:
		hasURL := part.URL != ""
		hasBase64 := part.Base64 != ""

		if hasURL == hasBase64 {
			return NewError(CategoryConfigInvalid, fmt.Sprintf("messages[%d] 的 image 片段必须且只能提供 url 或 base64 之一", messageIndex), provider)
		}

		if hasBase64 && part.MimeType == "" {
			return NewError(CategoryConfigInvalid, fmt.Sprintf("messages[%d] 的 base64 图片缺少 mimeType", messageIndex), provider)
		}

		return nil
	case PartToolCall:
		if role != RoleAssistant {
			return NewError(CategoryConfigInvalid, fmt.Sprintf("messages[%d] 的 tool_call 片段只能出现在 assistant 消息中", messageIndex), provider)
		}

		return nil
	case PartToolResult:
		if role != RoleTool {
			return NewError(CategoryConfigInvalid, fmt.Sprintf("messages[%d] 的 tool_result 片段只能出现在 tool 消息中", messageIndex), provider)
		}

		return nil
	default:
		return NewError(CategoryConfigInvalid, fmt.Sprintf("messages[%d] 存在未知片段类型：%s", messageIndex, part.Type), provider)
	}
}

// validateToolChoice 校验工具选择策略
func validateToolChoice(choice *ToolChoice, provider string) error {
	switch choice.Type {
	case ToolChoiceAuto, ToolChoiceNone, ToolChoiceRequired:
		return nil
	case ToolChoiceTool:
		if choice.Name == "" {
			return NewError(CategoryConfigInvalid, "toolChoice.type 为 tool 时 name 必填", provider)
		}

		return nil
	default:
		return NewError(CategoryConfigInvalid, "toolChoice.type 取值非法："+string(choice.Type), provider)
	}
}

// validateResponseFormat 校验输出格式
func validateResponseFormat(format *ResponseFormat, provider string) error {
	switch format.Type {
	case ResponseFormatText:
		return nil
	case ResponseFormatJSONSchema:
		if format.Name == "" || format.Schema == nil {
			return NewError(CategoryConfigInvalid, "responseFormat.type 为 json_schema 时 name 与 schema 必填", provider)
		}

		return nil
	default:
		return NewError(CategoryConfigInvalid, "responseFormat.type 取值非法："+string(format.Type), provider)
	}
}

// MergeProviderOptions 合并配置级 native_options 与请求级 providerOptions，请求级优先。
func MergeProviderOptions(configOptions map[string]any, requestOptions map[string]any) map[string]any {
	merged := map[string]any{}

	for key, value := range configOptions {
		merged[key] = value
	}

	for key, value := range requestOptions {
		merged[key] = value
	}

	return merged
}

// FindConflictingKeys 返回与已映射字段重名的键，按字母序排列。
func FindConflictingKeys(options map[string]any, mappedKeys []string) []string {
	conflicts := []string{}

	for _, mappedKey := range mappedKeys {
		if _, exists := options[mappedKey]; exists {
			conflicts = append(conflicts, mappedKey)
		}
	}

	sort.Strings(conflicts)
	return conflicts
}

// NewOptionConflictError 组装 providerOptions 键冲突错误。
func NewOptionConflictError(conflicts []string, provider string) *Error {
	return NewError(CategoryConfigInvalid, "providerOptions 与已映射字段重名："+strings.Join(conflicts, ", "), provider)
}

// FirstNonEmpty 返回第一个非空字符串。
func FirstNonEmpty(values ...string) string {
	for _, value := range values {
		if value != "" {
			return value
		}
	}

	return ""
}
