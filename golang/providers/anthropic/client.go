// Anthropic Provider 适配器：经官方 anthropic-sdk-go 的 Messages API 调用
// 创建日期：2026-11-16
// 修改日期：2026-11-16

package anthropic

import (
	"context"
	"encoding/json"
	"errors"
	"iter"
	"log/slog"
	"net/http"
	"slices"
	"strings"

	sdk "github.com/anthropics/anthropic-sdk-go/v2"
	"github.com/anthropics/anthropic-sdk-go/v2/option"

	"github.com/CharlesHYF/common-llm/golang/config"
	"github.com/CharlesHYF/common-llm/golang/core"
	"github.com/CharlesHYF/common-llm/golang/internal/providerkit"
)

// nativeMaxTokensKey 配置 native_options 中作为 maxOutputTokens 默认值的键
const nativeMaxTokensKey = "max_tokens"

// mappedKeys 由适配器映射的请求体字段，providerOptions 不得重名
var mappedKeys = []string{
	"model",
	"messages",
	"system",
	"max_tokens",
	"tools",
	"tool_choice",
	"output_config",
	"stream",
}

// capabilityFeatures Anthropic 支持的能力
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

// Client Anthropic 适配器，实现 core.ModelClient。
type Client struct {
	config config.ProviderConfig
	native sdk.Client
	logger *slog.Logger
	ids    core.IDGenerator
}

// New 按配置创建官方 SDK 客户端，不读取 ANTHROPIC_* 环境变量，重试交给 SDK。
func New(options Options) *Client {
	providerConfig := options.Config
	clientOptions := []option.RequestOption{
		option.WithoutEnvironmentDefaults(),
		option.WithAPIKey(providerConfig.APIKey),
		option.WithMaxRetries(providerConfig.MaxRetries),
		option.WithRequestTimeout(providerkit.RequestTimeout(&core.ModelRequest{}, providerConfig)),
		option.WithHTTPClient(providerkit.NewHTTPClient(providerConfig.ConnectTimeoutSeconds)),
	}

	if providerConfig.BaseURL != "" {
		clientOptions = append(clientOptions, option.WithBaseURL(providerConfig.BaseURL))
	}

	logger := options.Logger
	if logger == nil {
		logger = slog.Default()
	}

	ids := options.IDGenerator
	if ids == nil {
		ids = core.UUIDGenerator{}
	}

	return &Client{
		config: providerConfig,
		native: sdk.NewClient(clientOptions...),
		logger: logger,
		ids:    ids,
	}
}

// preparedRequest 校验后的请求参数
type preparedRequest struct {
	params      sdk.MessageNewParams
	traceID     string
	callOptions []option.RequestOption
	httpResult  *http.Response
}

// Generate 发起非流式调用。
func (c *Client) Generate(ctx context.Context, request *core.ModelRequest) (*core.ModelResponse, error) {
	prepared, err := c.prepare(request, "")
	if err != nil {
		return nil, err
	}

	callLogger := providerkit.NewCallLogger(c.logger, c.config.Name, prepared.params.Model, prepared.traceID)
	message, err := c.native.Messages.New(ctx, prepared.params, prepared.callOptions...)

	if err != nil {
		commonErr := c.mapError(ctx, err)
		callLogger.LogFailure(ctx, commonErr)
		return nil, commonErr
	}

	contentParts := []core.ContentPart{}
	for _, block := range message.Content {
		if block.Type == "text" {
			contentParts = append(contentParts, core.TextPart(block.Text))
			continue
		}

		if block.Type == "tool_use" {
			contentParts = append(contentParts, core.ToolCallPart(block.ID, block.Name, compactArguments(block.Input)))
		}
	}

	usage := buildUsage(usageCounts{
		input:         validCount(message.Usage.JSON.InputTokens.Valid(), message.Usage.InputTokens),
		cacheCreation: validCount(message.Usage.JSON.CacheCreationInputTokens.Valid(), message.Usage.CacheCreationInputTokens),
		cacheRead:     validCount(message.Usage.JSON.CacheReadInputTokens.Valid(), message.Usage.CacheReadInputTokens),
		output:        validCount(message.Usage.JSON.OutputTokens.Valid(), message.Usage.OutputTokens),
	})

	stopReason := string(message.StopReason)
	response := core.BuildModelResponse(c.config.Name, core.NonEmptyStringPtr(message.Model), core.NonEmptyStringPtr(message.ID), contentParts, mapFinishReason(stopReason), core.NonEmptyStringPtr(stopReason), usage, prepared.traceID, prepared.requestID())

	if request.IncludeRawResponse {
		response.RawResponse = json.RawMessage(message.RawJSON())
	}

	callLogger.LogSuccess(ctx, usage)
	return response, nil
}

// Stream 发起流式调用，message_start 的 usage 被 message_delta 的非空字段覆盖。
func (c *Client) Stream(ctx context.Context, request *core.ModelRequest) iter.Seq[core.ModelEvent] {
	return func(yield func(core.ModelEvent) bool) {
		traceID := ""
		if request != nil {
			traceID = request.TraceID
		}

		builder := core.NewModelEventBuilder(c.config.Name, core.ResolveTraceID(traceID, c.ids))
		prepared, err := c.prepare(request, builder.TraceID())

		if err != nil {
			commonErr := core.AsError(err, c.config.Name)
			yield(builder.ErrorEvent(commonErr))
			return
		}

		c.stream(ctx, request, prepared, builder, yield)
	}
}

// streamBlock 流式累积中的一个内容块
type streamBlock struct {
	part      core.ContentPart
	toolIndex int
}

// stream 逐个映射 Messages 流式事件
func (c *Client) stream(ctx context.Context, request *core.ModelRequest, prepared *preparedRequest, builder *core.ModelEventBuilder, yield func(core.ModelEvent) bool) {
	stream := c.native.Messages.NewStreaming(ctx, prepared.params, prepared.callOptions...)
	defer stream.Close()

	var model *string
	var messageID *string
	blocks := map[int64]*streamBlock{}
	blockOrder := []int64{}
	toolCount := 0
	stopReason := ""
	counts := usageCounts{}

	for stream.Next() {
		event := stream.Current()

		switch event.Type {
		case "message_start":
			model = core.NonEmptyStringPtr(event.Message.Model)
			messageID = core.NonEmptyStringPtr(event.Message.ID)
			startUsage := event.Message.Usage
			counts = usageCounts{
				input:         validCount(startUsage.JSON.InputTokens.Valid(), startUsage.InputTokens),
				cacheCreation: validCount(startUsage.JSON.CacheCreationInputTokens.Valid(), startUsage.CacheCreationInputTokens),
				cacheRead:     validCount(startUsage.JSON.CacheReadInputTokens.Valid(), startUsage.CacheReadInputTokens),
				output:        validCount(startUsage.JSON.OutputTokens.Valid(), startUsage.OutputTokens),
			}

			if !yield(builder.Started(model, messageID)) {
				return
			}
		case "content_block_start":
			block := event.ContentBlock

			if block.Type == "text" {
				blocks[event.Index] = &streamBlock{part: core.TextPart(block.Text)}
				blockOrder = append(blockOrder, event.Index)

				if block.Text != "" && !yield(builder.TextDelta(block.Text)) {
					return
				}

				continue
			}

			if block.Type == "tool_use" {
				blocks[event.Index] = &streamBlock{
					part:      core.ToolCallPart(block.ID, block.Name, ""),
					toolIndex: toolCount,
				}
				blockOrder = append(blockOrder, event.Index)
				toolCount++

				if !yield(builder.ToolCallDelta(blocks[event.Index].toolIndex, core.StringPtr(block.ID), core.StringPtr(block.Name), "")) {
					return
				}
			}
		case "content_block_delta":
			block, exists := blocks[event.Index]
			if !exists {
				continue
			}

			if event.Delta.Type == "text_delta" && event.Delta.Text != "" {
				block.part.Text += event.Delta.Text

				if !yield(builder.TextDelta(event.Delta.Text)) {
					return
				}

				continue
			}

			if event.Delta.Type == "input_json_delta" && event.Delta.PartialJSON != "" {
				block.part.Arguments += event.Delta.PartialJSON

				if !yield(builder.ToolCallDelta(block.toolIndex, nil, nil, event.Delta.PartialJSON)) {
					return
				}
			}
		case "message_delta":
			stopReason = string(event.Delta.StopReason)
			deltaUsage := event.Usage
			counts.overrideWith(usageCounts{
				input:         validCount(deltaUsage.JSON.InputTokens.Valid(), deltaUsage.InputTokens),
				cacheCreation: validCount(deltaUsage.JSON.CacheCreationInputTokens.Valid(), deltaUsage.CacheCreationInputTokens),
				cacheRead:     validCount(deltaUsage.JSON.CacheReadInputTokens.Valid(), deltaUsage.CacheReadInputTokens),
				output:        validCount(deltaUsage.JSON.OutputTokens.Valid(), deltaUsage.OutputTokens),
			})
		case "message_stop":
			contentParts := []core.ContentPart{}

			for _, index := range blockOrder {
				part := blocks[index].part
				if part.Type == core.PartToolCall && part.Arguments == "" {
					part.Arguments = "{}"
				}

				contentParts = append(contentParts, part)
			}

			usage := buildUsage(counts)
			if !yield(builder.UsageEvent(usage)) {
				return
			}

			response := core.BuildModelResponse(c.config.Name, model, messageID, contentParts, mapFinishReason(stopReason), core.NonEmptyStringPtr(stopReason), usage, builder.TraceID(), prepared.requestID())
			yield(builder.Completed(response))
			return
		}
	}

	streamErr := core.NewError(core.CategoryProviderError, "流在收到 message_stop 前结束", c.config.Name)
	if err := stream.Err(); err != nil {
		streamErr = c.mapError(ctx, err)
	}

	yield(builder.ErrorEvent(streamErr))
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

// NativeClient 返回官方 SDK 客户端 anthropic.Client。
func (c *Client) NativeClient() any {
	return c.native
}

// Close SDK 客户端没有需要释放的资源。
func (c *Client) Close() error {
	return nil
}

// prepare 校验请求、解析模型与 max_tokens，组装参数与请求级选项
func (c *Client) prepare(request *core.ModelRequest, traceID string) (*preparedRequest, error) {
	if err := core.ValidateModelRequest(request, c.config.Name); err != nil {
		return nil, err
	}

	// Anthropic 已弃用 temperature，SDK 的 Messages 参数中已移除，需要时经 providerOptions 自行透传
	if request.Temperature != nil {
		return nil, core.NewError(core.CategorySDKUnsupportedFeature, "Anthropic 已弃用 temperature，请改用 providerOptions 透传", c.config.Name)
	}

	model, err := providerkit.ResolveModel(request, c.config)
	if err != nil {
		return nil, err
	}

	configOptions := map[string]any{}
	for key, value := range c.config.NativeOptions {
		if key != nativeMaxTokensKey {
			configOptions[key] = value
		}
	}

	extraOptions := core.MergeProviderOptions(configOptions, request.ProviderOptions)
	conflicts := core.FindConflictingKeys(extraOptions, mappedKeys)

	if len(conflicts) > 0 {
		return nil, core.NewOptionConflictError(conflicts, c.config.Name)
	}

	maxTokens, err := c.resolveMaxTokens(request)
	if err != nil {
		return nil, err
	}

	params, err := buildParams(request, model, maxTokens)
	if err != nil {
		return nil, core.AsError(err, c.config.Name)
	}

	prepared := &preparedRequest{
		params:  params,
		traceID: core.FirstNonEmpty(traceID, core.ResolveTraceID(request.TraceID, c.ids)),
	}

	prepared.callOptions = []option.RequestOption{
		option.WithRequestTimeout(providerkit.RequestTimeout(request, c.config)),
		option.WithResponseInto(&prepared.httpResult),
	}

	for _, key := range providerkit.SortedKeys(extraOptions) {
		prepared.callOptions = append(prepared.callOptions, option.WithJSONSet(providerkit.EscapeJSONPathKey(key), extraOptions[key]))
	}

	return prepared, nil
}

// resolveMaxTokens 请求 maxOutputTokens 优先，其次配置 native_options.max_tokens，都没有报 CONFIG_INVALID
func (c *Client) resolveMaxTokens(request *core.ModelRequest) (int64, error) {
	if request.MaxOutputTokens != nil {
		return *request.MaxOutputTokens, nil
	}

	switch typed := c.config.NativeOptions[nativeMaxTokensKey].(type) {
	case int:
		return int64(typed), nil
	case int64:
		return typed, nil
	case float64:
		return int64(typed), nil
	}

	return 0, core.NewError(core.CategoryConfigInvalid, "Anthropic 要求 max_tokens：请求 maxOutputTokens 与配置 native_options.max_tokens 均为空", c.config.Name)
}

// requestID 读取响应头中的厂商请求 ID
func (p *preparedRequest) requestID() *string {
	if p.httpResult == nil {
		return nil
	}

	return core.NonEmptyStringPtr(p.httpResult.Header.Get("request-id"))
}

// mapError 按 SDK 错误类型与 HTTP 状态码映射为统一错误
func (c *Client) mapError(ctx context.Context, err error) *core.Error {
	var apiErr *sdk.Error
	if errors.As(err, &apiErr) {
		category, isRetriable := core.ClassifyHTTPStatus(apiErr.StatusCode, false, false)
		message := core.FirstNonEmpty(apiErr.Message, apiErr.Error())

		return core.WrapError(category, message, c.config.Name, err).
			WithStatusCode(apiErr.StatusCode).
			WithVendorCode(string(apiErr.Type())).
			WithRequestID(apiErr.RequestID).
			WithRetriable(isRetriable)
	}

	if transportErr := core.ClassifyTransportError(ctx, err, c.config.Name); transportErr != nil {
		return transportErr
	}

	return core.WrapError(core.CategoryProviderError, "Anthropic 请求失败", c.config.Name, err)
}

// usageCounts 厂商原始用量，nil 表示响应中没有该字段
type usageCounts struct {
	input         *int64
	cacheCreation *int64
	cacheRead     *int64
	output        *int64
}

// overrideWith 用非空字段覆盖当前值
func (u *usageCounts) overrideWith(other usageCounts) {
	if other.input != nil {
		u.input = other.input
	}

	if other.cacheCreation != nil {
		u.cacheCreation = other.cacheCreation
	}

	if other.cacheRead != nil {
		u.cacheRead = other.cacheRead
	}

	if other.output != nil {
		u.output = other.output
	}
}

// buildUsage inputTokens 为 input、缓存创建与缓存命中之和，Anthropic 不单独返回推理用量
func buildUsage(counts usageCounts) core.Usage {
	var totalInput *int64

	if counts.input != nil {
		total := *counts.input
		if counts.cacheCreation != nil {
			total += *counts.cacheCreation
		}

		if counts.cacheRead != nil {
			total += *counts.cacheRead
		}

		totalInput = core.Int64Ptr(total)
	}

	return core.Usage{
		InputTokens:       totalInput,
		OutputTokens:      counts.output,
		CachedInputTokens: counts.cacheRead,
		TotalTokens:       core.SumCounts(totalInput, counts.output),
	}
}

// mapFinishReason 映射 stop_reason
func mapFinishReason(stopReason string) core.FinishReason {
	switch stopReason {
	case "end_turn", "stop_sequence":
		return core.FinishStop
	case "max_tokens":
		return core.FinishLength
	case "tool_use":
		return core.FinishToolCalls
	case "refusal":
		return core.FinishContentFilter
	default:
		return core.FinishOther
	}
}

// validCount 字段存在时返回计数指针，否则返回 nil
func validCount(isValid bool, value int64) *int64 {
	if !isValid {
		return nil
	}

	return core.Int64Ptr(value)
}

// compactArguments 把工具输入对象序列化为紧凑 JSON，空输入为 {}
func compactArguments(input json.RawMessage) string {
	trimmed := strings.TrimSpace(string(input))
	if trimmed == "" || trimmed == "null" {
		return "{}"
	}

	var decoded any
	if err := json.Unmarshal(input, &decoded); err != nil {
		return trimmed
	}

	encoded, err := core.CompactJSON(decoded)
	if err != nil {
		return trimmed
	}

	return encoded
}
