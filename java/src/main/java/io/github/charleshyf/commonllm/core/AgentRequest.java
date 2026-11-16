package io.github.charleshyf.commonllm.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一 Coding Agent 调用请求
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class AgentRequest {

	private final String agent;
	private final String prompt;
	private final String workingDirectory;
	private final String sessionId;
	private final String model;
	private final Double timeoutSeconds;
	private final String permissionMode;
	private final String sandboxMode;
	private final List<String> allowedTools;
	private final List<String> envAllowlist;
	private final Map<String, Object> nativeOptions;
	private final String traceId;
	private final ApprovalHandler approvalHandler;

	private AgentRequest(Builder builder) {
		this.agent = builder.agent;
		this.prompt = builder.prompt;
		this.workingDirectory = builder.workingDirectory;
		this.sessionId = builder.sessionId;
		this.model = builder.model;
		this.timeoutSeconds = builder.timeoutSeconds;
		this.permissionMode = builder.permissionMode;
		this.sandboxMode = builder.sandboxMode;
		this.allowedTools = copyList(builder.allowedTools);
		this.envAllowlist = copyList(builder.envAllowlist);
		this.nativeOptions = Collections.unmodifiableMap(new LinkedHashMap<>(builder.nativeOptions));
		this.traceId = builder.traceId;
		this.approvalHandler = builder.approvalHandler;
	}

	public static Builder builder() {
		return new Builder();
	}

	public String getAgent() {
		return agent;
	}

	public String getPrompt() {
		return prompt;
	}

	public String getWorkingDirectory() {
		return workingDirectory;
	}

	public String getSessionId() {
		return sessionId;
	}

	public String getModel() {
		return model;
	}

	public Double getTimeoutSeconds() {
		return timeoutSeconds;
	}

	public String getPermissionMode() {
		return permissionMode;
	}

	public String getSandboxMode() {
		return sandboxMode;
	}

	/**
	 * 未设置时返回 null，与空列表区分
	 */
	public List<String> getAllowedTools() {
		return allowedTools;
	}

	public List<String> getEnvAllowlist() {
		return envAllowlist;
	}

	public Map<String, Object> getNativeOptions() {
		return nativeOptions;
	}

	public String getTraceId() {
		return traceId;
	}

	public ApprovalHandler getApprovalHandler() {
		return approvalHandler;
	}

	/**
	 * 基于当前请求组装新的构造器
	 */
	public Builder toBuilder() {
		Builder builder = new Builder();
		builder.agent = agent;
		builder.prompt = prompt;
		builder.workingDirectory = workingDirectory;
		builder.sessionId = sessionId;
		builder.model = model;
		builder.timeoutSeconds = timeoutSeconds;
		builder.permissionMode = permissionMode;
		builder.sandboxMode = sandboxMode;
		builder.allowedTools = allowedTools;
		builder.envAllowlist = envAllowlist;
		builder.nativeOptions = new LinkedHashMap<>(nativeOptions);
		builder.traceId = traceId;
		builder.approvalHandler = approvalHandler;
		return builder;
	}

	@Override
	public String toString() {
		return "AgentRequest{agent=" + agent + ", workingDirectory=" + workingDirectory + ", sessionId=" + sessionId + ", traceId=" + traceId + "}";
	}

	private static List<String> copyList(List<String> values) {
		if (values == null) {
			return null;
		}

		return Collections.unmodifiableList(new ArrayList<>(values));
	}

	/**
	 * AgentRequest 构造器
	 */
	public static final class Builder {

		private String agent;
		private String prompt;
		private String workingDirectory;
		private String sessionId;
		private String model;
		private Double timeoutSeconds;
		private String permissionMode;
		private String sandboxMode;
		private List<String> allowedTools;
		private List<String> envAllowlist;
		private Map<String, Object> nativeOptions = new LinkedHashMap<>();
		private String traceId;
		private ApprovalHandler approvalHandler;

		private Builder() {
		}

		public Builder agent(String agent) {
			this.agent = agent;
			return this;
		}

		public Builder prompt(String prompt) {
			this.prompt = prompt;
			return this;
		}

		public Builder workingDirectory(String workingDirectory) {
			this.workingDirectory = workingDirectory;
			return this;
		}

		public Builder sessionId(String sessionId) {
			this.sessionId = sessionId;
			return this;
		}

		public Builder model(String model) {
			this.model = model;
			return this;
		}

		public Builder timeoutSeconds(Double timeoutSeconds) {
			this.timeoutSeconds = timeoutSeconds;
			return this;
		}

		public Builder permissionMode(String permissionMode) {
			this.permissionMode = permissionMode;
			return this;
		}

		public Builder sandboxMode(String sandboxMode) {
			this.sandboxMode = sandboxMode;
			return this;
		}

		public Builder allowedTools(List<String> allowedTools) {
			this.allowedTools = allowedTools;
			return this;
		}

		public Builder envAllowlist(List<String> envAllowlist) {
			this.envAllowlist = envAllowlist;
			return this;
		}

		public Builder nativeOptions(Map<String, Object> nativeOptions) {
			this.nativeOptions = new LinkedHashMap<>(nativeOptions);
			return this;
		}

		public Builder putNativeOption(String key, Object value) {
			this.nativeOptions.put(key, value);
			return this;
		}

		public Builder traceId(String traceId) {
			this.traceId = traceId;
			return this;
		}

		public Builder approvalHandler(ApprovalHandler approvalHandler) {
			this.approvalHandler = approvalHandler;
			return this;
		}

		public AgentRequest build() {
			return new AgentRequest(this);
		}
	}
}
