package io.github.charleshyf.commonllm.core;

/**
 * Agent 运行的最终状态
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public enum AgentStatus {
	COMPLETED("completed"),
	FAILED("failed"),
	CANCELLED("cancelled");

	private final String value;

	AgentStatus(String value) {
		this.value = value;
	}

	public String getValue() {
		return value;
	}
}
