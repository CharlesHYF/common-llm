// 统一错误分类、ErrorInfo 结构与可被 errors.As 提取的错误类型
// 创建日期：2026-11-16
// 修改日期：2026-11-16

package core

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/url"
)

// ErrorCategory 统一错误分类，取值与接口契约第 5.2 节一致。
type ErrorCategory string

const (
	CategoryConfigInvalid         ErrorCategory = "CONFIG_INVALID"
	CategoryAuthMissing           ErrorCategory = "AUTH_MISSING"
	CategoryAuthFailed            ErrorCategory = "AUTH_FAILED"
	CategoryPermissionDenied      ErrorCategory = "PERMISSION_DENIED"
	CategoryModelUnavailable      ErrorCategory = "MODEL_UNAVAILABLE"
	CategoryRateLimited           ErrorCategory = "RATE_LIMITED"
	CategoryQuotaExceeded         ErrorCategory = "QUOTA_EXCEEDED"
	CategoryTimeout               ErrorCategory = "TIMEOUT"
	CategoryCancelled             ErrorCategory = "CANCELLED"
	CategoryNetworkError          ErrorCategory = "NETWORK_ERROR"
	CategoryProviderError         ErrorCategory = "PROVIDER_ERROR"
	CategorySDKUnsupportedFeature ErrorCategory = "SDK_UNSUPPORTED_FEATURE"
	CategoryCLINotFound           ErrorCategory = "CLI_NOT_FOUND"
	CategoryCLIVersionUnsupported ErrorCategory = "CLI_VERSION_UNSUPPORTED"
	CategoryCLIProcessFailed      ErrorCategory = "CLI_PROCESS_FAILED"
	CategoryCLIProtocolError      ErrorCategory = "CLI_PROTOCOL_ERROR"
	CategoryAgentApprovalRequired ErrorCategory = "AGENT_APPROVAL_REQUIRED"
	CategorySandboxDenied         ErrorCategory = "SANDBOX_DENIED"
	CategoryInternalError         ErrorCategory = "INTERNAL_ERROR"
)

// ErrorInfo 跨语言统一的错误描述，序列化字段名与契约 JSON 一致。
type ErrorInfo struct {
	Category   ErrorCategory `json:"category"`
	Message    string        `json:"message"`
	Provider   *string       `json:"provider"`
	Retriable  bool          `json:"retriable"`
	RequestID  *string       `json:"requestId"`
	VendorCode *string       `json:"vendorCode"`
	StatusCode *int          `json:"statusCode"`
	Cause      *string       `json:"cause"`
}

// Error 本库对外返回的错误类型，调用方可用 errors.As 提取 ErrorInfo。
type Error struct {
	Info ErrorInfo
	// err 保留底层错误用于 errors.Is 判断，不参与字符串输出，避免泄露密钥
	err error
}

// NewError 按分类创建错误，retriable 取该分类的默认值。
func NewError(category ErrorCategory, message string, provider string) *Error {
	info := ErrorInfo{
		Category:  category,
		Message:   SanitizeText(message),
		Retriable: IsCategoryRetriable(category),
	}

	if provider != "" {
		info.Provider = StringPtr(provider)
	}

	return &Error{Info: info}
}

// WrapError 按分类创建错误并保留底层错误，cause 记录脱敏后的底层错误类型与消息。
func WrapError(category ErrorCategory, message string, provider string, cause error) *Error {
	created := NewError(category, message, provider)

	if cause != nil {
		created.err = cause
		created.Info.Cause = StringPtr(SanitizeText(fmt.Sprintf("%T: %s", cause, cause.Error())))
	}

	return created
}

// ErrorFromInfo 由已有的 ErrorInfo 创建错误。
func ErrorFromInfo(info ErrorInfo) *Error {
	return &Error{Info: info}
}

// WithStatusCode 设置 HTTP 状态码或进程退出码。
func (e *Error) WithStatusCode(statusCode int) *Error {
	e.Info.StatusCode = IntPtr(statusCode)
	return e
}

// WithVendorCode 设置厂商错误码，空字符串表示没有错误码。
func (e *Error) WithVendorCode(vendorCode string) *Error {
	if vendorCode != "" {
		e.Info.VendorCode = StringPtr(vendorCode)
	}

	return e
}

// WithRequestID 设置厂商请求 ID，空字符串表示没有请求 ID。
func (e *Error) WithRequestID(requestID string) *Error {
	if requestID != "" {
		e.Info.RequestID = StringPtr(requestID)
	}

	return e
}

// WithRetriable 覆盖分类默认的可重试标记。
func (e *Error) WithRetriable(isRetriable bool) *Error {
	e.Info.Retriable = isRetriable
	return e
}

// Error 返回脱敏后的错误描述。
func (e *Error) Error() string {
	if e == nil {
		return "common-llm 错误"
	}

	provider := ""
	if e.Info.Provider != nil {
		provider = " provider=" + *e.Info.Provider
	}

	return fmt.Sprintf("%s%s: %s", e.Info.Category, provider, e.Info.Message)
}

// Unwrap 返回底层错误，便于调用方判断 context.Canceled 等哨兵错误。
func (e *Error) Unwrap() error {
	return e.err
}

// Category 返回错误分类。
func (e *Error) Category() ErrorCategory {
	return e.Info.Category
}

// IsCategoryRetriable 返回分类的默认可重试标记，PROVIDER_ERROR 需按状态码另行判断。
func IsCategoryRetriable(category ErrorCategory) bool {
	switch category {
	case CategoryRateLimited, CategoryTimeout, CategoryNetworkError:
		return true
	default:
		return false
	}
}

// ClassifyHTTPStatus 按 HTTP 状态码与厂商标记返回统一分类与是否可重试。
func ClassifyHTTPStatus(statusCode int, isQuotaExceeded bool, isAuthFailure bool) (ErrorCategory, bool) {
	if statusCode == 401 || isAuthFailure {
		return CategoryAuthFailed, false
	}

	if statusCode == 403 {
		return CategoryPermissionDenied, false
	}

	if statusCode == 404 {
		return CategoryModelUnavailable, false
	}

	if statusCode == 408 {
		return CategoryTimeout, true
	}

	if statusCode == 429 && isQuotaExceeded {
		return CategoryQuotaExceeded, false
	}

	if statusCode == 429 {
		return CategoryRateLimited, true
	}

	if statusCode >= 500 {
		return CategoryProviderError, true
	}

	return CategoryProviderError, false
}

// ClassifyTransportError 把 context 取消、超时与网络错误映射为统一错误，无法识别时返回 nil。
func ClassifyTransportError(ctx context.Context, err error, provider string) *Error {
	if ctx != nil && errors.Is(ctx.Err(), context.Canceled) {
		return WrapError(CategoryCancelled, "调用方已取消请求", provider, err)
	}

	if errors.Is(err, context.Canceled) {
		return WrapError(CategoryCancelled, "调用方已取消请求", provider, err)
	}

	if errors.Is(err, context.DeadlineExceeded) {
		return WrapError(CategoryTimeout, "请求超时", provider, err)
	}

	var netErr net.Error
	if errors.As(err, &netErr) && netErr.Timeout() {
		return WrapError(CategoryTimeout, "请求超时", provider, err)
	}

	var urlErr *url.Error
	if errors.As(err, &urlErr) {
		return WrapError(CategoryNetworkError, "网络请求失败", provider, err)
	}

	var opErr *net.OpError
	if errors.As(err, &opErr) {
		return WrapError(CategoryNetworkError, "网络连接失败", provider, err)
	}

	return nil
}

// AsError 把任意错误转换为 *Error，已是 *Error 时原样返回。
func AsError(err error, provider string) *Error {
	if err == nil {
		return nil
	}

	var commonErr *Error
	if errors.As(err, &commonErr) {
		return commonErr
	}

	return WrapError(CategoryInternalError, "未分类的内部错误", provider, err)
}
