// OpenAI Chat Completions 的请求组装、响应映射与流式事件映射
// 创建日期：2026-11-16
// 修改日期：2026-11-16

package openai

import (
	"context"
	"encoding/json"

	sdk "github.com/openai/openai-go/v3"
	"github.com/openai/openai-go/v3/shared"

	"github.com/CharlesHYF/common-llm/golang/core"
)

// chatMappedKeys Chat Completions 中由适配器映射的请求体字段，providerOptions 不得重名
var chatMappedKeys = []string{
	"model",
	"messages",
	"temperature",
	"max_completion_tokens",
	"tools",
	"tool_choice",
	"response_format",
	"stream",
	"stream_options",
}

// buildChatParams 把统一请求映射为 Chat Completions 参数，system 消息保留为 system 角色
func buildChatParams(request *core.ModelRequest, model string, isStream bool) sdk.ChatCompletionNewParams {
	params := sdk.ChatCompletionNewParams{
		Model: model,
	}

	for _, message := range request.Messages {
		params.Messages = append(params.Messages, buildChatMessages(message)...)
	}

	if request.Temperature != nil {
		params.Temperature = sdk.Float(*request.Temperature)
	}

	if request.MaxOutputTokens != nil {
		params.MaxCompletionTokens = sdk.Int(*request.MaxOutputTokens)
	}

	for _, tool := range request.Tools {
		definition := shared.FunctionDefinitionParam{
			Name:       tool.Name,
			Parameters: tool.Parameters,
		}

		if tool.Description != "" {
			definition.Description = sdk.String(tool.Description)
		}

		params.Tools = append(params.Tools, sdk.ChatCompletionToolUnionParam{
			OfFunction: &sdk.ChatCompletionFunctionToolParam{Function: definition},
		})
	}

	if request.ToolChoice != nil {
		params.ToolChoice = buildChatToolChoice(request.ToolChoice)
	}

	if request.ResponseFormat != nil {
		params.ResponseFormat = buildChatResponseFormat(request.ResponseFormat)
	}

	if isStream {
		params.StreamOptions = sdk.ChatCompletionStreamOptionsParam{IncludeUsage: sdk.Bool(true)}
	}

	return params
}

// buildChatMessages 把一条统一消息映射为 Chat Completions 消息，tool 消息按结果逐条展开
func buildChatMessages(message core.Message) []sdk.ChatCompletionMessageParamUnion {
	switch message.Role {
	case core.RoleSystem:
		return []sdk.ChatCompletionMessageParamUnion{{
			OfSystem: &sdk.ChatCompletionSystemMessageParam{
				Content: sdk.ChatCompletionSystemMessageParamContentUnion{OfString: sdk.String(joinTextParts(message.Content))},
			},
		}}
	case core.RoleUser:
		parts := []sdk.ChatCompletionContentPartUnionParam{}

		for _, part := range message.Content {
			if part.Type == core.PartText {
				parts = append(parts, sdk.ChatCompletionContentPartUnionParam{
					OfText: &sdk.ChatCompletionContentPartTextParam{Text: part.Text},
				})
				continue
			}

			if part.Type == core.PartImage {
				parts = append(parts, sdk.ChatCompletionContentPartUnionParam{
					OfImageURL: &sdk.ChatCompletionContentPartImageParam{
						ImageURL: sdk.ChatCompletionContentPartImageImageURLParam{URL: buildImageURL(part)},
					},
				})
			}
		}

		return []sdk.ChatCompletionMessageParamUnion{{
			OfUser: &sdk.ChatCompletionUserMessageParam{
				Content: sdk.ChatCompletionUserMessageParamContentUnion{OfArrayOfContentParts: parts},
			},
		}}
	case core.RoleAssistant:
		assistant := sdk.ChatCompletionAssistantMessageParam{}
		text := joinTextParts(message.Content)

		if text != "" {
			assistant.Content = sdk.ChatCompletionAssistantMessageParamContentUnion{OfString: sdk.String(text)}
		}

		for _, part := range message.Content {
			if part.Type != core.PartToolCall {
				continue
			}

			assistant.ToolCalls = append(assistant.ToolCalls, sdk.ChatCompletionMessageToolCallUnionParam{
				OfFunction: &sdk.ChatCompletionMessageFunctionToolCallParam{
					ID: part.ID,
					Function: sdk.ChatCompletionMessageFunctionToolCallFunctionParam{
						Name:      part.Name,
						Arguments: part.Arguments,
					},
				},
			})
		}

		return []sdk.ChatCompletionMessageParamUnion{{OfAssistant: &assistant}}
	default:
		messages := []sdk.ChatCompletionMessageParamUnion{}

		for _, part := range message.Content {
			if part.Type != core.PartToolResult {
				continue
			}

			messages = append(messages, sdk.ChatCompletionMessageParamUnion{
				OfTool: &sdk.ChatCompletionToolMessageParam{
					ToolCallID: part.ToolCallID,
					Content:    sdk.ChatCompletionToolMessageParamContentUnion{OfString: sdk.String(part.Content)},
				},
			})
		}

		return messages
	}
}

// buildChatToolChoice 映射工具选择策略
func buildChatToolChoice(choice *core.ToolChoice) sdk.ChatCompletionToolChoiceOptionUnionParam {
	if choice.Type == core.ToolChoiceTool {
		return sdk.ChatCompletionToolChoiceOptionUnionParam{
			OfFunctionToolChoice: &sdk.ChatCompletionNamedToolChoiceParam{
				Function: sdk.ChatCompletionNamedToolChoiceFunctionParam{Name: choice.Name},
			},
		}
	}

	return sdk.ChatCompletionToolChoiceOptionUnionParam{OfAuto: sdk.String(string(choice.Type))}
}

// buildChatResponseFormat 映射输出格式，json_schema 映射为 response_format.json_schema
func buildChatResponseFormat(format *core.ResponseFormat) sdk.ChatCompletionNewParamsResponseFormatUnion {
	if format.Type != core.ResponseFormatJSONSchema {
		return sdk.ChatCompletionNewParamsResponseFormatUnion{OfText: &shared.ResponseFormatTextParam{}}
	}

	schema := shared.ResponseFormatJSONSchemaJSONSchemaParam{
		Name:   format.Name,
		Schema: format.Schema,
	}

	if format.Strict != nil {
		schema.Strict = sdk.Bool(*format.Strict)
	}

	return sdk.ChatCompletionNewParamsResponseFormatUnion{
		OfJSONSchema: &shared.ResponseFormatJSONSchemaParam{JSONSchema: schema},
	}
}

// generateChat 调用 chat.completions.New 并映射响应
func (c *Client) generateChat(ctx context.Context, request *core.ModelRequest, prepared *preparedRequest) (*core.ModelResponse, error) {
	params := buildChatParams(request, prepared.model, false)
	completion, err := c.native.Chat.Completions.New(ctx, params, prepared.callOptions...)

	if err != nil {
		return nil, c.mapError(ctx, err)
	}

	if len(completion.Choices) == 0 {
		return nil, core.NewError(core.CategoryProviderError, "Chat Completions 响应没有 choices", c.config.Name).WithRequestID(stringValue(prepared.requestID()))
	}

	choice := completion.Choices[0]
	contentParts := []core.ContentPart{}

	if choice.Message.Content != "" {
		contentParts = append(contentParts, core.TextPart(choice.Message.Content))
	}

	for _, toolCall := range choice.Message.ToolCalls {
		contentParts = append(contentParts, core.ToolCallPart(toolCall.ID, toolCall.Function.Name, toolCall.Function.Arguments))
	}

	usage := core.Usage{}
	if completion.JSON.Usage.Valid() {
		usage = mapChatUsage(completion.Usage)
	}

	response := core.BuildModelResponse(c.config.Name, core.NonEmptyStringPtr(completion.Model), core.NonEmptyStringPtr(completion.ID), contentParts, mapChatFinishReason(choice.FinishReason), core.NonEmptyStringPtr(choice.FinishReason), usage, prepared.traceID, prepared.requestID())

	if request.IncludeRawResponse {
		response.RawResponse = json.RawMessage(completion.RawJSON())
	}

	return response, nil
}

// chatToolAccumulator 流式工具调用的累积状态
type chatToolAccumulator struct {
	id        string
	name      string
	arguments string
}

// streamChat 调用 chat.completions.NewStreaming，首个 chunk 发 started，末尾 usage chunk 的 choices 为空
func (c *Client) streamChat(ctx context.Context, request *core.ModelRequest, prepared *preparedRequest, builder *core.ModelEventBuilder, yield func(core.ModelEvent) bool) {
	params := buildChatParams(request, prepared.model, true)
	stream := c.native.Chat.Completions.NewStreaming(ctx, params, prepared.callOptions...)
	defer stream.Close()

	hasStarted := false
	var model *string
	var responseID *string
	text := ""
	toolOrdinals := map[int64]int{}
	tools := []*chatToolAccumulator{}
	finishReason := ""
	var usage *core.Usage

	for stream.Next() {
		chunk := stream.Current()

		if !hasStarted {
			hasStarted = true
			model = core.NonEmptyStringPtr(chunk.Model)
			responseID = core.NonEmptyStringPtr(chunk.ID)

			if !yield(builder.Started(model, responseID)) {
				return
			}
		}

		if chunk.JSON.Usage.Valid() {
			mapped := mapChatUsage(chunk.Usage)
			usage = &mapped
		}

		for _, choice := range chunk.Choices {
			if choice.Delta.Content != "" {
				text += choice.Delta.Content

				if !yield(builder.TextDelta(choice.Delta.Content)) {
					return
				}
			}

			for _, toolDelta := range choice.Delta.ToolCalls {
				ordinal, exists := toolOrdinals[toolDelta.Index]
				if !exists {
					ordinal = len(tools)
					toolOrdinals[toolDelta.Index] = ordinal
					tools = append(tools, &chatToolAccumulator{
						id:   toolDelta.ID,
						name: toolDelta.Function.Name,
					})
				}

				tools[ordinal].arguments += toolDelta.Function.Arguments
				callID := core.NonEmptyStringPtr(toolDelta.ID)
				name := core.NonEmptyStringPtr(toolDelta.Function.Name)

				if !core.ShouldEmitToolDelta(callID, name, toolDelta.Function.Arguments) {
					continue
				}

				if !yield(builder.ToolCallDelta(ordinal, callID, name, toolDelta.Function.Arguments)) {
					return
				}
			}

			if choice.FinishReason != "" {
				finishReason = choice.FinishReason
			}
		}
	}

	if err := stream.Err(); err != nil {
		streamErr := c.mapError(ctx, err)
		yield(builder.ErrorEvent(streamErr))
		return
	}

	contentParts := []core.ContentPart{}
	if text != "" {
		contentParts = append(contentParts, core.TextPart(text))
	}

	for _, tool := range tools {
		contentParts = append(contentParts, core.ToolCallPart(tool.id, tool.name, tool.arguments))
	}

	finalUsage := core.Usage{}
	if usage != nil {
		finalUsage = *usage

		if !yield(builder.UsageEvent(finalUsage)) {
			return
		}
	}

	response := core.BuildModelResponse(c.config.Name, model, responseID, contentParts, mapChatFinishReason(finishReason), core.NonEmptyStringPtr(finishReason), finalUsage, builder.TraceID(), prepared.requestID())
	yield(builder.Completed(response))
}

// mapChatFinishReason 映射 finish_reason
func mapChatFinishReason(finishReason string) core.FinishReason {
	switch finishReason {
	case "stop":
		return core.FinishStop
	case "length":
		return core.FinishLength
	case "tool_calls", "function_call":
		return core.FinishToolCalls
	case "content_filter":
		return core.FinishContentFilter
	default:
		return core.FinishOther
	}
}

// mapChatUsage 映射用量，缺失的字段为 nil
func mapChatUsage(raw sdk.CompletionUsage) core.Usage {
	usage := core.Usage{
		InputTokens:  validCount(raw.JSON.PromptTokens.Valid(), raw.PromptTokens),
		OutputTokens: validCount(raw.JSON.CompletionTokens.Valid(), raw.CompletionTokens),
		TotalTokens:  validCount(raw.JSON.TotalTokens.Valid(), raw.TotalTokens),
	}

	if raw.JSON.PromptTokensDetails.Valid() {
		usage.CachedInputTokens = validCount(raw.PromptTokensDetails.JSON.CachedTokens.Valid(), raw.PromptTokensDetails.CachedTokens)
	}

	if raw.JSON.CompletionTokensDetails.Valid() {
		usage.ReasoningTokens = validCount(raw.CompletionTokensDetails.JSON.ReasoningTokens.Valid(), raw.CompletionTokensDetails.ReasoningTokens)
	}

	return usage
}

// stringValue 读取可空字符串，nil 返回空字符串
func stringValue(value *string) string {
	if value == nil {
		return ""
	}

	return *value
}
