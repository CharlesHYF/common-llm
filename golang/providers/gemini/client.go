// Gemini Provider 适配器：经官方 google.golang.org/genai 的 generateContent 调用
// 创建日期：2026-11-16
// 修改日期：2026-11-16

package gemini

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"iter"
	"log/slog"
	"slices"
	"strconv"
	"strings"
	"sync"

	"google.golang.org/genai"

	"github.com/CharlesHYF/common-llm/golang/config"
	"github.com/CharlesHYF/common-llm/golang/core"
	"github.com/CharlesHYF/common-llm/golang/internal/providerkit"
)

// mappedTopLevelKeys 由适配器映射的请求体顶层字段，providerOptions 不得重名
var mappedTopLevelKeys = []string{
	"contents",
	"systemInstruction",
	"tools",
	"toolConfig",
}

// mappedGenerationKeys generationConfig 中由适配器映射的字段，providerOptions 不得重名
var mappedGenerationKeys = []string{
	"temperature",
	"maxOutputTokens",
	"responseMimeType",
	"responseJsonSchema",
	"responseSchema",
}

// contentFilterReasons 映射为 content_filter 的 finishReason
var contentFilterReasons = []string{
	"SAFETY",
	"RECITATION",
	"BLOCKLIST",
	"PROHIBITED_CONTENT",
	"SPII",
	"IMAGE_SAFETY",
}

// capabilityFeatures Gemini 支持的能力
var capabilityFeatures = []string{
	core.FeatureStreaming,
	core.FeatureToolCalls,
	core.FeatureStructuredOutput,
	core.FeatureVision,
	core.FeatureReasoning,
}

// Options 创建适配器所需参数。
type Options struct {
	Config      config.ProviderConfig
	Logger      *slog.Logger
	IDGenerator core.IDGenerator
}

// Client Gemini 适配器，实现 core.ModelClient。
type Client struct {
	config      config.ProviderConfig
	logger      *slog.Logger
	ids         core.IDGenerator
	clientMutex sync.Mutex
	native      *genai.Client
}

// New 创建适配器，SDK 客户端在首次调用时按配置创建。
func New(options Options) *Client {
	logger := options.Logger
	if logger == nil {
		logger = slog.Default()
	}

	ids := options.IDGenerator
	if ids == nil {
		ids = core.UUIDGenerator{}
	}

	return &Client{
		config: options.Config,
		logger: logger,
		ids:    ids,
	}
}

// nativeClient 创建或返回 SDK 客户端，重试次数映射为 attempts = max_retries + 1
func (c *Client) nativeClient(ctx context.Context) (*genai.Client, error) {
	c.clientMutex.Lock()
	defer c.clientMutex.Unlock()

	if c.native != nil {
		return c.native, nil
	}

	timeout := providerkit.RequestTimeout(&core.ModelRequest{}, c.config)
	attempts := int32(c.config.MaxRetries + 1)
	clientConfig := &genai.ClientConfig{
		APIKey:     c.config.APIKey,
		Backend:    genai.BackendGeminiAPI,
		HTTPClient: providerkit.NewHTTPClient(c.config.ConnectTimeoutSeconds),
		HTTPOptions: genai.HTTPOptions{
			BaseURL:      c.config.BaseURL,
			Timeout:      &timeout,
			RetryOptions: &genai.HTTPRetryOptions{Attempts: &attempts},
		},
	}

	native, err := genai.NewClient(ctx, clientConfig)
	if err != nil {
		return nil, core.WrapError(core.CategoryConfigInvalid, "创建 Gemini 客户端失败", c.config.Name, err)
	}

	c.native = native
	return native, nil
}

// preparedRequest 校验后的请求参数
type preparedRequest struct {
	native   *genai.Client
	model    string
	traceID  string
	contents []*genai.Content
	config   *genai.GenerateContentConfig
}

// Generate 发起非流式调用。
func (c *Client) Generate(ctx context.Context, request *core.ModelRequest) (*core.ModelResponse, error) {
	prepared, err := c.prepare(ctx, request, "")
	if err != nil {
		return nil, err
	}

	callLogger := providerkit.NewCallLogger(c.logger, c.config.Name, prepared.model, prepared.traceID)
	response, err := prepared.native.Models.GenerateContent(ctx, prepared.model, prepared.contents, prepared.config)

	if err != nil {
		commonErr := c.mapError(ctx, err)
		callLogger.LogFailure(ctx, commonErr)
		return nil, commonErr
	}

	contentParts, finishReason := collectParts(response, 0)
	mapped := core.BuildModelResponse(c.config.Name, core.NonEmptyStringPtr(response.ModelVersion), core.NonEmptyStringPtr(response.ResponseID), contentParts, mapFinishReason(finishReason, hasToolCall(contentParts)), core.NonEmptyStringPtr(finishReason), mapUsage(response.UsageMetadata), prepared.traceID, nil)

	if request.IncludeRawResponse {
		raw, marshalErr := json.Marshal(response)
		if marshalErr == nil {
			mapped.RawResponse = raw
		}
	}

	callLogger.LogSuccess(ctx, mapped.Usage)
	return mapped, nil
}

// Stream 发起流式调用，只取第一个候选，跳过 thought 部分。
func (c *Client) Stream(ctx context.Context, request *core.ModelRequest) iter.Seq[core.ModelEvent] {
	return func(yield func(core.ModelEvent) bool) {
		traceID := ""
		if request != nil {
			traceID = request.TraceID
		}

		builder := core.NewModelEventBuilder(c.config.Name, core.ResolveTraceID(traceID, c.ids))
		prepared, err := c.prepare(ctx, request, builder.TraceID())

		if err != nil {
			commonErr := core.AsError(err, c.config.Name)
			yield(builder.ErrorEvent(commonErr))
			return
		}

		hasStarted := false
		var model *string
		var responseID *string
		text := ""
		toolParts := []core.ContentPart{}
		finishReason := ""
		var lastUsage *genai.GenerateContentResponseUsageMetadata

		for chunk, chunkErr := range prepared.native.Models.GenerateContentStream(ctx, prepared.model, prepared.contents, prepared.config) {
			if chunkErr != nil {
				streamErr := c.mapError(ctx, chunkErr)
				yield(builder.ErrorEvent(streamErr))
				return
			}

			if !hasStarted {
				hasStarted = true
				model = core.NonEmptyStringPtr(chunk.ModelVersion)
				responseID = core.NonEmptyStringPtr(chunk.ResponseID)

				if !yield(builder.Started(model, responseID)) {
					return
				}
			}

			chunkParts, chunkFinish := collectParts(chunk, len(toolParts))

			for _, part := range chunkParts {
				if part.Type == core.PartText {
					if part.Text == "" {
						continue
					}

					text += part.Text

					if !yield(builder.TextDelta(part.Text)) {
						return
					}

					continue
				}

				toolIndex := len(toolParts)
				toolParts = append(toolParts, part)

				if !yield(builder.ToolCallDelta(toolIndex, core.StringPtr(part.ID), core.StringPtr(part.Name), part.Arguments)) {
					return
				}
			}

			if chunkFinish != "" {
				finishReason = chunkFinish
			}

			if chunk.UsageMetadata != nil {
				lastUsage = chunk.UsageMetadata
			}
		}

		finalParts := []core.ContentPart{}
		if text != "" {
			finalParts = append(finalParts, core.TextPart(text))
		}

		finalParts = append(finalParts, toolParts...)
		usage := mapUsage(lastUsage)

		if lastUsage != nil && !yield(builder.UsageEvent(usage)) {
			return
		}

		response := core.BuildModelResponse(c.config.Name, model, responseID, finalParts, mapFinishReason(finishReason, len(toolParts) > 0), core.NonEmptyStringPtr(finishReason), usage, builder.TraceID(), nil)
		yield(builder.Completed(response))
	}
}

// Supports 判断是否支持指定能力，未知能力返回 false。
func (c *Client) Supports(feature string) bool {
	return core.HasFeature(capabilityFeatures, feature)
}

// Capability 返回能力集合。
func (c *Client) Capability() core.ProviderCapability {
	return core.ProviderCapability{
		Provider: c.config.Name,
		Features: slices.Clone(capabilityFeatures),
	}
}

// NativeClient 返回官方 SDK 客户端 *genai.Client，创建失败时返回 nil。
func (c *Client) NativeClient() any {
	native, err := c.nativeClient(context.Background())
	if err != nil {
		return nil
	}

	return native
}

// Close SDK 客户端没有需要释放的资源。
func (c *Client) Close() error {
	return nil
}

// prepare 校验请求、检查 providerOptions 冲突并组装 SDK 参数
func (c *Client) prepare(ctx context.Context, request *core.ModelRequest, traceID string) (*preparedRequest, error) {
	if err := core.ValidateModelRequest(request, c.config.Name); err != nil {
		return nil, err
	}

	model, err := providerkit.ResolveModel(request, c.config)
	if err != nil {
		return nil, err
	}

	extraOptions := core.MergeProviderOptions(c.config.NativeOptions, request.ProviderOptions)
	conflicts := core.FindConflictingKeys(extraOptions, mappedTopLevelKeys)

	if generationOptions, isMap := extraOptions["generationConfig"].(map[string]any); isMap {
		for _, conflict := range core.FindConflictingKeys(generationOptions, mappedGenerationKeys) {
			conflicts = append(conflicts, "generationConfig."+conflict)
		}
	}

	if len(conflicts) > 0 {
		return nil, core.NewOptionConflictError(conflicts, c.config.Name)
	}

	contents, systemInstruction, err := buildContents(request.Messages)
	if err != nil {
		return nil, core.AsError(err, c.config.Name)
	}

	timeout := providerkit.RequestTimeout(request, c.config)
	generateConfig := &genai.GenerateContentConfig{
		HTTPOptions:       &genai.HTTPOptions{Timeout: &timeout},
		SystemInstruction: systemInstruction,
	}

	if len(extraOptions) > 0 {
		generateConfig.HTTPOptions.ExtraBody = extraOptions
	}

	applyGenerationOptions(generateConfig, request)

	native, err := c.nativeClient(ctx)
	if err != nil {
		return nil, err
	}

	return &preparedRequest{
		native:   native,
		model:    model,
		traceID:  core.FirstNonEmpty(traceID, core.ResolveTraceID(request.TraceID, c.ids)),
		contents: contents,
		config:   generateConfig,
	}, nil
}

// applyGenerationOptions 映射温度、输出上限、工具、工具选择与结构化输出
func applyGenerationOptions(generateConfig *genai.GenerateContentConfig, request *core.ModelRequest) {
	if request.Temperature != nil {
		temperature := float32(*request.Temperature)
		generateConfig.Temperature = &temperature
	}

	if request.MaxOutputTokens != nil {
		generateConfig.MaxOutputTokens = int32(*request.MaxOutputTokens)
	}

	if len(request.Tools) > 0 {
		declarations := []*genai.FunctionDeclaration{}

		for _, tool := range request.Tools {
			declarations = append(declarations, &genai.FunctionDeclaration{
				Name:                 tool.Name,
				Description:          tool.Description,
				ParametersJsonSchema: tool.Parameters,
			})
		}

		generateConfig.Tools = []*genai.Tool{{FunctionDeclarations: declarations}}
	}

	if request.ToolChoice != nil {
		callingConfig := &genai.FunctionCallingConfig{Mode: genai.FunctionCallingConfigModeAuto}

		switch request.ToolChoice.Type {
		case core.ToolChoiceNone:
			callingConfig.Mode = genai.FunctionCallingConfigModeNone
		case core.ToolChoiceRequired:
			callingConfig.Mode = genai.FunctionCallingConfigModeAny
		case core.ToolChoiceTool:
			callingConfig.Mode = genai.FunctionCallingConfigModeAny
			callingConfig.AllowedFunctionNames = []string{request.ToolChoice.Name}
		}

		generateConfig.ToolConfig = &genai.ToolConfig{FunctionCallingConfig: callingConfig}
	}

	if request.ResponseFormat != nil && request.ResponseFormat.Type == core.ResponseFormatJSONSchema {
		generateConfig.ResponseMIMEType = "application/json"
		generateConfig.ResponseJsonSchema = request.ResponseFormat.Schema
	}
}

// buildContents 把消息映射为 Content，assistant 映射为 model 角色，tool 结果映射为 functionResponse
func buildContents(messages []core.Message) ([]*genai.Content, *genai.Content, error) {
	contents := []*genai.Content{}
	systemParts := []*genai.Part{}
	toolNames := map[string]string{}

	for _, message := range messages {
		switch message.Role {
		case core.RoleSystem:
			for _, part := range message.Content {
				if part.Type == core.PartText {
					systemParts = append(systemParts, &genai.Part{Text: part.Text})
				}
			}
		case core.RoleUser:
			parts, err := buildUserParts(message.Content)
			if err != nil {
				return nil, nil, err
			}

			contents = append(contents, &genai.Content{Role: genai.RoleUser, Parts: parts})
		case core.RoleAssistant:
			parts := []*genai.Part{}

			for _, part := range message.Content {
				if part.Type == core.PartText {
					parts = append(parts, &genai.Part{Text: part.Text})
					continue
				}

				if part.Type != core.PartToolCall {
					continue
				}

				args := map[string]any{}
				if strings.TrimSpace(part.Arguments) != "" {
					if err := json.Unmarshal([]byte(part.Arguments), &args); err != nil {
						return nil, nil, core.WrapError(core.CategoryConfigInvalid, "tool_call.arguments 不是合法 JSON 对象："+part.ID, "", err)
					}
				}

				toolNames[part.ID] = part.Name
				parts = append(parts, &genai.Part{FunctionCall: &genai.FunctionCall{ID: part.ID, Name: part.Name, Args: args}})
			}

			contents = append(contents, &genai.Content{Role: genai.RoleModel, Parts: parts})
		case core.RoleTool:
			parts := []*genai.Part{}

			for _, part := range message.Content {
				if part.Type != core.PartToolResult {
					continue
				}

				responseKey := "output"
				if part.IsError {
					responseKey = "error"
				}

				parts = append(parts, &genai.Part{FunctionResponse: &genai.FunctionResponse{
					ID:       part.ToolCallID,
					Name:     toolNames[part.ToolCallID],
					Response: map[string]any{responseKey: part.Content},
				}})
			}

			contents = append(contents, &genai.Content{Role: genai.RoleUser, Parts: parts})
		}
	}

	if len(systemParts) == 0 {
		return contents, nil, nil
	}

	return contents, &genai.Content{Parts: systemParts}, nil
}

// buildUserParts 映射文本与图片，base64 图片解码为 inlineData，URL 图片映射为 fileData
func buildUserParts(contentParts []core.ContentPart) ([]*genai.Part, error) {
	parts := []*genai.Part{}

	for _, part := range contentParts {
		if part.Type == core.PartText {
			parts = append(parts, &genai.Part{Text: part.Text})
			continue
		}

		if part.Type != core.PartImage {
			continue
		}

		if part.URL != "" {
			parts = append(parts, &genai.Part{FileData: &genai.FileData{FileURI: part.URL, MIMEType: part.MimeType}})
			continue
		}

		data, err := base64.StdEncoding.DecodeString(part.Base64)
		if err != nil {
			return nil, core.WrapError(core.CategoryConfigInvalid, "image.base64 不是合法的 base64", "", err)
		}

		parts = append(parts, &genai.Part{InlineData: &genai.Blob{Data: data, MIMEType: part.MimeType}})
	}

	return parts, nil
}

// collectParts 提取首个候选的文本与函数调用，缺少 id 的函数调用按出现顺序生成 gemini_call_<序号>
func collectParts(response *genai.GenerateContentResponse, toolOffset int) ([]core.ContentPart, string) {
	contentParts := []core.ContentPart{}

	if response == nil || len(response.Candidates) == 0 || response.Candidates[0] == nil {
		return contentParts, ""
	}

	candidate := response.Candidates[0]
	toolCount := toolOffset

	if candidate.Content != nil {
		for _, part := range candidate.Content.Parts {
			if part == nil || part.Thought {
				continue
			}

			if part.FunctionCall != nil {
				callID := core.FirstNonEmpty(part.FunctionCall.ID, "gemini_call_"+strconv.Itoa(toolCount))
				toolCount++
				arguments := "{}"

				if part.FunctionCall.Args != nil {
					encoded, err := core.CompactJSON(part.FunctionCall.Args)
					if err == nil {
						arguments = encoded
					}
				}

				contentParts = append(contentParts, core.ToolCallPart(callID, part.FunctionCall.Name, arguments))
				continue
			}

			contentParts = append(contentParts, core.TextPart(part.Text))
		}
	}

	return contentParts, string(candidate.FinishReason)
}

// mapUsage 映射用量；SDK 的计数字段为 omitempty 整数，缓存与推理用量为 0 时视为厂商未返回
func mapUsage(usage *genai.GenerateContentResponseUsageMetadata) core.Usage {
	if usage == nil {
		return core.Usage{}
	}

	mapped := core.Usage{
		InputTokens:  core.Int64Ptr(int64(usage.PromptTokenCount)),
		OutputTokens: core.Int64Ptr(int64(usage.CandidatesTokenCount)),
		TotalTokens:  core.Int64Ptr(int64(usage.TotalTokenCount)),
	}

	if usage.CachedContentTokenCount != 0 {
		mapped.CachedInputTokens = core.Int64Ptr(int64(usage.CachedContentTokenCount))
	}

	if usage.ThoughtsTokenCount != 0 {
		mapped.ReasoningTokens = core.Int64Ptr(int64(usage.ThoughtsTokenCount))
	}

	return mapped
}

// mapFinishReason 含函数调用时为 tool_calls，其余按 finishReason 映射
func mapFinishReason(finishReason string, hasToolCall bool) core.FinishReason {
	if hasToolCall {
		return core.FinishToolCalls
	}

	switch {
	case finishReason == "STOP":
		return core.FinishStop
	case finishReason == "MAX_TOKENS":
		return core.FinishLength
	case slices.Contains(contentFilterReasons, finishReason):
		return core.FinishContentFilter
	default:
		return core.FinishOther
	}
}

// hasToolCall 判断内容片段中是否有工具调用
func hasToolCall(parts []core.ContentPart) bool {
	for _, part := range parts {
		if part.Type == core.PartToolCall {
			return true
		}
	}

	return false
}

// mapError 把 genai.APIError 按状态码映射，400 且详情含 API_KEY_INVALID 视为认证失败
func (c *Client) mapError(ctx context.Context, err error) *core.Error {
	var apiErr genai.APIError
	if errors.As(err, &apiErr) {
		details, _ := json.Marshal(apiErr.Details)
		isAuthFailure := apiErr.Code == 400 && strings.Contains(string(details), "API_KEY_INVALID")
		category, isRetriable := core.ClassifyHTTPStatus(apiErr.Code, false, isAuthFailure)

		return core.WrapError(category, core.FirstNonEmpty(apiErr.Message, "Gemini 请求失败"), c.config.Name, err).
			WithStatusCode(apiErr.Code).
			WithVendorCode(apiErr.Status).
			WithRetriable(isRetriable)
	}

	if transportErr := core.ClassifyTransportError(ctx, err, c.config.Name); transportErr != nil {
		return transportErr
	}

	return core.WrapError(core.CategoryProviderError, "Gemini 请求失败", c.config.Name, err)
}
