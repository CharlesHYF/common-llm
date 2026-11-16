package io.github.charleshyf.commonllm.core;

/**
 * 按 HTTP 状态码与厂商错误标记映射统一错误分类
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class HttpErrorClassifier {

	private HttpErrorClassifier() {
	}

	/**
	 * 返回错误分类，isQuota 表示厂商明确为额度耗尽，isAuthFailure 表示厂商明确为认证失败
	 */
	public static ErrorCategory classify(int statusCode, boolean isQuota, boolean isAuthFailure) {
		if (statusCode == 401 || isAuthFailure) {
			return ErrorCategory.AUTH_FAILED;
		}

		if (statusCode == 403) {
			return ErrorCategory.PERMISSION_DENIED;
		}

		if (statusCode == 404) {
			return ErrorCategory.MODEL_UNAVAILABLE;
		}

		if (statusCode == 408) {
			return ErrorCategory.TIMEOUT;
		}

		if (statusCode == 429 && isQuota) {
			return ErrorCategory.QUOTA_EXCEEDED;
		}

		if (statusCode == 429) {
			return ErrorCategory.RATE_LIMITED;
		}

		return ErrorCategory.PROVIDER_ERROR;
	}

	/**
	 * 判断该状态码对应的错误是否可重试
	 */
	public static boolean isRetriable(ErrorCategory category, int statusCode) {
		if (category == ErrorCategory.TIMEOUT || category == ErrorCategory.RATE_LIMITED) {
			return true;
		}

		return category == ErrorCategory.PROVIDER_ERROR && statusCode >= 500;
	}

	/**
	 * 组装 HTTP 错误对应的 ErrorInfo
	 */
	public static ErrorInfo buildError(String provider, int statusCode, String vendorCode, String vendorMessage, String requestId, boolean isQuota, boolean isAuthFailure, Throwable cause) {
		ErrorCategory category = classify(statusCode, isQuota, isAuthFailure);
		String message = vendorMessage == null || vendorMessage.isBlank() ? "厂商返回 HTTP " + statusCode : vendorMessage;
		return ErrorInfo.builder(category, message)
			.provider(provider)
			.retriable(isRetriable(category, statusCode))
			.statusCode(statusCode)
			.vendorCode(vendorCode)
			.requestId(requestId)
			.cause(cause)
			.build();
	}
}
