// 图片生成适配器共用的超时与模型解析、MIME 推断与 n 张库内循环
// 创建日期：2026-11-16
// 修改日期：2026-11-16

package providerkit

import (
	"context"
	"encoding/json"
	"strings"
	"sync"
	"time"

	"github.com/CharlesHYF/common-llm/golang/config"
	"github.com/CharlesHYF/common-llm/golang/core"
)

// ImageTimeout 请求 timeoutSeconds 优先，其次配置 image_timeout_seconds。
func ImageTimeout(request *core.ImageRequest, providerConfig config.ProviderConfig) time.Duration {
	if request.TimeoutSeconds != nil {
		return secondsToDuration(*request.TimeoutSeconds)
	}

	return secondsToDuration(providerConfig.ImageTimeoutSeconds)
}

// ResolveImageModel 请求 model 优先，其次配置 default_image_model，两者都没有报 CONFIG_INVALID。
func ResolveImageModel(request *core.ImageRequest, providerConfig config.ProviderConfig) (string, error) {
	model := core.FirstNonEmpty(request.Model, providerConfig.DefaultImageModel)

	if model == "" {
		return "", core.NewError(core.CategoryConfigInvalid, "未指定图片模型：请求 model 与配置 default_image_model 均为空", providerConfig.Name)
	}

	return model, nil
}

// MimeTypeFromFormat 按 output_format 推出 MIME 类型，缺省为 image/png。
func MimeTypeFromFormat(outputFormat string) string {
	switch strings.ToLower(outputFormat) {
	case "":
		return "image/png"
	case "jpg", "jpeg":
		return "image/jpeg"
	default:
		return "image/" + strings.ToLower(outputFormat)
	}
}

// RejectUnsupportedImageFields 请求设置了所选路径不支持的字段时报 SDK_UNSUPPORTED_FEATURE，不静默忽略。
func RejectUnsupportedImageFields(request *core.ImageRequest, fieldNames []string, path string, provider string) error {
	unsupported := []string{}

	for _, fieldName := range fieldNames {
		if isImageFieldSet(request, fieldName) {
			unsupported = append(unsupported, fieldName)
		}
	}

	if len(unsupported) == 0 {
		return nil
	}

	return core.NewError(core.CategorySDKUnsupportedFeature, path+" 不支持字段："+strings.Join(unsupported, ", "), provider)
}

// RejectPartialImagesOnGenerate 非流式调用设置了 partialImages（含 0）时报 SDK_UNSUPPORTED_FEATURE
func RejectPartialImagesOnGenerate(request *core.ImageRequest, isStream bool, provider string) error {
	if isStream || request.PartialImages == nil {
		return nil
	}

	return core.NewError(core.CategorySDKUnsupportedFeature, "partialImages 只用于流式图片生成", provider)
}

// isImageFieldSet 判断请求是否设置了指定字段，imageUrl 表示任一输入图片使用了 URL
func isImageFieldSet(request *core.ImageRequest, fieldName string) bool {
	switch fieldName {
	case "size":
		return request.Size != ""
	case "aspectRatio":
		return request.AspectRatio != ""
	case "imageSize":
		return request.ImageSize != ""
	case "quality":
		return request.Quality != ""
	case "outputFormat":
		return request.OutputFormat != ""
	case "outputCompression":
		return request.OutputCompression != nil
	case "background":
		return request.Background != ""
	case "mask":
		return request.Mask != nil
	case "partialImages":
		return request.PartialImages != nil && *request.PartialImages > 0
	case "imageUrl":
		for _, image := range request.Images {
			if image.URL != "" {
				return true
			}
		}

		return request.Mask != nil && request.Mask.URL != ""
	default:
		return false
	}
}

// RunImageLoop 库内循环 count 次单张生成：按 concurrency 并发，任一次失败即取消其余请求、不再发起后续请求并返回该次错误，结果按调用顺序排列。
func RunImageLoop(ctx context.Context, count int, concurrency int, generateOne func(ctx context.Context, index int) (*core.ImageResponse, error)) ([]*core.ImageResponse, error) {
	loopContext, cancel := context.WithCancel(ctx)
	defer cancel()

	if concurrency < 1 {
		concurrency = 1
	}

	results := make([]*core.ImageResponse, count)
	slots := make(chan struct{}, concurrency)
	var mutex sync.Mutex
	var firstErr error
	var waitGroup sync.WaitGroup

	for index := 0; index < count; index++ {
		slots <- struct{}{}

		mutex.Lock()
		hasFailed := firstErr != nil
		mutex.Unlock()

		if hasFailed {
			<-slots
			break
		}

		waitGroup.Add(1)
		go func(index int) {
			defer waitGroup.Done()
			defer func() {
				<-slots
			}()

			response, err := generateOne(loopContext, index)

			mutex.Lock()
			defer mutex.Unlock()

			if err != nil {
				if firstErr == nil {
					firstErr = err
					cancel()
				}

				return
			}

			results[index] = response
		}(index)
	}

	waitGroup.Wait()

	if firstErr != nil {
		return nil, firstErr
	}

	return results, nil
}

// CombineImageResponses 合并循环结果：图片按调用顺序拼接，用量逐项求和（任一次为 nil 则为 nil），文字按换行连接，id、model 与 requestId 取最后一次。
func CombineImageResponses(responses []*core.ImageResponse) *core.ImageResponse {
	if len(responses) == 1 {
		return responses[0]
	}

	last := responses[len(responses)-1]
	combined := &core.ImageResponse{
		Provider:  last.Provider,
		Model:     last.Model,
		ID:        last.ID,
		Images:    []core.GeneratedImage{},
		TraceID:   last.TraceID,
		RequestID: last.RequestID,
		Usage: core.Usage{
			InputTokens:       core.Int64Ptr(0),
			OutputTokens:      core.Int64Ptr(0),
			CachedInputTokens: core.Int64Ptr(0),
			ReasoningTokens:   core.Int64Ptr(0),
			TotalTokens:       core.Int64Ptr(0),
		},
	}

	texts := []string{}
	raws := []json.RawMessage{}

	for _, response := range responses {
		combined.Images = append(combined.Images, response.Images...)

		if response.Text != "" {
			texts = append(texts, response.Text)
		}

		combined.Usage.InputTokens = core.SumCounts(combined.Usage.InputTokens, response.Usage.InputTokens)
		combined.Usage.OutputTokens = core.SumCounts(combined.Usage.OutputTokens, response.Usage.OutputTokens)
		combined.Usage.CachedInputTokens = core.SumCounts(combined.Usage.CachedInputTokens, response.Usage.CachedInputTokens)
		combined.Usage.ReasoningTokens = core.SumCounts(combined.Usage.ReasoningTokens, response.Usage.ReasoningTokens)
		combined.Usage.TotalTokens = core.SumCounts(combined.Usage.TotalTokens, response.Usage.TotalTokens)

		if response.RawResponse != nil {
			raws = append(raws, response.RawResponse)
		}
	}

	combined.Text = strings.Join(texts, "\n")

	if len(raws) > 0 {
		encoded, err := json.Marshal(raws)
		if err == nil {
			combined.RawResponse = encoded
		}
	}

	return combined
}

// ImageStreamSink 向调用方输出图片流事件，库内循环时只发一次 started，并按已完成图片数偏移 imageIndex。
type ImageStreamSink struct {
	Builder    *core.ImageEventBuilder
	Yield      func(core.ImageEvent) bool
	IndexBase  int
	hasStarted bool
	isStopped  bool
}

// Started 发出 started 事件，已发过时跳过。
func (s *ImageStreamSink) Started(model *string, responseID *string) bool {
	if s.hasStarted {
		return !s.isStopped
	}

	s.hasStarted = true
	event := s.Builder.NewEvent(core.ImageEventStarted)
	event.Model = model
	event.ID = responseID

	return s.emit(event)
}

// PartialImage 发出 partial_image 事件。
func (s *ImageStreamSink) PartialImage(imageIndex int, partialIndex int, data []byte, mimeType string) bool {
	event := s.Builder.NewEvent(core.ImageEventPartialImage)
	event.ImageIndex = s.IndexBase + imageIndex
	event.PartialIndex = partialIndex
	event.Data = data
	event.MimeType = mimeType

	return s.emit(event)
}

// ImageCompleted 发出 image_completed 事件。
func (s *ImageStreamSink) ImageCompleted(imageIndex int, image core.GeneratedImage) bool {
	event := s.Builder.NewEvent(core.ImageEventImageCompleted)
	event.ImageIndex = s.IndexBase + imageIndex
	event.Image = &image

	return s.emit(event)
}

// Finish 厂商返回过用量时先发出 usage，再发出 completed 终止事件。
func (s *ImageStreamSink) Finish(response *core.ImageResponse) {
	usage := response.Usage
	hasUsage := usage.InputTokens != nil || usage.OutputTokens != nil || usage.CachedInputTokens != nil || usage.ReasoningTokens != nil || usage.TotalTokens != nil

	if hasUsage {
		usageEvent := s.Builder.NewEvent(core.ImageEventUsage)
		usageEvent.Usage = &usage

		if !s.emit(usageEvent) {
			return
		}
	}

	completed := s.Builder.NewEvent(core.ImageEventCompleted)
	completed.Response = response
	s.emit(completed)
}

// Fail 发出 error 终止事件。
func (s *ImageStreamSink) Fail(err *core.Error) {
	s.emit(s.Builder.ErrorEvent(err))
}

// IsStopped 判断调用方是否已停止迭代。
func (s *ImageStreamSink) IsStopped() bool {
	return s.isStopped
}

// emit 输出事件并记录调用方是否停止迭代
func (s *ImageStreamSink) emit(event core.ImageEvent) bool {
	if s.isStopped {
		return false
	}

	if !s.Yield(event) {
		s.isStopped = true
	}

	return !s.isStopped
}

// StreamImageLoop 依次执行 count 次单张流式生成，汇总后发出 usage 与 completed，任一次失败发出 error 并结束。
func StreamImageLoop(ctx context.Context, sink *ImageStreamSink, count int, provider string, streamOne func(ctx context.Context, sink *ImageStreamSink) (*core.ImageResponse, error)) {
	responses := []*core.ImageResponse{}

	for index := 0; index < count; index++ {
		response, err := streamOne(ctx, sink)
		if sink.IsStopped() {
			return
		}

		if err != nil {
			sink.Fail(core.AsError(err, provider))
			return
		}

		responses = append(responses, response)
		sink.IndexBase += len(response.Images)
	}

	sink.Finish(CombineImageResponses(responses))
}
