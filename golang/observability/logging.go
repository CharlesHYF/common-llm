// 基于 log/slog 的默认日志创建与密钥字段脱敏
// 创建日期：2026-11-16
// 修改日期：2026-11-16

package observability

import (
	"io"
	"log/slog"
	"strings"

	"github.com/CharlesHYF/common-llm/golang/core"
)

// secretKeyFragments 日志字段名包含这些片段时输出 ***
var secretKeyFragments = []string{
	"apikey",
	"api_key",
	"authorization",
	"token",
	"secret",
	"password",
}

// ParseLevel 把配置中的 log_level 转换为 slog.Level，未知值按 INFO 处理。
func ParseLevel(level string) slog.Level {
	switch strings.ToUpper(level) {
	case "DEBUG":
		return slog.LevelDebug
	case "WARN":
		return slog.LevelWarn
	case "ERROR":
		return slog.LevelError
	default:
		return slog.LevelInfo
	}
}

// NewLogger 创建输出到 writer 的文本日志，密钥字段输出 ***，字符串值做通用脱敏。
func NewLogger(writer io.Writer, level string) *slog.Logger {
	handler := slog.NewTextHandler(writer, &slog.HandlerOptions{
		Level:       ParseLevel(level),
		ReplaceAttr: redactAttr,
	})

	return slog.New(handler)
}

// redactAttr 按字段名与字符串内容脱敏
func redactAttr(groups []string, attr slog.Attr) slog.Attr {
	normalizedKey := strings.ToLower(attr.Key)

	for _, fragment := range secretKeyFragments {
		if strings.Contains(normalizedKey, fragment) && !strings.Contains(normalizedKey, "tokens") {
			return slog.String(attr.Key, core.RedactedValue)
		}
	}

	if attr.Value.Kind() == slog.KindString {
		return slog.String(attr.Key, core.SanitizeText(attr.Value.String()))
	}

	return attr
}
