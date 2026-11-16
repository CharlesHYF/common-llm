package io.github.charleshyf.commonllm.config;

import io.github.charleshyf.commonllm.core.SecretMasker;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单个模型 Provider 实例的配置
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class ProviderConfig {

	public static final String TYPE_OPENAI = "openai";
	public static final String TYPE_ANTHROPIC = "anthropic";
	public static final String TYPE_GEMINI = "gemini";
	public static final String API_RESPONSES = "responses";
	public static final String API_CHAT_COMPLETIONS = "chat_completions";
	public static final String IMAGE_API_IMAGES = "images";
	public static final String IMAGE_API_RESPONSES = "responses";
	public static final int DEFAULT_IMAGE_CONCURRENCY = 1;

	private final String instanceName;
	private final String type;
	private final String api;
	private final boolean isEnabled;
	private final String apiKey;
	private final String baseUrl;
	private final String defaultModel;
	private final double timeoutSeconds;
	private final double connectTimeoutSeconds;
	private final int maxRetries;
	private final Map<String, Object> nativeOptions;
	private final String defaultImageModel;
	private final String imageApi;
	private final int imageConcurrency;
	private final double imageTimeoutSeconds;

	/**
	 * imageTimeoutSeconds 未单独配置时由解析方传入 timeoutSeconds
	 */
	public ProviderConfig(String instanceName, String type, String api, boolean isEnabled, String apiKey, String baseUrl, String defaultModel, double timeoutSeconds, double connectTimeoutSeconds, int maxRetries, Map<String, Object> nativeOptions, String defaultImageModel, String imageApi, int imageConcurrency, double imageTimeoutSeconds) {
		this.instanceName = instanceName;
		this.type = type;
		this.api = api;
		this.isEnabled = isEnabled;
		this.apiKey = apiKey;
		this.baseUrl = baseUrl;
		this.defaultModel = defaultModel;
		this.timeoutSeconds = timeoutSeconds;
		this.connectTimeoutSeconds = connectTimeoutSeconds;
		this.maxRetries = maxRetries;
		this.nativeOptions = Collections.unmodifiableMap(new LinkedHashMap<>(nativeOptions));
		this.defaultImageModel = defaultImageModel;
		this.imageApi = imageApi;
		this.imageConcurrency = imageConcurrency;
		this.imageTimeoutSeconds = imageTimeoutSeconds;
	}

	public String getInstanceName() {
		return instanceName;
	}

	public String getType() {
		return type;
	}

	public String getApi() {
		return api;
	}

	public boolean isEnabled() {
		return isEnabled;
	}

	public String getApiKey() {
		return apiKey;
	}

	public String getBaseUrl() {
		return baseUrl;
	}

	public String getDefaultModel() {
		return defaultModel;
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

	public Map<String, Object> getNativeOptions() {
		return nativeOptions;
	}

	public String getDefaultImageModel() {
		return defaultImageModel;
	}

	/**
	 * 仅 type=openai：images 或 responses
	 */
	public String getImageApi() {
		return imageApi;
	}

	public int getImageConcurrency() {
		return imageConcurrency;
	}

	public double getImageTimeoutSeconds() {
		return imageTimeoutSeconds;
	}

	@Override
	public String toString() {
		return "ProviderConfig{instanceName=" + instanceName + ", type=" + type + ", api=" + api + ", enabled=" + isEnabled + ", apiKey=" + SecretMasker.maskValue(apiKey) + ", baseUrl=" + baseUrl + ", defaultModel=" + defaultModel + ", timeoutSeconds=" + timeoutSeconds + ", maxRetries=" + maxRetries + ", defaultImageModel=" + defaultImageModel + ", imageApi=" + imageApi + ", imageConcurrency=" + imageConcurrency + "}";
	}
}
