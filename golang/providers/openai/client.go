// OpenAI Provider 适配器：按配置选择 Responses API 或 Chat Completions，经官方 openai-go SDK 调用
// 创建日期：2026-11-16
// 修改日期：2026-11-16

package openai

import (
	"context"
	"errors"
	"iter"
	"log/slog"
	"net/http"
	"slices"

	sdk "github.com/openai/openai-go/v3"
	"github.com/openai/openai-go/v3/option"

	"github.com/CharlesHYF/common-llm/golang/config"
	"github.com/CharlesHYF/common-llm/golang/core"
	"github.com/CharlesHYF/common-llm/golang/internal/providerkit"
)

// requestIDHeader OpenAI 返回请求 ID 的响应头
const requestIDHeader = "x-request-id"

// capabilityFeatures OpenAI 两种协议都支持的能力
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

// Client OpenAI 适配器，实现 core.ModelClient。
type Client struct {
	config config.ProviderConfig
	native sdk.Client
	logger *slog.Logger
	ids    core.IDGenerator
}

// New 按配置创建官方 SDK 客户端，重试次数交给 SDK 内置机制。
func New(options Options) *Client {
	providerConfig := options.Config
	clientOptions := []option.RequestOption{
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

// Generate 发起非流式调用。
func (c *Client) Generate(ctx context.Context, request *core.ModelRequest) (*core.ModelResponse, error) {
	prepared, err := c.prepare(request)
	if err != nil {
		return nil, err
	}

	callLogger := providerkit.NewCallLogger(c.logger, c.config.Name, prepared.model, prepared.traceID)
	var response *core.ModelResponse

	if c.config.API == config.OpenAIAPIChatCompletions {
		response, err = c.generateChat(ctx, request, prepared)
	} else {
		response, err = c.generateResponses(ctx, request, prepared)
	}

	if err != nil {
		commonErr := core.AsError(err, c.config.Name)
		callLogger.LogFailure(ctx, commonErr)
		return nil, commonErr
	}

	callLogger.LogSuccess(ctx, response.Usage)
	return response, nil
}

// Stream 发起流式调用，error 事件同时携带 *core.Error。
func (c *Client) Stream(ctx context.Context, request *core.ModelRequest) iter.Seq[core.ModelEvent] {
	return func(yield func(core.ModelEvent) bool) {
		traceID := ""
		if request != nil {
			traceID = request.TraceID
		}

		builder := core.NewModelEventBuilder(c.config.Name, core.ResolveTraceID(traceID, c.ids))
		prepared, err := c.prepare(request)

		if err != nil {
			commonErr := core.AsError(err, c.config.Name)
			yield(builder.ErrorEvent(commonErr))
			return
		}

		prepared.traceID = builder.TraceID()

		if c.config.API == config.OpenAIAPIChatCompletions {
			c.streamChat(ctx, request, prepared, builder, yield)
			return
		}

		c.streamResponses(ctx, request, prepared, builder, yield)
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

// NativeClient 返回官方 SDK 客户端 openai.Client。
func (c *Client) NativeClient() any {
	return c.native
}

// Close SDK 客户端没有需要释放的资源。
func (c *Client) Close() error {
	return nil
}

// preparedRequest 校验后的公共请求参数
type preparedRequest struct {
	model       string
	traceID     string
	callOptions []option.RequestOption
	httpResult  *http.Response
}

// prepare 校验请求、解析模型并组装请求级 SDK 选项
func (c *Client) prepare(request *core.ModelRequest) (*preparedRequest, error) {
	if err := core.ValidateModelRequest(request, c.config.Name); err != nil {
		return nil, err
	}

	model, err := providerkit.ResolveModel(request, c.config)
	if err != nil {
		return nil, err
	}

	mappedKeys := responsesMappedKeys
	if c.config.API == config.OpenAIAPIChatCompletions {
		mappedKeys = chatMappedKeys
	}

	extraOptions := core.MergeProviderOptions(c.config.NativeOptions, request.ProviderOptions)
	conflicts := core.FindConflictingKeys(extraOptions, mappedKeys)

	if len(conflicts) > 0 {
		return nil, core.NewOptionConflictError(conflicts, c.config.Name)
	}

	prepared := &preparedRequest{
		model:   model,
		traceID: core.ResolveTraceID(request.TraceID, c.ids),
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

// requestID 读取响应头中的厂商请求 ID
func (p *preparedRequest) requestID() *string {
	if p.httpResult == nil {
		return nil
	}

	return core.NonEmptyStringPtr(p.httpResult.Header.Get(requestIDHeader))
}

// mapError 按 SDK 错误类型与 HTTP 状态码映射为统一错误
func (c *Client) mapError(ctx context.Context, err error) *core.Error {
	var apiErr *sdk.Error
	if errors.As(err, &apiErr) {
		vendorCode := core.FirstNonEmpty(apiErr.Code, apiErr.Type)
		category, isRetriable := core.ClassifyHTTPStatus(apiErr.StatusCode, apiErr.Code == "insufficient_quota", false)
		requestID := ""

		if apiErr.Response != nil {
			requestID = apiErr.Response.Header.Get(requestIDHeader)
		}

		message := core.FirstNonEmpty(apiErr.Message, apiErr.Error())
		return core.WrapError(category, message, c.config.Name, err).
			WithStatusCode(apiErr.StatusCode).
			WithVendorCode(vendorCode).
			WithRequestID(requestID).
			WithRetriable(isRetriable)
	}

	if transportErr := core.ClassifyTransportError(ctx, err, c.config.Name); transportErr != nil {
		return transportErr
	}

	var commonErr *core.Error
	if errors.As(err, &commonErr) {
		return commonErr
	}

	return core.WrapError(core.CategoryProviderError, "OpenAI 请求失败", c.config.Name, err)
}
