package io.github.charleshyf.commonllm.core;

/**
 * Coding Agent 能力标识
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public enum AgentFeature {
	STREAMING("streaming"),
	SESSION_RESUME("session_resume"),
	APPROVALS("approvals"),
	INTERRUPT("interrupt"),
	COST("cost");

	private final String value;

	AgentFeature(String value) {
		this.value = value;
	}

	public String getValue() {
		return value;
	}
}
