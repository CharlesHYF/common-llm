// 把统一模型请求映射为 Anthropic Messages API 参数
// 创建日期：2026-11-16
// 修改日期：2026-11-16

package anthropic

import (
	"encoding/json"
	"strings"

	sdk "github.com/anthropics/anthropic-sdk-go/v2"

	"github.com/CharlesHYF/common-llm/golang/core"
)

// buildParams system 消息映射为 system，tool 消息映射为 user 消息中的 tool_result 块
func buildParams(request *core.ModelRequest, model string, maxTokens int64) (sdk.MessageNewParams, error) {
	params := sdk.MessageNewParams{
		Model:     model,
		MaxTokens: maxTokens,
	}

	systemTexts := []string{}

	for _, message := range request.Messages {
		switch message.Role {
		case core.RoleSystem:
			systemTexts = append(systemTexts, joinTextParts(message.Content))
		case core.RoleUser:
			params.Messages = append(params.Messages, sdk.MessageParam{
				OfUser: &sdk.UserMessageParam{Content: buildUserBlocks(message.Content)},
			})
		case core.RoleAssistant:
			blocks, err := buildAssistantBlocks(message.Content)
			if err != nil {
				return params, err
			}

			params.Messages = append(params.Messages, sdk.MessageParam{
				OfAssistant: &sdk.AssistantMessageParam{Content: blocks},
			})
		case core.RoleTool:
			params.Messages = append(params.Messages, sdk.MessageParam{
				OfUser: &sdk.UserMessageParam{Content: buildToolResultBlocks(message.Content)},
			})
		}
	}

	if len(systemTexts) > 0 {
		params.System = []sdk.TextBlockParam{{Text: strings.Join(systemTexts, "\n")}}
	}

	for _, tool := range request.Tools {
		params.Tools = append(params.Tools, sdk.ToolUnionParam{OfTool: buildTool(tool)})
	}

	if request.ToolChoice != nil {
		params.ToolChoice = buildToolChoice(request.ToolChoice)
	}

	if request.ResponseFormat != nil && request.ResponseFormat.Type == core.ResponseFormatJSONSchema {
		params.OutputConfig = sdk.OutputConfigParam{
			Format: sdk.JSONOutputFormatParam{Schema: request.ResponseFormat.Schema},
		}
	}

	return params, nil
}

// buildUserBlocks 映射文本与图片块
func buildUserBlocks(parts []core.ContentPart) []sdk.ContentBlockParamUnion {
	blocks := []sdk.ContentBlockParamUnion{}

	for _, part := range parts {
		if part.Type == core.PartText {
			blocks = append(blocks, sdk.ContentBlockParamUnion{OfText: &sdk.TextBlockParam{Text: part.Text}})
			continue
		}

		if part.Type != core.PartImage {
			continue
		}

		source := sdk.ImageBlockParamSourceUnion{}
		if part.URL != "" {
			source.OfURL = &sdk.URLImageSourceParam{URL: part.URL}
		} else {
			source.OfBase64 = &sdk.Base64ImageSourceParam{
				Data:      part.Base64,
				MediaType: sdk.Base64ImageSourceMediaType(part.MimeType),
			}
		}

		blocks = append(blocks, sdk.ContentBlockParamUnion{OfImage: &sdk.ImageBlockParam{Source: source}})
	}

	return blocks
}

// buildAssistantBlocks 映射 assistant 文本与历史工具调用，工具参数解析为对象
func buildAssistantBlocks(parts []core.ContentPart) ([]sdk.ContentBlockParamUnion, error) {
	blocks := []sdk.ContentBlockParamUnion{}

	for _, part := range parts {
		if part.Type == core.PartText {
			blocks = append(blocks, sdk.ContentBlockParamUnion{OfText: &sdk.TextBlockParam{Text: part.Text}})
			continue
		}

		if part.Type != core.PartToolCall {
			continue
		}

		var input any = map[string]any{}
		if strings.TrimSpace(part.Arguments) != "" {
			if err := json.Unmarshal([]byte(part.Arguments), &input); err != nil {
				return nil, core.WrapError(core.CategoryConfigInvalid, "tool_call.arguments 不是合法 JSON："+part.ID, "", err)
			}
		}

		blocks = append(blocks, sdk.ContentBlockParamUnion{
			OfToolUse: &sdk.ToolUseBlockParam{
				ID:    part.ID,
				Name:  part.Name,
				Input: input,
			},
		})
	}

	return blocks, nil
}

// buildToolResultBlocks 映射工具结果块
func buildToolResultBlocks(parts []core.ContentPart) []sdk.ContentBlockParamUnion {
	blocks := []sdk.ContentBlockParamUnion{}

	for _, part := range parts {
		if part.Type != core.PartToolResult {
			continue
		}

		toolResult := sdk.ToolResultBlockParam{
			ToolUseID: part.ToolCallID,
			Content: []sdk.ToolResultBlockParamContentUnion{
				{OfText: &sdk.TextBlockParam{Text: part.Content}},
			},
		}

		if part.IsError {
			toolResult.IsError = sdk.Bool(true)
		}

		blocks = append(blocks, sdk.ContentBlockParamUnion{OfToolResult: &toolResult})
	}

	return blocks
}

// buildTool 把 JSON Schema 拆为 properties、required 与其余字段
func buildTool(tool core.ToolDefinition) *sdk.ToolParam {
	schema := sdk.ToolInputSchemaParam{
		Properties:  tool.Parameters["properties"],
		ExtraFields: map[string]any{},
	}

	for key, value := range tool.Parameters {
		switch key {
		case "type", "properties":
			continue
		case "required":
			schema.Required = toStrings(value)
		default:
			schema.ExtraFields[key] = value
		}
	}

	toolParam := &sdk.ToolParam{
		Name:        tool.Name,
		InputSchema: schema,
	}

	if tool.Description != "" {
		toolParam.Description = sdk.String(tool.Description)
	}

	return toolParam
}

// buildToolChoice auto、none 直接映射，required 映射为 any，tool 映射为指定工具
func buildToolChoice(choice *core.ToolChoice) sdk.ToolChoiceUnionParam {
	switch choice.Type {
	case core.ToolChoiceNone:
		return sdk.ToolChoiceUnionParam{OfNone: &sdk.ToolChoiceNoneParam{}}
	case core.ToolChoiceRequired:
		return sdk.ToolChoiceUnionParam{OfAny: &sdk.ToolChoiceAnyParam{}}
	case core.ToolChoiceTool:
		return sdk.ToolChoiceUnionParam{OfTool: &sdk.ToolChoiceToolParam{Name: choice.Name}}
	default:
		return sdk.ToolChoiceUnionParam{OfAuto: &sdk.ToolChoiceAutoParam{}}
	}
}

// toStrings 把 JSON 数组转换为字符串切片，跳过非字符串元素
func toStrings(value any) []string {
	switch typed := value.(type) {
	case []string:
		return typed
	case []any:
		result := []string{}

		for _, element := range typed {
			text, isString := element.(string)
			if isString {
				result = append(result, text)
			}
		}

		return result
	default:
		return nil
	}
}

// joinTextParts 按顺序拼接消息中的文本片段
func joinTextParts(parts []core.ContentPart) string {
	var builder strings.Builder

	for _, part := range parts {
		if part.Type == core.PartText {
			builder.WriteString(part.Text)
		}
	}

	return builder.String()
}
