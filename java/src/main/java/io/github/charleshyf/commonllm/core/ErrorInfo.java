package io.github.charleshyf.commonllm.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 脱敏后的统一错误信息
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class ErrorInfo {

	private final ErrorCategory category;
	private final String message;
	private final String provider;
	private final boolean retriable;
	private final String requestId;
	private final String vendorCode;
	private final Integer statusCode;
	private final String cause;

	private ErrorInfo(Builder builder) {
		this.category = builder.category;
		this.message = SecretMasker.mask(builder.message);
		this.provider = builder.provider;
		this.retriable = builder.retriable;
		this.requestId = builder.requestId;
		this.vendorCode = builder.vendorCode;
		this.statusCode = builder.statusCode;
		this.cause = SecretMasker.mask(builder.cause);
	}

	public static Builder builder(ErrorCategory category, String message) {
		return new Builder(category, message);
	}

	public ErrorCategory getCategory() {
		return category;
	}

	public String getMessage() {
		return message;
	}

	public String getProvider() {
		return provider;
	}

	public boolean isRetriable() {
		return retriable;
	}

	public String getRequestId() {
		return requestId;
	}

	public String getVendorCode() {
		return vendorCode;
	}

	public Integer getStatusCode() {
		return statusCode;
	}

	public String getCause() {
		return cause;
	}

	/**
	 * 转换为契约 JSON 结构，字段名与 error.schema.json 一致
	 */
	public Map<String, Object> toContractMap() {
		Map<String, Object> contractMap = new LinkedHashMap<>();
		contractMap.put("category", category.name());
		contractMap.put("message", message);
		contractMap.put("provider", provider);
		contractMap.put("retriable", retriable);
		contractMap.put("requestId", requestId);
		contractMap.put("vendorCode", vendorCode);
		contractMap.put("statusCode", statusCode);
		contractMap.put("cause", cause);
		return contractMap;
	}

	@Override
	public String toString() {
		return "ErrorInfo" + toContractMap();
	}

	/**
	 * ErrorInfo 构造器，message 与 cause 在构造时统一脱敏
	 */
	public static final class Builder {

		private final ErrorCategory category;
		private final String message;
		private String provider;
		private boolean retriable;
		private String requestId;
		private String vendorCode;
		private Integer statusCode;
		private String cause;

		private Builder(ErrorCategory category, String message) {
			this.category = category;
			this.message = message;
		}

		public Builder provider(String provider) {
			this.provider = provider;
			return this;
		}

		public Builder retriable(boolean retriable) {
			this.retriable = retriable;
			return this;
		}

		public Builder requestId(String requestId) {
			this.requestId = requestId;
			return this;
		}

		public Builder vendorCode(String vendorCode) {
			this.vendorCode = vendorCode;
			return this;
		}

		public Builder statusCode(Integer statusCode) {
			this.statusCode = statusCode;
			return this;
		}

		public Builder cause(Throwable throwable) {
			if (throwable != null) {
				this.cause = throwable.getClass().getName() + ": " + throwable.getMessage();
			}

			return this;
		}

		public Builder causeText(String causeText) {
			this.cause = causeText;
			return this;
		}

		public ErrorInfo build() {
			return new ErrorInfo(this);
		}
	}
}
