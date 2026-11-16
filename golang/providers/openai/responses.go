// OpenAI Responses API 的请求组装、响应映射与流式事件映射
// 创建日期：2026-11-16
// 修改日期：2026-11-16

package openai

import (
	"context"
	"encoding/json"
	"strings"

	sdk "github.com/openai/openai-go/v3"
	"github.com/openai/openai-go/v3/responses"

	"github.com/CharlesHYF/common-llm/golang/core"
)

// responsesMappedKeys Responses API 中由适配器映射的请求体字段，providerOptions 不得重名
var responsesMappedKeys = []string{
	"model",
	"input",
	"instructions",
	"temperature",
	"max_output_tokens",
	"tools",
	"tool_choice",
	"text",
	"stream",
}

// buildResponsesParams 把统一请求映射为 Responses API 参数，system 消息映射为 instructions
func buildResponsesParams(request *core.ModelRequest, model string) responses.ResponseNewParams {
	params := responses.ResponseNewParams{
		Model: model,
	}

	instructions := []string{}
	inputItems := responses.ResponseInputParam{}

	for _, message := range request.Messages {
		switch message.Role {
		case core.RoleSystem:
			instructions = append(instructions, joinTextParts(message.Content))
		case core.RoleUser:
			inputItems = append(inputItems, buildResponsesUserItem(message))
		case core.RoleAssistant:
			inputItems = append(inputItems, buildResponsesAssistantItems(message)...)
		case core.RoleTool:
			inputItems = append(inputItems, buildResponsesToolItems(message)...)
		}
	}

	if len(instructions) > 0 {
		params.Instructions = sdk.String(strings.Join(instructions, "\n"))
	}

	params.Input = responses.ResponseNewParamsInputUnion{OfInputItemList: inputItems}

	if request.Temperature != nil {
		params.Temperature = sdk.Float(*request.Temperature)
	}

	if request.MaxOutputTokens != nil {
		params.MaxOutputTokens = sdk.Int(*request.MaxOutputTokens)
	}

	for _, tool := range request.Tools {
		functionTool := responses.FunctionToolParam{
			Name:       tool.Name,
			Parameters: tool.Parameters,
			Strict:     sdk.Bool(false),
		}

		if tool.Description != "" {
			functionTool.Description = sdk.String(tool.Description)
		}

		params.Tools = append(params.Tools, responses.ToolUnionParam{OfFunction: &functionTool})
	}

	if request.ToolChoice != nil {
		params.ToolChoice = buildResponsesToolChoice(request.ToolChoice)
	}

	if request.ResponseFormat != nil {
		params.Text = buildResponsesTextConfig(request.ResponseFormat)
	}

	return params
}

// buildResponsesUserItem 组装 user 消息，文本与图片保持原顺序
func buildResponsesUserItem(message core.Message) responses.ResponseInputItemUnionParam {
	contents := responses.ResponseInputMessageContentListParam{}

	for _, part := range message.Content {
		if part.Type == core.PartText {
			contents = append(contents, responses.ResponseInputContentUnionParam{
				OfInputText: &responses.ResponseInputTextParam{Text: part.Text},
			})
			continue
		}

		if part.Type == core.PartImage {
			contents = append(contents, responses.ResponseInputContentUnionParam{
				OfInputImage: &responses.ResponseInputImageParam{
					ImageURL: sdk.String(buildImageURL(part)),
					Detail:   responses.ResponseInputImageDetailAuto,
				},
			})
		}
	}

	return responses.ResponseInputItemUnionParam{
		OfMessage: &responses.EasyInputMessageParam{
			Role:    responses.EasyInputMessageRoleUser,
			Content: responses.EasyInputMessageContentUnionParam{OfInputItemContentList: contents},
		},
	}
}

// buildResponsesAssistantItems 组装 assistant 文本与历史工具调用
func buildResponsesAssistantItems(message core.Message) []responses.ResponseInputItemUnionParam {
	items := []responses.ResponseInputItemUnionParam{}
	text := joinTextParts(message.Content)

	if text != "" {
		items = append(items, responses.ResponseInputItemUnionParam{
			OfMessage: &responses.EasyInputMessageParam{
				Role:    responses.EasyInputMessageRoleAssistant,
				Content: responses.EasyInputMessageContentUnionParam{OfString: sdk.String(text)},
			},
		})
	}

	for _, part := range message.Content {
		if part.Type != core.PartToolCall {
			continue
		}

		items = append(items, responses.ResponseInputItemUnionParam{
			OfFunctionCall: &responses.ResponseFunctionToolCallParam{
				CallID:    part.ID,
				Name:      part.Name,
				Arguments: part.Arguments,
			},
		})
	}

	return items
}

// buildResponsesToolItems 组装工具结果
func buildResponsesToolItems(message core.Message) []responses.ResponseInputItemUnionParam {
	items := []responses.ResponseInputItemUnionParam{}

	for _, part := range message.Content {
		if part.Type != core.PartToolResult {
			continue
		}

		items = append(items, responses.ResponseInputItemUnionParam{
			OfFunctionCallOutput: &responses.ResponseInputItemFunctionCallOutputParam{
				CallID: sdk.String(part.ToolCallID),
				Output: responses.ResponseInputItemFunctionCallOutputOutputUnionParam{OfString: sdk.String(part.Content)},
			},
		})
	}

	return items
}

// buildResponsesToolChoice 映射工具选择策略
func buildResponsesToolChoice(choice *core.ToolChoice) responses.ResponseNewParamsToolChoiceUnion {
	switch choice.Type {
	case core.ToolChoiceNone:
		return responses.ResponseNewParamsToolChoiceUnion{OfToolChoiceMode: sdk.Opt(responses.ToolChoiceOptionsNone)}
	case core.ToolChoiceRequired:
		return responses.ResponseNewParamsToolChoiceUnion{OfToolChoiceMode: sdk.Opt(responses.ToolChoiceOptionsRequired)}
	case core.ToolChoiceTool:
		return responses.ResponseNewParamsToolChoiceUnion{OfFunctionTool: &responses.ToolChoiceFunctionParam{Name: choice.Name}}
	default:
		return responses.ResponseNewParamsToolChoiceUnion{OfToolChoiceMode: sdk.Opt(responses.ToolChoiceOptionsAuto)}
	}
}

// buildResponsesTextConfig 映射输出格式，json_schema 映射为 text.format
func buildResponsesTextConfig(format *core.ResponseFormat) responses.ResponseTextConfigParam {
	if format.Type != core.ResponseFormatJSONSchema {
		return responses.ResponseTextConfigParam{
			Format: responses.ResponseFormatTextConfigUnionParam{OfText: &sdk.ResponseFormatTextParam{}},
		}
	}

	schemaConfig := responses.ResponseFormatTextJSONSchemaConfigParam{
		Name:   format.Name,
		Schema: format.Schema,
	}

	if format.Strict != nil {
		schemaConfig.Strict = sdk.Bool(*format.Strict)
	}

	return responses.ResponseTextConfigParam{
		Format: responses.ResponseFormatTextConfigUnionParam{OfJSONSchema: &schemaConfig},
	}
}

// generateResponses 调用 responses.New 并映射响应
func (c *Client) generateResponses(ctx context.Context, request *core.ModelRequest, prepared *preparedRequest) (*core.ModelResponse, error) {
	params := buildResponsesParams(request, prepared.model)
	response, err := c.native.Responses.New(ctx, params, prepared.callOptions...)

	if err != nil {
		return nil, c.mapError(ctx, err)
	}

	mapped := mapResponsesResponse(c.config.Name, response, prepared.traceID, prepared.requestID())
	if request.IncludeRawResponse {
		mapped.RawResponse = json.RawMessage(response.RawJSON())
	}

	return mapped, nil
}

// streamResponses 调用 responses.NewStreaming 并逐个映射事件
func (c *Client) streamResponses(ctx context.Context, request *core.ModelRequest, prepared *preparedRequest, builder *core.ModelEventBuilder, yield func(core.ModelEvent) bool) {
	params := buildResponsesParams(request, prepared.model)
	stream := c.native.Responses.NewStreaming(ctx, params, prepared.callOptions...)
	defer stream.Close()

	toolIndexes := map[string]int{}

	for stream.Next() {
		event := stream.Current()

		switch event.Type {
		case "response.created":
			if !yield(builder.Started(core.NonEmptyStringPtr(event.Response.Model), core.NonEmptyStringPtr(event.Response.ID))) {
				return
			}
		case "response.output_text.delta":
			if event.Delta == "" {
				continue
			}

			if !yield(builder.TextDelta(event.Delta)) {
				return
			}
		case "response.output_item.added":
			if event.Item.Type != "function_call" {
				continue
			}

			toolIndex := len(toolIndexes)
			toolIndexes[event.Item.ID] = toolIndex

			if !yield(builder.ToolCallDelta(toolIndex, core.StringPtr(event.Item.CallID), core.StringPtr(event.Item.Name), event.Item.Arguments.OfString)) {
				return
			}
		case "response.function_call_arguments.delta":
			toolIndex, exists := toolIndexes[event.ItemID]
			if !exists || event.Delta == "" {
				continue
			}

			if !yield(builder.ToolCallDelta(toolIndex, nil, nil, event.Delta)) {
				return
			}
		case "response.completed", "response.incomplete":
			response := mapResponsesResponse(c.config.Name, &event.Response, builder.TraceID(), prepared.requestID())
			if request.IncludeRawResponse {
				response.RawResponse = json.RawMessage(event.Response.RawJSON())
			}

			if event.Response.JSON.Usage.Valid() && !yield(builder.UsageEvent(response.Usage)) {
				return
			}

			yield(builder.Completed(response))
			return
		case "response.failed", "error":
			streamErr := core.NewError(core.CategoryProviderError, core.FirstNonEmpty(event.Message, event.Response.Error.Message, "OpenAI 流式响应失败"), c.config.Name).WithVendorCode(core.FirstNonEmpty(event.Code, string(event.Response.Error.Code)))
			yield(builder.ErrorEvent(streamErr))
			return
		}
	}

	streamErr := core.NewError(core.CategoryProviderError, "流在收到完成事件前结束", c.config.Name)
	if err := stream.Err(); err != nil {
		streamErr = c.mapError(ctx, err)
	}

	yield(builder.ErrorEvent(streamErr))
}

// mapResponsesResponse 把 Response 映射为统一响应
func mapResponsesResponse(provider string, response *responses.Response, traceID string, requestID *string) *core.ModelResponse {
	contentParts := []core.ContentPart{}
	hasToolCall := false

	for _, item := range response.Output {
		if item.Type == "message" {
			for _, content := range item.Content {
				if content.Type == "output_text" {
					contentParts = append(contentParts, core.TextPart(content.Text))
				}
			}

			continue
		}

		if item.Type == "function_call" {
			hasToolCall = true
			contentParts = append(contentParts, core.ToolCallPart(item.CallID, item.Name, item.Arguments.OfString))
		}
	}

	status := string(response.Status)
	vendorFinishReason := core.NonEmptyStringPtr(status)
	finishReason := core.FinishOther

	switch {
	case hasToolCall:
		finishReason = core.FinishToolCalls
	case status == "completed":
		finishReason = core.FinishStop
	case status == "incomplete":
		reason := response.IncompleteDetails.Reason
		vendorFinishReason = core.NonEmptyStringPtr(reason)

		if reason == "max_output_tokens" {
			finishReason = core.FinishLength
		} else if reason == "content_filter" {
			finishReason = core.FinishContentFilter
		}
	}

	return core.BuildModelResponse(provider, core.NonEmptyStringPtr(response.Model), core.NonEmptyStringPtr(response.ID), contentParts, finishReason, vendorFinishReason, mapResponsesUsage(response), traceID, requestID)
}

// mapResponsesUsage 映射用量，响应中缺失的字段为 nil
func mapResponsesUsage(response *responses.Response) core.Usage {
	usage := core.Usage{}
	if !response.JSON.Usage.Valid() {
		return usage
	}

	raw := response.Usage
	usage.InputTokens = validCount(raw.JSON.InputTokens.Valid(), raw.InputTokens)
	usage.OutputTokens = validCount(raw.JSON.OutputTokens.Valid(), raw.OutputTokens)
	usage.TotalTokens = validCount(raw.JSON.TotalTokens.Valid(), raw.TotalTokens)

	if raw.JSON.InputTokensDetails.Valid() {
		usage.CachedInputTokens = validCount(raw.InputTokensDetails.JSON.CachedTokens.Valid(), raw.InputTokensDetails.CachedTokens)
	}

	if raw.JSON.OutputTokensDetails.Valid() {
		usage.ReasoningTokens = validCount(raw.OutputTokensDetails.JSON.ReasoningTokens.Valid(), raw.OutputTokensDetails.ReasoningTokens)
	}

	return usage
}

// validCount 字段存在时返回计数指针，否则返回 nil
func validCount(isValid bool, value int64) *int64 {
	if !isValid {
		return nil
	}

	return core.Int64Ptr(value)
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

// buildImageURL URL 图片原样返回，base64 图片转换为 data URL
func buildImageURL(part core.ContentPart) string {
	if part.URL != "" {
		return part.URL
	}

	return "data:" + part.MimeType + ";base64," + part.Base64
}
