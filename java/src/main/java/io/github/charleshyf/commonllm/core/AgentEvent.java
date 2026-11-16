package io.github.charleshyf.commonllm.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Coding Agent 运行产生的单个事件
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class AgentEvent {

	private final AgentEventType type;
	private final String traceId;
	private final String runId;
	private final String sessionId;
	private final long sequence;
	private final String agent;
	private final Map<String, Object> fields;
	private final AgentUsage usage;
	private final AgentResult result;
	private final ErrorInfo error;
	private final List<FileChange> changes;

	private AgentEvent(Builder builder) {
		this.type = builder.type;
		this.traceId = builder.traceId;
		this.runId = builder.runId;
		this.sessionId = builder.sessionId;
		this.sequence = builder.sequence;
		this.agent = builder.agent;
		this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(builder.fields));
		this.usage = builder.usage;
		this.result = builder.result;
		this.error = builder.error;
		this.changes = builder.changes == null ? null : Collections.unmodifiableList(new ArrayList<>(builder.changes));
	}

	public static Builder builder(AgentEventType type) {
		return new Builder(type);
	}

	public AgentEventType getType() {
		return type;
	}

	public String getTraceId() {
		return traceId;
	}

	public String getRunId() {
		return runId;
	}

	public String getSessionId() {
		return sessionId;
	}

	public long getSequence() {
		return sequence;
	}

	public String getAgent() {
		return agent;
	}

	/**
	 * 读取类型专属的附加字段，字段名与契约一致
	 */
	public Object getField(String fieldName) {
		return fields.get(fieldName);
	}

	public String getText() {
		return (String) fields.get("text");
	}

	public AgentUsage getUsage() {
		return usage;
	}

	public AgentResult getResult() {
		return result;
	}

	public ErrorInfo getError() {
		return error;
	}

	public List<FileChange> getChanges() {
		return changes;
	}

	/**
	 * 转换为契约 JSON 结构，公共字段在前，类型专属字段在后
	 */
	public Map<String, Object> toContractMap() {
		Map<String, Object> contractMap = new LinkedHashMap<>();
		contractMap.put("type", type.getValue());
		contractMap.put("traceId", traceId);
		contractMap.put("runId", runId);
		contractMap.put("sessionId", sessionId);
		contractMap.put("sequence", sequence);
		contractMap.put("agent", agent);

		for (Map.Entry<String, Object> fieldEntry : fields.entrySet()) {
			contractMap.put(fieldEntry.getKey(), fieldEntry.getValue());
		}

		if (usage != null) {
			contractMap.put("usage", usage.toContractMap());
		}

		if (result != null) {
			contractMap.put("result", result.toContractMap());
		}

		if (error != null) {
			contractMap.put("error", error.toContractMap());
		}

		if (changes != null) {
			List<Map<String, Object>> changeMaps = new ArrayList<>();

			for (FileChange change : changes) {
				changeMaps.add(change.toContractMap());
			}

			contractMap.put("changes", changeMaps);
		}

		return contractMap;
	}

	@Override
	public String toString() {
		return "AgentEvent{type=" + type.getValue() + ", runId=" + runId + ", sessionId=" + sessionId + ", sequence=" + sequence + "}";
	}

	/**
	 * AgentEvent 构造器，公共字段由 AgentEventEmitter 填写
	 */
	public static final class Builder {

		private final AgentEventType type;
		private final Map<String, Object> fields = new LinkedHashMap<>();
		private String traceId;
		private String runId;
		private String sessionId;
		private long sequence;
		private String agent;
		private AgentUsage usage;
		private AgentResult result;
		private ErrorInfo error;
		private List<FileChange> changes;

		private Builder(AgentEventType type) {
			this.type = type;
		}

		public Builder field(String fieldName, Object value) {
			this.fields.put(fieldName, value);
			return this;
		}

		public Builder usage(AgentUsage usage) {
			this.usage = usage;
			return this;
		}

		public Builder result(AgentResult result) {
			this.result = result;
			return this;
		}

		public Builder error(ErrorInfo error) {
			this.error = error;
			return this;
		}

		public Builder changes(List<FileChange> changes) {
			this.changes = changes;
			return this;
		}

		public AgentEventType getType() {
			return type;
		}

		/**
		 * 写入公共字段并生成事件
		 */
		public AgentEvent build(String eventTraceId, String eventRunId, String eventSessionId, long eventSequence, String eventAgent) {
			this.traceId = eventTraceId;
			this.runId = eventRunId;
			this.sessionId = eventSessionId;
			this.sequence = eventSequence;
			this.agent = eventAgent;
			return new AgentEvent(this);
		}
	}
}
