// 模型 Provider 适配器共用的 HTTP 客户端、模型与超时解析、调用日志
// 创建日期：2026-11-16
// 修改日期：2026-11-16

package providerkit

import (
	"context"
	"log/slog"
	"net"
	"net/http"
	"slices"
	"strings"
	"time"

	"github.com/CharlesHYF/common-llm/golang/config"
	"github.com/CharlesHYF/common-llm/golang/core"
)

// NewHTTPClient 创建带连接超时的 HTTP 客户端，整体请求超时交给 SDK 设置。
func NewHTTPClient(connectTimeoutSeconds float64) *http.Client {
	dialer := &net.Dialer{
		Timeout:   secondsToDuration(connectTimeoutSeconds),
		KeepAlive: 30 * time.Second,
	}

	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.DialContext = dialer.DialContext
	transport.TLSHandshakeTimeout = secondsToDuration(connectTimeoutSeconds)

	return &http.Client{Transport: transport}
}

// ResolveModel 请求 model 优先，其次配置 default_model，两者都没有报 CONFIG_INVALID。
func ResolveModel(request *core.ModelRequest, providerConfig config.ProviderConfig) (string, error) {
	model := core.FirstNonEmpty(request.Model, providerConfig.DefaultModel)

	if model == "" {
		return "", core.NewError(core.CategoryConfigInvalid, "未指定 model：请求 model 与配置 default_model 均为空", providerConfig.Name)
	}

	return model, nil
}

// RequestTimeout 请求 timeoutSeconds 优先，其次配置 timeout_seconds。
func RequestTimeout(request *core.ModelRequest, providerConfig config.ProviderConfig) time.Duration {
	if request.TimeoutSeconds != nil {
		return secondsToDuration(*request.TimeoutSeconds)
	}

	return secondsToDuration(providerConfig.TimeoutSeconds)
}

// secondsToDuration 把秒数转换为 time.Duration
func secondsToDuration(seconds float64) time.Duration {
	return time.Duration(seconds * float64(time.Second))
}

// CallLogger 记录单次模型调用的耗时、用量与错误分类，不记录 prompt、响应正文与密钥。
type CallLogger struct {
	logger    *slog.Logger
	provider  string
	model     string
	traceID   string
	startedAt time.Time
}

// NewCallLogger 开始计时。
func NewCallLogger(logger *slog.Logger, provider string, model string, traceID string) *CallLogger {
	return &CallLogger{
		logger:    logger,
		provider:  provider,
		model:     model,
		traceID:   traceID,
		startedAt: time.Now(),
	}
}

// LogSuccess 记录成功调用。
func (l *CallLogger) LogSuccess(ctx context.Context, usage core.Usage) {
	if l.logger == nil {
		return
	}

	l.logger.LogAttrs(ctx, slog.LevelDebug, "模型调用完成",
		slog.String("provider", l.provider),
		slog.String("model", l.model),
		slog.String("traceId", l.traceID),
		slog.Int64("durationMs", time.Since(l.startedAt).Milliseconds()),
		slog.Any("inputTokens", usage.InputTokens),
		slog.Any("outputTokens", usage.OutputTokens),
		slog.Any("totalTokens", usage.TotalTokens),
	)
}

// LogFailure 记录失败调用。
func (l *CallLogger) LogFailure(ctx context.Context, err *core.Error) {
	if l.logger == nil {
		return
	}

	l.logger.LogAttrs(ctx, slog.LevelWarn, "模型调用失败",
		slog.String("provider", l.provider),
		slog.String("model", l.model),
		slog.String("traceId", l.traceID),
		slog.Int64("durationMs", time.Since(l.startedAt).Milliseconds()),
		slog.String("category", string(err.Info.Category)),
		slog.Bool("retriable", err.Info.Retriable),
	)
}

// EscapeJSONPathKey 转义 sjson 路径中的特殊字符，使顶层键名按字面量写入请求体。
func EscapeJSONPathKey(key string) string {
	var builder strings.Builder

	for _, character := range key {
		if strings.ContainsRune(`\\.*?|#@!:`, character) {
			builder.WriteRune('\\')
		}

		builder.WriteRune(character)
	}

	return builder.String()
}

// SortedKeys 返回按字母序排列的键，保证请求体字段顺序稳定。
func SortedKeys(values map[string]any) []string {
	keys := make([]string, 0, len(values))

	for key := range values {
		keys = append(keys, key)
	}

	slices.Sort(keys)
	return keys
}
