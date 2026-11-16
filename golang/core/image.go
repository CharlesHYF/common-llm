// 图片生成的请求、输入图片、响应、事件与 ImageClient 接口
// 创建日期：2026-11-16
// 修改日期：2026-11-16

package core

import (
	"context"
	"encoding/json"
	"fmt"
	"iter"
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"strings"
)

// 图片生成能力名称
const (
	FeatureImageGeneration = "image_generation"
	FeatureImageEdit       = "image_edit"
	FeatureImageMask       = "image_mask"
	FeatureImageStreaming  = "image_streaming"
	FeatureImageMultiple   = "image_multiple"
)

// ImageEventType 图片流式事件类型。
type ImageEventType string

const (
	ImageEventStarted        ImageEventType = "started"
	ImageEventPartialImage   ImageEventType = "partial_image"
	ImageEventImageCompleted ImageEventType = "image_completed"
	ImageEventUsage          ImageEventType = "usage"
	ImageEventCompleted      ImageEventType = "completed"
	ImageEventError          ImageEventType = "error"
)

// ImageInput 输入图片，提供 Data 与 MimeType，或只提供 URL（仅 OpenAI Responses 路径支持 URL）。
type ImageInput struct {
	Data     []byte `json:"base64,omitempty"`
	MimeType string `json:"mimeType,omitempty"`
	URL      string `json:"url,omitempty"`
}

// ImageFromBytes 由图片字节创建输入图片，mimeType 为空时按内容识别。
func ImageFromBytes(data []byte, mimeType string) ImageInput {
	if mimeType == "" {
		mimeType = http.DetectContentType(data)
	}

	return ImageInput{
		Data:     data,
		MimeType: mimeType,
	}
}

// ImageFromFile 读取图片文件创建输入图片，MIME 类型先按扩展名、再按内容识别。
func ImageFromFile(path string) (ImageInput, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return ImageInput{}, WrapError(CategoryConfigInvalid, "读取图片文件失败："+path, "", err)
	}

	mimeType := mime.TypeByExtension(strings.ToLower(filepath.Ext(path)))
	if !strings.HasPrefix(mimeType, "image/") {
		mimeType = ""
	}

	return ImageFromBytes(data, mimeType), nil
}

// ImageFromURL 由图片 URL 创建输入图片，本库不代为下载。
func ImageFromURL(imageURL string) ImageInput {
	return ImageInput{URL: imageURL}
}

// ImageRequest 统一的图片生成请求。
type ImageRequest struct {
	Provider           string            `json:"provider"`
	Model              string            `json:"model,omitempty"`
	Prompt             string            `json:"prompt"`
	Images             []ImageInput      `json:"images,omitempty"`
	Mask               *ImageInput       `json:"mask,omitempty"`
	N                  *int              `json:"n,omitempty"`
	Size               string            `json:"size,omitempty"`
	AspectRatio        string            `json:"aspectRatio,omitempty"`
	ImageSize          string            `json:"imageSize,omitempty"`
	Quality            string            `json:"quality,omitempty"`
	OutputFormat       string            `json:"outputFormat,omitempty"`
	OutputCompression  *int              `json:"outputCompression,omitempty"`
	Background         string            `json:"background,omitempty"`
	PartialImages      *int              `json:"partialImages,omitempty"`
	ProviderOptions    map[string]any    `json:"providerOptions,omitempty"`
	TimeoutSeconds     *float64          `json:"timeoutSeconds,omitempty"`
	IncludeRawResponse bool              `json:"includeRawResponse,omitempty"`
	TraceID            string            `json:"traceId,omitempty"`
	RequestID          string            `json:"requestId,omitempty"`
	Metadata           map[string]string `json:"metadata,omitempty"`
}

// ImageCount 返回请求的图片张数，未设置时为 1。
func (r *ImageRequest) ImageCount() int {
	if r.N == nil {
		return 1
	}

	return *r.N
}

// GeneratedImage 生成的一张图片，序列化时 data 为 base64。
type GeneratedImage struct {
	Data          []byte  `json:"data"`
	MimeType      string  `json:"mimeType"`
	URL           *string `json:"url"`
	RevisedPrompt *string `json:"revisedPrompt"`
}

// ImageResponse 统一的图片生成响应。
type ImageResponse struct {
	Provider    string           `json:"provider"`
	Model       *string          `json:"model"`
	ID          *string          `json:"id"`
	Images      []GeneratedImage `json:"images"`
	Text        string           `json:"text"`
	Usage       Usage            `json:"usage"`
	TraceID     string           `json:"traceId"`
	RequestID   *string          `json:"requestId"`
	RawResponse json.RawMessage  `json:"rawResponse,omitempty"`
}

// ImageEvent 图片流式事件，按 Type 决定哪些字段有效。
type ImageEvent struct {
	Type         ImageEventType
	TraceID      string
	Sequence     int
	Provider     string
	Model        *string
	ID           *string
	ImageIndex   int
	PartialIndex int
	Data         []byte
	MimeType     string
	Image        *GeneratedImage
	Usage        *Usage
	Response     *ImageResponse
	Error        *ErrorInfo
}

// IsTerminal 判断事件是否为终止事件。
func (e ImageEvent) IsTerminal() bool {
	return e.Type == ImageEventCompleted || e.Type == ImageEventError
}

// MarshalJSON 按事件类型输出契约字段。
func (e ImageEvent) MarshalJSON() ([]byte, error) {
	fields := map[string]any{
		"type":     e.Type,
		"traceId":  e.TraceID,
		"sequence": e.Sequence,
		"provider": e.Provider,
	}

	switch e.Type {
	case ImageEventStarted:
		fields["model"] = e.Model
		fields["id"] = e.ID
	case ImageEventPartialImage:
		fields["imageIndex"] = e.ImageIndex
		fields["partialIndex"] = e.PartialIndex
		fields["data"] = e.Data
		fields["mimeType"] = e.MimeType
	case ImageEventImageCompleted:
		fields["imageIndex"] = e.ImageIndex
		fields["image"] = e.Image
	case ImageEventUsage:
		fields["usage"] = e.Usage
	case ImageEventCompleted:
		fields["response"] = e.Response
	case ImageEventError:
		fields["error"] = e.Error
	}

	return json.Marshal(fields)
}

// ImageEventBuilder 为同一个流组装事件，统一填写 traceId、provider 与递增序号。
type ImageEventBuilder struct {
	provider     string
	traceID      string
	nextSequence int
}

// NewImageEventBuilder 创建事件组装器，序号从 0 开始。
func NewImageEventBuilder(provider string, traceID string) *ImageEventBuilder {
	return &ImageEventBuilder{
		provider: provider,
		traceID:  traceID,
	}
}

// TraceID 返回本流的 traceId。
func (b *ImageEventBuilder) TraceID() string {
	return b.traceID
}

// NewEvent 创建带公共字段的事件并递增序号，调用方再填写类型相关字段。
func (b *ImageEventBuilder) NewEvent(eventType ImageEventType) ImageEvent {
	event := ImageEvent{
		Type:     eventType,
		TraceID:  b.traceID,
		Sequence: b.nextSequence,
		Provider: b.provider,
	}
	b.nextSequence++

	return event
}

// ErrorEvent 组装 error 终止事件。
func (b *ImageEventBuilder) ErrorEvent(err *Error) ImageEvent {
	event := b.NewEvent(ImageEventError)
	info := err.Info
	event.Error = &info

	return event
}

// ImageClient 单个 Provider 实例的图片生成客户端。
type ImageClient interface {
	// Generate 发起非流式图片生成，失败返回 *Error。
	Generate(ctx context.Context, request *ImageRequest) (*ImageResponse, error)
	// Stream 发起流式图片生成，失败（含预检失败）以唯一的 error 终止事件返回。
	Stream(ctx context.Context, request *ImageRequest) iter.Seq[ImageEvent]
	// Supports 判断是否支持指定能力。
	Supports(feature string) bool
	// Capability 返回能力集合。
	Capability() ProviderCapability
	// NativeClient 返回底层官方 SDK 客户端。
	NativeClient() any
	// Close 释放客户端资源。
	Close() error
}

// ValidateImageRequest 校验图片请求的必填字段、取值范围与输入图片。
func ValidateImageRequest(request *ImageRequest, provider string) error {
	if request == nil {
		return NewError(CategoryConfigInvalid, "ImageRequest 不能为空", provider)
	}

	if strings.TrimSpace(request.Prompt) == "" {
		return NewError(CategoryConfigInvalid, "prompt 不能为空", provider)
	}

	if request.N != nil && *request.N < 1 {
		return NewError(CategoryConfigInvalid, "n 不能小于 1", provider)
	}

	if request.OutputCompression != nil && (*request.OutputCompression < 0 || *request.OutputCompression > 100) {
		return NewError(CategoryConfigInvalid, "outputCompression 必须在 0 到 100 之间", provider)
	}

	if request.PartialImages != nil && *request.PartialImages < 0 {
		return NewError(CategoryConfigInvalid, "partialImages 不能小于 0", provider)
	}

	if request.TimeoutSeconds != nil && *request.TimeoutSeconds <= 0 {
		return NewError(CategoryConfigInvalid, "timeoutSeconds 必须大于 0", provider)
	}

	if request.Mask != nil && len(request.Images) == 0 {
		return NewError(CategoryConfigInvalid, "mask 需要同时提供 images", provider)
	}

	for index, image := range request.Images {
		if err := validateImageInput(image, fmt.Sprintf("images[%d]", index), provider); err != nil {
			return err
		}
	}

	if request.Mask != nil {
		return validateImageInput(*request.Mask, "mask", provider)
	}

	return nil
}

// validateImageInput 校验输入图片：data 与 url 二选一，data 必须能确定图片 MIME 类型
func validateImageInput(image ImageInput, field string, provider string) error {
	hasData := len(image.Data) > 0
	hasURL := image.URL != ""

	if hasData == hasURL {
		return NewError(CategoryConfigInvalid, field+" 必须且只能提供图片数据或 url 之一", provider)
	}

	if hasData && !strings.HasPrefix(image.MimeType, "image/") {
		return NewError(CategoryConfigInvalid, field+" 无法识别图片 MIME 类型："+image.MimeType, provider)
	}

	return nil
}
