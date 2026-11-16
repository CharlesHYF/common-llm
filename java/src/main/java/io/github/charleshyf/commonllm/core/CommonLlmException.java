package io.github.charleshyf.commonllm.core;

/**
 * 携带统一错误信息的非受检异常
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public class CommonLlmException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final transient ErrorInfo errorInfo;

	public CommonLlmException(ErrorInfo errorInfo) {
		super(errorInfo.getCategory().name() + ": " + errorInfo.getMessage());
		this.errorInfo = errorInfo;
	}

	public CommonLlmException(ErrorInfo errorInfo, Throwable cause) {
		super(errorInfo.getCategory().name() + ": " + errorInfo.getMessage(), cause);
		this.errorInfo = errorInfo;
	}

	public ErrorInfo getErrorInfo() {
		return errorInfo;
	}

	public ErrorCategory getCategory() {
		return errorInfo.getCategory();
	}

	/**
	 * 组装不可重试的异常，用于配置与参数校验失败
	 */
	public static CommonLlmException of(ErrorCategory category, String message, String provider) {
		return new CommonLlmException(ErrorInfo.builder(category, message).provider(provider).build());
	}
}
