package io.github.charleshyf.commonllm.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型流式调用产生的单个事件
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class ModelEvent {

	private final ModelEventType type;
	private final String traceId;
	private final long sequence;
	private final String provider;
	private final String model;
	private final String responseId;
	private final String text;
	private final Integer index;
	private final String toolCallId;
	private final String toolName;
	private final String argumentsDelta;
	private final Usage usage;
	private final ModelResponse response;
	private final ErrorInfo error;

	private ModelEvent(Builder builder) {
		this.type = builder.type;
		this.traceId = builder.traceId;
		this.sequence = builder.sequence;
		this.provider = builder.provider;
		this.model = builder.model;
		this.responseId = builder.responseId;
		this.text = builder.text;
		this.index = builder.index;
		this.toolCallId = builder.toolCallId;
		this.toolName = builder.toolName;
		this.argumentsDelta = builder.argumentsDelta;
		this.usage = builder.usage;
		this.response = builder.response;
		this.error = builder.error;
	}

	public static Builder builder(ModelEventType type, String traceId, long sequence, String provider) {
		return new Builder(type, traceId, sequence, provider);
	}

	public ModelEventType getType() {
		return type;
	}

	public String getTraceId() {
		return traceId;
	}

	public long getSequence() {
		return sequence;
	}

	public String getProvider() {
		return provider;
	}

	public String getModel() {
		return model;
	}

	public String getResponseId() {
		return responseId;
	}

	public String getText() {
		return text;
	}

	public Integer getIndex() {
		return index;
	}

	public String getToolCallId() {
		return toolCallId;
	}

	public String getToolName() {
		return toolName;
	}

	public String getArgumentsDelta() {
		return argumentsDelta;
	}

	public Usage getUsage() {
		return usage;
	}

	public ModelResponse getResponse() {
		return response;
	}

	public ErrorInfo getError() {
		return error;
	}

	/**
	 * 转换为契约 JSON 结构，按事件类型输出附加字段
	 */
	public Map<String, Object> toContractMap() {
		Map<String, Object> contractMap = new LinkedHashMap<>();
		contractMap.put("type", type.getValue());
		contractMap.put("traceId", traceId);
		contractMap.put("sequence", sequence);
		contractMap.put("provider", provider);

		if (type == ModelEventType.STARTED) {
			contractMap.put("model", model);
			contractMap.put("id", responseId);
		} else if (type == ModelEventType.TEXT_DELTA) {
			contractMap.put("text", text);
		} else if (type == ModelEventType.TOOL_CALL_DELTA) {
			contractMap.put("index", index);
			contractMap.put("id", toolCallId);
			contractMap.put("name", toolName);
			contractMap.put("argumentsDelta", argumentsDelta);
		} else if (type == ModelEventType.USAGE) {
			contractMap.put("usage", usage.toContractMap());
		} else if (type == ModelEventType.COMPLETED) {
			contractMap.put("response", response.toContractMap());
		} else {
			contractMap.put("error", error.toContractMap());
		}

		return contractMap;
	}

	@Override
	public String toString() {
		return "ModelEvent" + toContractMap();
	}

	/**
	 * ModelEvent 构造器
	 */
	public static final class Builder {

		private final ModelEventType type;
		private final String traceId;
		private final long sequence;
		private final String provider;
		private String model;
		private String responseId;
		private String text;
		private Integer index;
		private String toolCallId;
		private String toolName;
		private String argumentsDelta;
		private Usage usage;
		private ModelResponse response;
		private ErrorInfo error;

		private Builder(ModelEventType type, String traceId, long sequence, String provider) {
			this.type = type;
			this.traceId = traceId;
			this.sequence = sequence;
			this.provider = provider;
		}

		public Builder model(String model) {
			this.model = model;
			return this;
		}

		public Builder responseId(String responseId) {
			this.responseId = responseId;
			return this;
		}

		public Builder text(String text) {
			this.text = text;
			return this;
		}

		public Builder index(Integer index) {
			this.index = index;
			return this;
		}

		public Builder toolCallId(String toolCallId) {
			this.toolCallId = toolCallId;
			return this;
		}

		public Builder toolName(String toolName) {
			this.toolName = toolName;
			return this;
		}

		public Builder argumentsDelta(String argumentsDelta) {
			this.argumentsDelta = argumentsDelta;
			return this;
		}

		public Builder usage(Usage usage) {
			this.usage = usage;
			return this;
		}

		public Builder response(ModelResponse response) {
			this.response = response;
			return this;
		}

		public Builder error(ErrorInfo error) {
			this.error = error;
			return this;
		}

		public ModelEvent build() {
			return new ModelEvent(this);
		}
	}
}
