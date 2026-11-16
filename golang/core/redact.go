// 密钥脱敏、指针构造、紧凑 JSON 与按码点截断等公共工具函数
// 创建日期：2026-11-16
// 修改日期：2026-11-16

package core

import (
	"bytes"
	"encoding/json"
	"regexp"
	"strings"
)

// RedactedValue 密钥字段在日志、字符串与序列化输出中的替代值。
const RedactedValue = "***"

// secretPatterns 常见密钥与令牌格式，命中部分替换为 RedactedValue
var secretPatterns = []*regexp.Regexp{
	regexp.MustCompile(`sk-[A-Za-z0-9_\-]{8,}`),
	regexp.MustCompile(`AIza[0-9A-Za-z_\-]{20,}`),
	regexp.MustCompile(`(?i)bearer\s+[A-Za-z0-9._\-]+`),
	regexp.MustCompile(`(?i)(api[_-]?key|x-api-key|x-goog-api-key|access[_-]?token|token|password|secret)(["']?\s*[:=]\s*["']?)[^\s"',&]+`),
}

// RedactSecret 非空密钥返回 RedactedValue，空值保持为空。
func RedactSecret(secret string) string {
	if secret == "" {
		return ""
	}

	return RedactedValue
}

// SanitizeText 替换文本中的常见密钥格式并限制长度，用于错误消息、日志与诊断输出。
func SanitizeText(text string) string {
	sanitized := text

	for _, pattern := range secretPatterns {
		sanitized = pattern.ReplaceAllStringFunc(sanitized, redactMatchedSecret)
	}

	return TruncateRunes(sanitized, 2000)
}

// SanitizeTextWithSecrets 先替换已知密钥原文，再执行通用脱敏。
func SanitizeTextWithSecrets(text string, secrets []string) string {
	sanitized := text

	for _, secret := range secrets {
		if secret == "" {
			continue
		}

		sanitized = strings.ReplaceAll(sanitized, secret, RedactedValue)
	}

	return SanitizeText(sanitized)
}

// redactMatchedSecret 保留键名与分隔符，只替换密钥值
func redactMatchedSecret(matched string) string {
	keyValuePattern := secretPatterns[len(secretPatterns)-1]
	groups := keyValuePattern.FindStringSubmatch(matched)

	if len(groups) == 3 {
		return groups[1] + groups[2] + RedactedValue
	}

	return RedactedValue
}

// TruncateRunes 按 Unicode 码点截断字符串。
func TruncateRunes(text string, maxRunes int) string {
	if maxRunes < 0 {
		return text
	}

	count := 0
	for byteIndex := range text {
		if count == maxRunes {
			return text[:byteIndex]
		}

		count++
	}

	return text
}

// TruncateRunesPtr 对可空字符串按码点截断，nil 保持为 nil。
func TruncateRunesPtr(text *string, maxRunes int) *string {
	if text == nil {
		return nil
	}

	truncated := TruncateRunes(*text, maxRunes)
	return &truncated
}

// CompactJSON 把值序列化为紧凑 JSON 字符串，不转义 HTML 字符。
func CompactJSON(value any) (string, error) {
	var buffer bytes.Buffer
	encoder := json.NewEncoder(&buffer)
	encoder.SetEscapeHTML(false)

	if err := encoder.Encode(value); err != nil {
		return "", err
	}

	return strings.TrimRight(buffer.String(), "\n"), nil
}

// StringPtr 返回字符串指针。
func StringPtr(value string) *string {
	return &value
}

// NonEmptyStringPtr 空字符串返回 nil，其余返回指针。
func NonEmptyStringPtr(value string) *string {
	if value == "" {
		return nil
	}

	return &value
}

// IntPtr 返回 int 指针。
func IntPtr(value int) *int {
	return &value
}

// Int64Ptr 返回 int64 指针。
func Int64Ptr(value int64) *int64 {
	return &value
}

// Float64Ptr 返回 float64 指针。
func Float64Ptr(value float64) *float64 {
	return &value
}

// BoolPtr 返回 bool 指针。
func BoolPtr(value bool) *bool {
	return &value
}
