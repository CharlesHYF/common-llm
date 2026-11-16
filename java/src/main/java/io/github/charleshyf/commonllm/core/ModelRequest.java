package io.github.charleshyf.commonllm.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一模型调用请求
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class ModelRequest {

	private final String provider;
	private final String model;
	private final List<Message> messages;
	private final Double temperature;
	private final Long maxOutputTokens;
	private final Double timeoutSeconds;
	private final List<ToolDefinition> tools;
	private final ToolChoice toolChoice;
	private final ResponseFormat responseFormat;
	private final Map<String, Object> providerOptions;
	private final boolean shouldIncludeRawResponse;
	private final String traceId;
	private final String requestId;
	private final Map<String, String> metadata;

	private ModelRequest(Builder builder) {
		this.provider = builder.provider;
		this.model = builder.model;
		this.messages = Collections.unmodifiableList(new ArrayList<>(builder.messages));
		this.temperature = builder.temperature;
		this.maxOutputTokens = builder.maxOutputTokens;
		this.timeoutSeconds = builder.timeoutSeconds;
		this.tools = Collections.unmodifiableList(new ArrayList<>(builder.tools));
		this.toolChoice = builder.toolChoice;
		this.responseFormat = builder.responseFormat;
		this.providerOptions = Collections.unmodifiableMap(new LinkedHashMap<>(builder.providerOptions));
		this.shouldIncludeRawResponse = builder.shouldIncludeRawResponse;
		this.traceId = builder.traceId;
		this.requestId = builder.requestId;
		this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(builder.metadata));
	}

	public static Builder builder() {
		return new Builder();
	}

	public String getProvider() {
		return provider;
	}

	public String getModel() {
		return model;
	}

	public List<Message> getMessages() {
		return messages;
	}

	public Double getTemperature() {
		return temperature;
	}

	public Long getMaxOutputTokens() {
		return maxOutputTokens;
	}

	public Double getTimeoutSeconds() {
		return timeoutSeconds;
	}

	public List<ToolDefinition> getTools() {
		return tools;
	}

	public ToolChoice getToolChoice() {
		return toolChoice;
	}

	public ResponseFormat getResponseFormat() {
		return responseFormat;
	}

	public Map<String, Object> getProviderOptions() {
		return providerOptions;
	}

	public boolean shouldIncludeRawResponse() {
		return shouldIncludeRawResponse;
	}

	public String getTraceId() {
		return traceId;
	}

	public String getRequestId() {
		return requestId;
	}

	public Map<String, String> getMetadata() {
		return metadata;
	}

	/**
	 * 基于当前请求组装新的构造器，用于补全默认值
	 */
	public Builder toBuilder() {
		Builder builder = new Builder();
		builder.provider = provider;
		builder.model = model;
		builder.messages = new ArrayList<>(messages);
		builder.temperature = temperature;
		builder.maxOutputTokens = maxOutputTokens;
		builder.timeoutSeconds = timeoutSeconds;
		builder.tools = new ArrayList<>(tools);
		builder.toolChoice = toolChoice;
		builder.responseFormat = responseFormat;
		builder.providerOptions = new LinkedHashMap<>(providerOptions);
		builder.shouldIncludeRawResponse = shouldIncludeRawResponse;
		builder.traceId = traceId;
		builder.requestId = requestId;
		builder.metadata = new LinkedHashMap<>(metadata);
		return builder;
	}

	@Override
	public String toString() {
		return "ModelRequest{provider=" + provider + ", model=" + model + ", messageCount=" + messages.size() + ", traceId=" + traceId + "}";
	}

	/**
	 * ModelRequest 构造器
	 */
	public static final class Builder {

		private String provider;
		private String model;
		private List<Message> messages = new ArrayList<>();
		private Double temperature;
		private Long maxOutputTokens;
		private Double timeoutSeconds;
		private List<ToolDefinition> tools = new ArrayList<>();
		private ToolChoice toolChoice;
		private ResponseFormat responseFormat;
		private Map<String, Object> providerOptions = new LinkedHashMap<>();
		private boolean shouldIncludeRawResponse;
		private String traceId;
		private String requestId;
		private Map<String, String> metadata = new LinkedHashMap<>();

		private Builder() {
		}

		public Builder provider(String provider) {
			this.provider = provider;
			return this;
		}

		public Builder model(String model) {
			this.model = model;
			return this;
		}

		public Builder messages(List<Message> messages) {
			this.messages = new ArrayList<>(messages);
			return this;
		}

		public Builder addMessage(Message message) {
			this.messages.add(message);
			return this;
		}

		public Builder temperature(Double temperature) {
			this.temperature = temperature;
			return this;
		}

		public Builder maxOutputTokens(Long maxOutputTokens) {
			this.maxOutputTokens = maxOutputTokens;
			return this;
		}

		public Builder timeoutSeconds(Double timeoutSeconds) {
			this.timeoutSeconds = timeoutSeconds;
			return this;
		}

		public Builder tools(List<ToolDefinition> tools) {
			this.tools = new ArrayList<>(tools);
			return this;
		}

		public Builder addTool(ToolDefinition tool) {
			this.tools.add(tool);
			return this;
		}

		public Builder toolChoice(ToolChoice toolChoice) {
			this.toolChoice = toolChoice;
			return this;
		}

		public Builder responseFormat(ResponseFormat responseFormat) {
			this.responseFormat = responseFormat;
			return this;
		}

		public Builder providerOptions(Map<String, Object> providerOptions) {
			this.providerOptions = new LinkedHashMap<>(providerOptions);
			return this;
		}

		public Builder putProviderOption(String key, Object value) {
			this.providerOptions.put(key, value);
			return this;
		}

		public Builder includeRawResponse(boolean shouldIncludeRawResponse) {
			this.shouldIncludeRawResponse = shouldIncludeRawResponse;
			return this;
		}

		public Builder traceId(String traceId) {
			this.traceId = traceId;
			return this;
		}

		public Builder requestId(String requestId) {
			this.requestId = requestId;
			return this;
		}

		public Builder metadata(Map<String, String> metadata) {
			this.metadata = new LinkedHashMap<>(metadata);
			return this;
		}

		public ModelRequest build() {
			return new ModelRequest(this);
		}
	}
}
