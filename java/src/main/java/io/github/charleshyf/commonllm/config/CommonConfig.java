package io.github.charleshyf.commonllm.config;

/**
 * 全局公共配置
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class CommonConfig {

	public static final double DEFAULT_TIMEOUT_SECONDS = 120;
	public static final double DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;
	public static final int DEFAULT_MAX_RETRIES = 2;
	public static final String DEFAULT_LOG_LEVEL = "INFO";

	private final double timeoutSeconds;
	private final double connectTimeoutSeconds;
	private final int maxRetries;
	private final String logLevel;

	public CommonConfig(double timeoutSeconds, double connectTimeoutSeconds, int maxRetries, String logLevel) {
		this.timeoutSeconds = timeoutSeconds;
		this.connectTimeoutSeconds = connectTimeoutSeconds;
		this.maxRetries = maxRetries;
		this.logLevel = logLevel;
	}

	public double getTimeoutSeconds() {
		return timeoutSeconds;
	}

	public double getConnectTimeoutSeconds() {
		return connectTimeoutSeconds;
	}

	public int getMaxRetries() {
		return maxRetries;
	}

	public String getLogLevel() {
		return logLevel;
	}

	@Override
	public String toString() {
		return "CommonConfig{timeoutSeconds=" + timeoutSeconds + ", connectTimeoutSeconds=" + connectTimeoutSeconds + ", maxRetries=" + maxRetries + ", logLevel=" + logLevel + "}";
	}
}
