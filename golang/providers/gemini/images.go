// Gemini 图片生成：经 generateContent 原生出图，n 张时按 image_concurrency 库内循环
// 创建日期：2026-11-16
// 修改日期：2026-11-16

package gemini

import (
	"context"
	"encoding/json"
	"iter"
	"slices"
	"strings"

	"google.golang.org/genai"

	"github.com/CharlesHYF/common-llm/golang/core"
	"github.com/CharlesHYF/common-llm/golang/internal/providerkit"
)

// imageFeatures Gemini 支持的图片生成能力，不支持蒙版、部分图片与原生多张
var imageFeatures = []string{
	core.FeatureImageGeneration,
	core.FeatureImageEdit,
}

// imageUnsupportedFields Gemini 原生出图不支持的请求字段
var imageUnsupportedFields = []string{
	"size",
	"quality",
	"outputFormat",
	"outputCompression",
	"background",
	"mask",
	"partialImages",
	"imageUrl",
}

// ImageClient Gemini 图片生成客户端，复用 Client 的 SDK 客户端与错误映射，实现 core.ImageClient。
type ImageClient struct {
	client *Client
}

// Images 返回共用同一 SDK 客户端的图片生成客户端。
func (c *Client) Images() *ImageClient {
	return &ImageClient{client: c}
}

// Supports 判断是否支持指定能力，未知能力返回 false。
func (c *ImageClient) Supports(feature string) bool {
	return c.client.Supports(feature)
}

// Capability 返回能力集合，与模型客户端一致。
func (c *ImageClient) Capability() core.ProviderCapability {
	return c.client.Capability()
}

// NativeClient 返回官方 SDK 客户端 *genai.Client。
func (c *ImageClient) NativeClient() any {
	return c.client.NativeClient()
}

// Close 图片客户端与模型客户端共用 SDK 客户端，没有单独需要释放的资源。
func (c *ImageClient) Close() error {
	return nil
}

// imagePlan 校验后的图片请求参数
type imagePlan struct {
	request  *core.ImageRequest
	native   *genai.Client
	model    string
	traceID  string
	contents []*genai.Content
	config   *genai.GenerateContentConfig
}

// prepare 校验请求、拒绝 Gemini 不支持的字段、检查 providerOptions 冲突并组装 SDK 参数
func (c *ImageClient) prepare(ctx context.Context, request *core.ImageRequest, isStream bool) (*imagePlan, error) {
	provider := c.client.config.Name

	if err := core.ValidateImageRequest(request, provider); err != nil {
		return nil, err
	}

	if err := providerkit.RejectPartialImagesOnGenerate(request, isStream, provider); err != nil {
		return nil, err
	}

	if err := providerkit.RejectUnsupportedImageFields(request, imageUnsupportedFields, "Gemini generateContent", provider); err != nil {
		return nil, err
	}

	model, err := providerkit.ResolveImageModel(request, c.client.config)
	if err != nil {
		return nil, err
	}

	conflicts := core.FindConflictingKeys(request.ProviderOptions, mappedTopLevelKeys)
	if generationOptions, isMap := request.ProviderOptions["generationConfig"].(map[string]any); isMap {
		for _, conflict := range core.FindConflictingKeys(generationOptions, []string{"responseModalities", "imageConfig"}) {
			conflicts = append(conflicts, "generationConfig."+conflict)
		}
	}

	if len(conflicts) > 0 {
		return nil, core.NewOptionConflictError(conflicts, provider)
	}

	parts := []*genai.Part{{Text: request.Prompt}}
	for _, image := range request.Images {
		parts = append(parts, &genai.Part{InlineData: &genai.Blob{Data: image.Data, MIMEType: image.MimeType}})
	}

	timeout := providerkit.ImageTimeout(request, c.client.config)
	generateConfig := &genai.GenerateContentConfig{
		HTTPOptions:        &genai.HTTPOptions{Timeout: &timeout},
		ResponseModalities: []string{string(genai.ModalityText), string(genai.ModalityImage)},
	}

	if request.AspectRatio != "" || request.ImageSize != "" {
		generateConfig.ImageConfig = &genai.ImageConfig{
			AspectRatio: request.AspectRatio,
			ImageSize:   request.ImageSize,
		}
	}

	if len(request.ProviderOptions) > 0 {
		generateConfig.HTTPOptions.ExtraBody = request.ProviderOptions
	}

	native, err := c.client.nativeClient(ctx)
	if err != nil {
		return nil, err
	}

	return &imagePlan{
		request:  request,
		native:   native,
		model:    model,
		traceID:  core.ResolveTraceID(request.TraceID, c.client.ids),
		contents: []*genai.Content{{Role: genai.RoleUser, Parts: parts}},
		config:   generateConfig,
	}, nil
}

// Generate 发起非流式图片生成，n 张时按 image_concurrency 库内循环。
func (c *ImageClient) Generate(ctx context.Context, request *core.ImageRequest) (*core.ImageResponse, error) {
	plan, err := c.prepare(ctx, request, false)
	if err != nil {
		return nil, err
	}

	callLogger := providerkit.NewCallLogger(c.client.logger, c.client.config.Name, plan.model, plan.traceID)
	responses, err := providerkit.RunImageLoop(ctx, request.ImageCount(), c.client.config.ImageConcurrency, func(ctx context.Context, index int) (*core.ImageResponse, error) {
		return c.generateOne(ctx, plan)
	})

	if err != nil {
		commonErr := core.AsError(err, c.client.config.Name)
		callLogger.LogFailure(ctx, commonErr)
		return nil, commonErr
	}

	response := providerkit.CombineImageResponses(responses)
	callLogger.LogSuccess(ctx, response.Usage)

	return response, nil
}

// generateOne 单次调用 generateContent，响应中没有图片时报 PROVIDER_ERROR
func (c *ImageClient) generateOne(ctx context.Context, plan *imagePlan) (*core.ImageResponse, error) {
	result, err := plan.native.Models.GenerateContent(ctx, plan.model, plan.contents, cloneConfig(plan.config))
	if err != nil {
		return nil, c.client.mapError(ctx, err)
	}

	images, text, finishReason := collectImageParts(result)
	if len(images) == 0 {
		return nil, c.noImageError(finishReason)
	}

	response := &core.ImageResponse{
		Provider: c.client.config.Name,
		Model:    core.NonEmptyStringPtr(result.ModelVersion),
		ID:       core.NonEmptyStringPtr(result.ResponseID),
		Images:   images,
		Text:     text,
		Usage:    mapUsage(result.UsageMetadata),
		TraceID:  plan.traceID,
	}

	if plan.request.IncludeRawResponse {
		raw, marshalErr := json.Marshal(result)
		if marshalErr == nil {
			response.RawResponse = raw
		}
	}

	return response, nil
}

// Stream 发起流式图片生成，Gemini 不产生部分图片，收到 inlineData 时发出 image_completed。
func (c *ImageClient) Stream(ctx context.Context, request *core.ImageRequest) iter.Seq[core.ImageEvent] {
	return func(yield func(core.ImageEvent) bool) {
		traceID := ""
		if request != nil {
			traceID = request.TraceID
		}

		builder := core.NewImageEventBuilder(c.client.config.Name, core.ResolveTraceID(traceID, c.client.ids))
		sink := &providerkit.ImageStreamSink{
			Builder: builder,
			Yield:   yield,
		}

		plan, err := c.prepare(ctx, request, true)
		if err != nil {
			sink.Fail(core.AsError(err, c.client.config.Name))
			return
		}

		plan.traceID = builder.TraceID()
		providerkit.StreamImageLoop(ctx, sink, request.ImageCount(), c.client.config.Name, func(ctx context.Context, sink *providerkit.ImageStreamSink) (*core.ImageResponse, error) {
			return c.streamOne(ctx, plan, sink)
		})
	}
}

// streamOne 单次调用 generateContentStream，第一个分块发 started
func (c *ImageClient) streamOne(ctx context.Context, plan *imagePlan, sink *providerkit.ImageStreamSink) (*core.ImageResponse, error) {
	var model *string
	var responseID *string
	images := []core.GeneratedImage{}
	var text strings.Builder
	finishReason := ""
	var lastUsage *genai.GenerateContentResponseUsageMetadata

	for chunk, chunkErr := range plan.native.Models.GenerateContentStream(ctx, plan.model, plan.contents, cloneConfig(plan.config)) {
		if chunkErr != nil {
			return nil, c.client.mapError(ctx, chunkErr)
		}

		if model == nil && responseID == nil {
			model = core.NonEmptyStringPtr(chunk.ModelVersion)
			responseID = core.NonEmptyStringPtr(chunk.ResponseID)
		}

		if !sink.Started(model, responseID) {
			return nil, nil
		}

		chunkImages, chunkText, chunkFinish := collectImageParts(chunk)
		text.WriteString(chunkText)

		for _, image := range chunkImages {
			if !sink.ImageCompleted(len(images), image) {
				return nil, nil
			}

			images = append(images, image)
		}

		if chunkFinish != "" {
			finishReason = chunkFinish
		}

		if chunk.UsageMetadata != nil {
			lastUsage = chunk.UsageMetadata
		}
	}

	if len(images) == 0 {
		return nil, c.noImageError(finishReason)
	}

	return &core.ImageResponse{
		Provider: c.client.config.Name,
		Model:    model,
		ID:       responseID,
		Images:   images,
		Text:     text.String(),
		Usage:    mapUsage(lastUsage),
		TraceID:  plan.traceID,
	}, nil
}

// cloneConfig 为每次调用复制配置，SDK 会写入 HTTPOptions.Headers，库内并发循环不能共用同一份
func cloneConfig(base *genai.GenerateContentConfig) *genai.GenerateContentConfig {
	copied := *base

	if base.HTTPOptions != nil {
		httpOptions := *base.HTTPOptions
		copied.HTTPOptions = &httpOptions
	}

	return &copied
}

// noImageError 响应中没有图片时的错误，vendorCode 为 finishReason
func (c *ImageClient) noImageError(finishReason string) *core.Error {
	return core.NewError(core.CategoryProviderError, "Gemini 响应中没有图片，finishReason："+finishReason, c.client.config.Name).WithVendorCode(finishReason)
}

// collectImageParts 提取首个候选中的图片与文本，跳过 thought 部分
func collectImageParts(response *genai.GenerateContentResponse) ([]core.GeneratedImage, string, string) {
	images := []core.GeneratedImage{}

	if response == nil || len(response.Candidates) == 0 || response.Candidates[0] == nil {
		return images, "", ""
	}

	candidate := response.Candidates[0]
	var text strings.Builder

	if candidate.Content != nil {
		for _, part := range candidate.Content.Parts {
			if part == nil || part.Thought {
				continue
			}

			if part.InlineData != nil {
				images = append(images, core.GeneratedImage{
					Data:     part.InlineData.Data,
					MimeType: core.FirstNonEmpty(part.InlineData.MIMEType, "image/png"),
				})
				continue
			}

			text.WriteString(part.Text)
		}
	}

	return images, text.String(), string(candidate.FinishReason)
}

// features 合并模型能力与图片生成能力
func (c *Client) features() []string {
	return append(slices.Clone(capabilityFeatures), imageFeatures...)
}
