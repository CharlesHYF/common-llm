// OpenAI 图片生成：Images API（generate / edit）与 Responses API image_generation 工具两条路径
// 创建日期：2026-11-16
// 修改日期：2026-11-16

package openai

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"iter"
	"net/http"
	"strings"

	sdk "github.com/openai/openai-go/v3"
	"github.com/openai/openai-go/v3/option"
	"github.com/openai/openai-go/v3/packages/ssestream"
	"github.com/openai/openai-go/v3/responses"

	"github.com/CharlesHYF/common-llm/golang/config"
	"github.com/CharlesHYF/common-llm/golang/core"
	"github.com/CharlesHYF/common-llm/golang/internal/providerkit"
)

// imagesMappedKeys Images API 中由适配器映射的请求字段，providerOptions 不得重名
var imagesMappedKeys = []string{
	"model",
	"prompt",
	"n",
	"size",
	"quality",
	"output_format",
	"output_compression",
	"background",
	"partial_images",
	"stream",
	"image",
	"mask",
}

// ImageClient OpenAI 图片生成客户端，复用 Client 的 SDK 客户端与错误映射，实现 core.ImageClient。
type ImageClient struct {
	client *Client
}

// Images 返回共用同一 SDK 客户端的图片生成客户端。
func (c *Client) Images() *ImageClient {
	return &ImageClient{client: c}
}

// imageCapabilityFeatures 按 image_api 返回图片生成能力，Responses 路径不支持原生多张
func imageCapabilityFeatures(imageAPI string) []string {
	features := []string{
		core.FeatureImageGeneration,
		core.FeatureImageEdit,
		core.FeatureImageMask,
		core.FeatureImageStreaming,
	}

	if imageAPI == config.ImageAPIImages {
		features = append(features, core.FeatureImageMultiple)
	}

	return features
}

// Supports 判断是否支持指定能力，未知能力返回 false。
func (c *ImageClient) Supports(feature string) bool {
	return c.client.Supports(feature)
}

// Capability 返回能力集合，与模型客户端一致。
func (c *ImageClient) Capability() core.ProviderCapability {
	return c.client.Capability()
}

// NativeClient 返回官方 SDK 客户端 openai.Client。
func (c *ImageClient) NativeClient() any {
	return c.client.native
}

// Close 图片客户端与模型客户端共用 SDK 客户端，没有单独需要释放的资源。
func (c *ImageClient) Close() error {
	return nil
}

// imagePlan 校验后的图片请求参数
type imagePlan struct {
	request      *core.ImageRequest
	imageModel   string
	chatModel    string
	traceID      string
	extraOptions map[string]any
}

// prepare 校验请求、按路径拒绝不支持的字段并检查 providerOptions 冲突
func (c *ImageClient) prepare(request *core.ImageRequest, isStream bool) (*imagePlan, error) {
	provider := c.client.config.Name

	if err := core.ValidateImageRequest(request, provider); err != nil {
		return nil, err
	}

	imageModel, err := providerkit.ResolveImageModel(request, c.client.config)
	if err != nil {
		return nil, err
	}

	if err := providerkit.RejectPartialImagesOnGenerate(request, isStream, provider); err != nil {
		return nil, err
	}

	unsupported := []string{"aspectRatio", "imageSize"}

	mappedKeys := imagesMappedKeys
	path := "OpenAI Images API"

	if c.client.config.ImageAPI == config.ImageAPIResponses {
		mappedKeys = responsesMappedKeys
		path = "OpenAI Responses image_generation"
	} else {
		unsupported = append(unsupported, "imageUrl")

		if len(request.Images) > 16 {
			return nil, core.NewError(core.CategoryConfigInvalid, "OpenAI Images API 最多接受 16 张输入图片", provider)
		}

		if request.ImageCount() > 10 {
			return nil, core.NewError(core.CategoryConfigInvalid, "OpenAI Images API 的 n 取值为 1 到 10", provider)
		}
	}

	if err := providerkit.RejectUnsupportedImageFields(request, unsupported, path, provider); err != nil {
		return nil, err
	}

	conflicts := core.FindConflictingKeys(request.ProviderOptions, mappedKeys)
	if len(conflicts) > 0 {
		return nil, core.NewOptionConflictError(conflicts, provider)
	}

	plan := &imagePlan{
		request:      request,
		imageModel:   imageModel,
		traceID:      core.ResolveTraceID(request.TraceID, c.client.ids),
		extraOptions: request.ProviderOptions,
	}

	if c.client.config.ImageAPI == config.ImageAPIResponses {
		plan.chatModel = c.client.config.DefaultModel

		if plan.chatModel == "" {
			return nil, core.NewError(core.CategoryConfigInvalid, "image_api=responses 需要配置 default_model 作为对话模型", provider)
		}
	}

	return plan, nil
}

// callOptions 组装单次调用的请求选项，httpResult 用于读取请求 ID
func (c *ImageClient) callOptions(plan *imagePlan, httpResult **http.Response, isJSONBody bool) []option.RequestOption {
	options := []option.RequestOption{
		option.WithRequestTimeout(providerkit.ImageTimeout(plan.request, c.client.config)),
		option.WithResponseInto(httpResult),
	}

	if !isJSONBody {
		return options
	}

	for _, key := range providerkit.SortedKeys(plan.extraOptions) {
		options = append(options, option.WithJSONSet(providerkit.EscapeJSONPathKey(key), plan.extraOptions[key]))
	}

	return options
}

// Generate 发起非流式图片生成；Images API 原生支持 n，Responses 路径按 image_concurrency 库内循环。
func (c *ImageClient) Generate(ctx context.Context, request *core.ImageRequest) (*core.ImageResponse, error) {
	plan, err := c.prepare(request, false)
	if err != nil {
		return nil, err
	}

	callLogger := providerkit.NewCallLogger(c.client.logger, c.client.config.Name, plan.imageModel, plan.traceID)
	var response *core.ImageResponse

	if c.client.config.ImageAPI == config.ImageAPIResponses {
		var responses []*core.ImageResponse
		responses, err = providerkit.RunImageLoop(ctx, request.ImageCount(), c.client.config.ImageConcurrency, func(ctx context.Context, index int) (*core.ImageResponse, error) {
			return c.generateWithResponses(ctx, plan)
		})

		if err == nil {
			response = providerkit.CombineImageResponses(responses)
		}
	} else {
		response, err = c.generateWithImages(ctx, plan)
	}

	if err != nil {
		commonErr := core.AsError(err, c.client.config.Name)
		callLogger.LogFailure(ctx, commonErr)
		return nil, commonErr
	}

	callLogger.LogSuccess(ctx, response.Usage)
	return response, nil
}

// Stream 发起流式图片生成，失败（含预检失败）以唯一的 error 终止事件返回。
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

		plan, err := c.prepare(request, true)
		if err != nil {
			sink.Fail(core.AsError(err, c.client.config.Name))
			return
		}

		plan.traceID = builder.TraceID()

		if c.client.config.ImageAPI == config.ImageAPIResponses {
			providerkit.StreamImageLoop(ctx, sink, request.ImageCount(), c.client.config.Name, func(ctx context.Context, sink *providerkit.ImageStreamSink) (*core.ImageResponse, error) {
				return c.streamWithResponses(ctx, plan, sink)
			})
			return
		}

		providerkit.StreamImageLoop(ctx, sink, 1, c.client.config.Name, func(ctx context.Context, sink *providerkit.ImageStreamSink) (*core.ImageResponse, error) {
			return c.streamWithImages(ctx, plan, sink)
		})
	}
}

// buildImageFiles 把输入图片转换为带文件名与 MIME 类型的上传文件
func buildImageFiles(images []core.ImageInput) []io.Reader {
	files := []io.Reader{}

	for index, image := range images {
		files = append(files, buildImageFile(image, fmt.Sprintf("image-%d", index)))
	}

	return files
}

// buildImageFile 按 MIME 类型生成文件名后缀，SDK 以 multipart 上传
func buildImageFile(image core.ImageInput, baseName string) io.Reader {
	extension := strings.TrimPrefix(image.MimeType, "image/")
	return sdk.File(bytes.NewReader(image.Data), baseName+"."+extension, image.MimeType)
}

// generateWithImages 无输入图片时调用 images.generate，有输入图片时调用 images.edit
func (c *ImageClient) generateWithImages(ctx context.Context, plan *imagePlan) (*core.ImageResponse, error) {
	var httpResult *http.Response
	options := c.callOptions(plan, &httpResult, false)
	var result *sdk.ImagesResponse
	var err error

	if len(plan.request.Images) == 0 {
		result, err = c.client.native.Images.Generate(ctx, buildImageGenerateParams(plan, false), options...)
	} else {
		result, err = c.client.native.Images.Edit(ctx, buildImageEditParams(plan, false), options...)
	}

	if err != nil {
		return nil, c.client.mapError(ctx, err)
	}

	return c.mapImagesResponse(plan, result, readRequestID(httpResult)), nil
}

// buildImageGenerateParams 映射 images.generate 参数，providerOptions 经 SetExtraFields 透传
func buildImageGenerateParams(plan *imagePlan, isStream bool) sdk.ImageGenerateParams {
	request := plan.request
	params := sdk.ImageGenerateParams{
		Prompt:       request.Prompt,
		Model:        plan.imageModel,
		Size:         sdk.ImageGenerateParamsSize(request.Size),
		Quality:      sdk.ImageGenerateParamsQuality(request.Quality),
		OutputFormat: sdk.ImageGenerateParamsOutputFormat(request.OutputFormat),
		Background:   sdk.ImageGenerateParamsBackground(request.Background),
	}

	if request.N != nil {
		params.N = sdk.Int(int64(*request.N))
	}

	if request.OutputCompression != nil {
		params.OutputCompression = sdk.Int(int64(*request.OutputCompression))
	}

	if isStream && request.PartialImages != nil {
		params.PartialImages = sdk.Int(int64(*request.PartialImages))
	}

	if len(plan.extraOptions) > 0 {
		params.SetExtraFields(plan.extraOptions)
	}

	return params
}

// buildImageEditParams 映射 images.edit 参数，单张输入传单个文件，多张传文件数组
func buildImageEditParams(plan *imagePlan, isStream bool) sdk.ImageEditParams {
	request := plan.request
	params := sdk.ImageEditParams{
		Prompt:       request.Prompt,
		Model:        plan.imageModel,
		Size:         sdk.ImageEditParamsSize(request.Size),
		Quality:      sdk.ImageEditParamsQuality(request.Quality),
		OutputFormat: sdk.ImageEditParamsOutputFormat(request.OutputFormat),
		Background:   sdk.ImageEditParamsBackground(request.Background),
	}

	files := buildImageFiles(request.Images)
	if len(files) == 1 {
		params.Image = sdk.ImageEditParamsImageUnion{OfFile: files[0]}
	} else {
		params.Image = sdk.ImageEditParamsImageUnion{OfFileArray: files}
	}

	if request.Mask != nil {
		params.Mask = buildImageFile(*request.Mask, "mask")
	}

	if request.N != nil {
		params.N = sdk.Int(int64(*request.N))
	}

	if request.OutputCompression != nil {
		params.OutputCompression = sdk.Int(int64(*request.OutputCompression))
	}

	if isStream && request.PartialImages != nil {
		params.PartialImages = sdk.Int(int64(*request.PartialImages))
	}

	if len(plan.extraOptions) > 0 {
		params.SetExtraFields(plan.extraOptions)
	}

	return params
}

// mapImagesResponse 映射 Images API 响应，MIME 由 output_format 推出，用量只有输入、输出与总量
func (c *ImageClient) mapImagesResponse(plan *imagePlan, result *sdk.ImagesResponse, requestID *string) *core.ImageResponse {
	mimeType := providerkit.MimeTypeFromFormat(string(result.OutputFormat))
	images := []core.GeneratedImage{}

	for _, item := range result.Data {
		images = append(images, core.GeneratedImage{
			Data:          decodeImageData(item.B64JSON),
			MimeType:      mimeType,
			URL:           core.NonEmptyStringPtr(item.URL),
			RevisedPrompt: core.NonEmptyStringPtr(item.RevisedPrompt),
		})
	}

	usage := core.Usage{}
	if result.JSON.Usage.Valid() {
		usage = core.Usage{
			InputTokens:  validCount(result.Usage.JSON.InputTokens.Valid(), result.Usage.InputTokens),
			OutputTokens: validCount(result.Usage.JSON.OutputTokens.Valid(), result.Usage.OutputTokens),
			TotalTokens:  validCount(result.Usage.JSON.TotalTokens.Valid(), result.Usage.TotalTokens),
		}
	}

	response := &core.ImageResponse{
		Provider:  c.client.config.Name,
		Model:     core.StringPtr(plan.imageModel),
		Images:    images,
		Usage:     usage,
		TraceID:   plan.traceID,
		RequestID: requestID,
	}

	if plan.request.IncludeRawResponse {
		response.RawResponse = json.RawMessage(result.RawJSON())
	}

	return response
}

// imageStreamEvent Images API 生成与编辑两种流式事件的公共字段
type imageStreamEvent struct {
	eventType         string
	b64JSON           string
	outputFormat      string
	partialImageIndex int64
	hasUsage          bool
	usage             core.Usage
}

// streamWithImages 调用 images.generate 或 images.edit 的流式版本
func (c *ImageClient) streamWithImages(ctx context.Context, plan *imagePlan, sink *providerkit.ImageStreamSink) (*core.ImageResponse, error) {
	var httpResult *http.Response
	options := c.callOptions(plan, &httpResult, false)

	if len(plan.request.Images) == 0 {
		stream := c.client.native.Images.GenerateStreaming(ctx, buildImageGenerateParams(plan, true), options...)
		return consumeImageStream(ctx, c, plan, stream, &httpResult, sink, func(event sdk.ImageGenStreamEventUnion) imageStreamEvent {
			return imageStreamEvent{
				eventType:         event.Type,
				b64JSON:           event.B64JSON,
				outputFormat:      event.OutputFormat,
				partialImageIndex: event.PartialImageIndex,
				hasUsage:          event.JSON.Usage.Valid(),
				usage: core.Usage{
					InputTokens:  validCount(event.Usage.JSON.InputTokens.Valid(), event.Usage.InputTokens),
					OutputTokens: validCount(event.Usage.JSON.OutputTokens.Valid(), event.Usage.OutputTokens),
					TotalTokens:  validCount(event.Usage.JSON.TotalTokens.Valid(), event.Usage.TotalTokens),
				},
			}
		})
	}

	stream := c.client.native.Images.EditStreaming(ctx, buildImageEditParams(plan, true), options...)
	return consumeImageStream(ctx, c, plan, stream, &httpResult, sink, func(event sdk.ImageEditStreamEventUnion) imageStreamEvent {
		return imageStreamEvent{
			eventType:         event.Type,
			b64JSON:           event.B64JSON,
			outputFormat:      event.OutputFormat,
			partialImageIndex: event.PartialImageIndex,
			hasUsage:          event.JSON.Usage.Valid(),
			usage: core.Usage{
				InputTokens:  validCount(event.Usage.JSON.InputTokens.Valid(), event.Usage.InputTokens),
				OutputTokens: validCount(event.Usage.JSON.OutputTokens.Valid(), event.Usage.OutputTokens),
				TotalTokens:  validCount(event.Usage.JSON.TotalTokens.Valid(), event.Usage.TotalTokens),
			},
		}
	})
}

// consumeImageStream 映射 *.partial_image 与 *.completed 事件，completed 的 usage 作为最终用量
func consumeImageStream[T any](ctx context.Context, c *ImageClient, plan *imagePlan, stream *ssestream.Stream[T], httpResult **http.Response, sink *providerkit.ImageStreamSink, convert func(T) imageStreamEvent) (*core.ImageResponse, error) {
	defer stream.Close()

	images := []core.GeneratedImage{}
	usage := core.Usage{}

	for stream.Next() {
		event := convert(stream.Current())

		if !sink.Started(core.StringPtr(plan.imageModel), nil) {
			return nil, nil
		}

		mimeType := providerkit.MimeTypeFromFormat(event.outputFormat)

		if strings.HasSuffix(event.eventType, ".partial_image") {
			if !sink.PartialImage(len(images), int(event.partialImageIndex), decodeImageData(event.b64JSON), mimeType) {
				return nil, nil
			}

			continue
		}

		if strings.HasSuffix(event.eventType, ".completed") {
			image := core.GeneratedImage{
				Data:     decodeImageData(event.b64JSON),
				MimeType: mimeType,
			}

			if !sink.ImageCompleted(len(images), image) {
				return nil, nil
			}

			images = append(images, image)

			if event.hasUsage {
				usage = event.usage
			}
		}
	}

	if err := stream.Err(); err != nil {
		return nil, c.client.mapError(ctx, err)
	}

	return &core.ImageResponse{
		Provider:  c.client.config.Name,
		Model:     core.StringPtr(plan.imageModel),
		Images:    images,
		Usage:     usage,
		TraceID:   plan.traceID,
		RequestID: readRequestID(*httpResult),
	}, nil
}

// buildResponsesImageParams 组装 Responses 请求：对话模型调用 image_generation 工具并强制使用该工具
func buildResponsesImageParams(plan *imagePlan, isStream bool) responses.ResponseNewParams {
	request := plan.request
	contents := responses.ResponseInputMessageContentListParam{
		{OfInputText: &responses.ResponseInputTextParam{Text: request.Prompt}},
	}

	for _, image := range request.Images {
		contents = append(contents, responses.ResponseInputContentUnionParam{
			OfInputImage: &responses.ResponseInputImageParam{
				ImageURL: sdk.String(buildInputImageURL(image)),
				Detail:   responses.ResponseInputImageDetailAuto,
			},
		})
	}

	tool := responses.ToolImageGenerationParam{
		Model:        plan.imageModel,
		Size:         request.Size,
		Quality:      request.Quality,
		OutputFormat: request.OutputFormat,
		Background:   request.Background,
	}

	if request.OutputCompression != nil {
		tool.OutputCompression = sdk.Int(int64(*request.OutputCompression))
	}

	if isStream && request.PartialImages != nil {
		tool.PartialImages = sdk.Int(int64(*request.PartialImages))
	}

	if request.Mask != nil {
		tool.InputImageMask = responses.ToolImageGenerationInputImageMaskParam{ImageURL: sdk.String(buildInputImageURL(*request.Mask))}
	}

	return responses.ResponseNewParams{
		Model: plan.chatModel,
		Input: responses.ResponseNewParamsInputUnion{OfInputItemList: responses.ResponseInputParam{
			{OfMessage: &responses.EasyInputMessageParam{
				Role:    responses.EasyInputMessageRoleUser,
				Content: responses.EasyInputMessageContentUnionParam{OfInputItemContentList: contents},
			}},
		}},
		Tools:      []responses.ToolUnionParam{{OfImageGeneration: &tool}},
		ToolChoice: responses.ResponseNewParamsToolChoiceUnion{OfHostedTool: &responses.ToolChoiceTypesParam{Type: responses.ToolChoiceTypesTypeImageGeneration}},
	}
}

// buildInputImageURL URL 原样使用，图片字节转换为 base64 data URL
func buildInputImageURL(image core.ImageInput) string {
	if image.URL != "" {
		return image.URL
	}

	return "data:" + image.MimeType + ";base64," + base64.StdEncoding.EncodeToString(image.Data)
}

// generateWithResponses 单次调用 responses.create 生成一张图片
func (c *ImageClient) generateWithResponses(ctx context.Context, plan *imagePlan) (*core.ImageResponse, error) {
	var httpResult *http.Response
	result, err := c.client.native.Responses.New(ctx, buildResponsesImageParams(plan, false), c.callOptions(plan, &httpResult, true)...)

	if err != nil {
		return nil, c.client.mapError(ctx, err)
	}

	return c.mapResponsesImage(plan, result, readRequestID(httpResult)), nil
}

// mapResponsesImage 映射 image_generation_call 结果与文本输出，用量按 Responses 规则映射
func (c *ImageClient) mapResponsesImage(plan *imagePlan, result *responses.Response, requestID *string) *core.ImageResponse {
	images := []core.GeneratedImage{}
	var text strings.Builder

	for _, item := range result.Output {
		if item.Type == "image_generation_call" && item.Result != "" {
			images = append(images, core.GeneratedImage{
				Data:          decodeImageData(item.Result),
				MimeType:      providerkit.MimeTypeFromFormat(item.OutputFormat),
				RevisedPrompt: core.NonEmptyStringPtr(item.RevisedPrompt),
			})
			continue
		}

		if item.Type != "message" {
			continue
		}

		for _, content := range item.Content {
			if content.Type == "output_text" {
				text.WriteString(content.Text)
			}
		}
	}

	response := &core.ImageResponse{
		Provider:  c.client.config.Name,
		Model:     core.StringPtr(plan.imageModel),
		ID:        core.NonEmptyStringPtr(result.ID),
		Images:    images,
		Text:      text.String(),
		Usage:     mapResponsesUsage(result),
		TraceID:   plan.traceID,
		RequestID: requestID,
	}

	if plan.request.IncludeRawResponse {
		response.RawResponse = json.RawMessage(result.RawJSON())
	}

	return response
}

// streamWithResponses 单次流式调用 responses.create，部分图片与完成的图片按 image_generation_call 出现顺序编号
func (c *ImageClient) streamWithResponses(ctx context.Context, plan *imagePlan, sink *providerkit.ImageStreamSink) (*core.ImageResponse, error) {
	var httpResult *http.Response
	stream := c.client.native.Responses.NewStreaming(ctx, buildResponsesImageParams(plan, true), c.callOptions(plan, &httpResult, true)...)
	defer stream.Close()

	imageIndexes := map[string]int{}

	for stream.Next() {
		event := stream.Current()

		switch event.Type {
		case "response.created":
			if !sink.Started(core.StringPtr(plan.imageModel), core.NonEmptyStringPtr(event.Response.ID)) {
				return nil, nil
			}
		case "response.output_item.added":
			if event.Item.Type == "image_generation_call" {
				imageIndexes[event.Item.ID] = len(imageIndexes)
			}
		case "response.image_generation_call.partial_image":
			if !sink.PartialImage(imageIndexes[event.ItemID], int(event.PartialImageIndex), decodeImageData(event.PartialImageB64), providerkit.MimeTypeFromFormat(event.OutputFormat)) {
				return nil, nil
			}
		case "response.output_item.done":
			if event.Item.Type != "image_generation_call" {
				continue
			}

			image := core.GeneratedImage{
				Data:          decodeImageData(event.Item.Result),
				MimeType:      providerkit.MimeTypeFromFormat(event.Item.OutputFormat),
				RevisedPrompt: core.NonEmptyStringPtr(event.Item.RevisedPrompt),
			}

			if !sink.ImageCompleted(imageIndexes[event.Item.ID], image) {
				return nil, nil
			}
		case "response.completed":
			response := c.mapResponsesImage(plan, &event.Response, readRequestID(httpResult))
			// 流式不提供 rawResponse
			response.RawResponse = nil

			return response, nil
		case "response.failed", "error":
			return nil, core.NewError(core.CategoryProviderError, core.FirstNonEmpty(event.Message, event.Response.Error.Message, "OpenAI 图片流式响应失败"), c.client.config.Name).WithVendorCode(core.FirstNonEmpty(event.Code, string(event.Response.Error.Code)))
		}
	}

	if err := stream.Err(); err != nil {
		return nil, c.client.mapError(ctx, err)
	}

	return nil, core.NewError(core.CategoryProviderError, "流在收到 response.completed 前结束", c.client.config.Name)
}

// decodeImageData 解码 base64 图片，空字符串或非法内容返回空切片
func decodeImageData(encoded string) []byte {
	decoded, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		return []byte{}
	}

	return decoded
}

// readRequestID 读取响应头中的厂商请求 ID
func readRequestID(httpResult *http.Response) *string {
	if httpResult == nil {
		return nil
	}

	return core.NonEmptyStringPtr(httpResult.Header.Get(requestIDHeader))
}
