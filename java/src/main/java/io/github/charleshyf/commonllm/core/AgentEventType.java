package io.github.charleshyf.commonllm.core;

/**
 * Agent 事件类型
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public enum AgentEventType {
	STARTED("started"),
	MESSAGE("message"),
	TOOL_STARTED("tool_started"),
	TOOL_COMPLETED("tool_completed"),
	FILE_CHANGED("file_changed"),
	APPROVAL_REQUESTED("approval_requested"),
	USAGE("usage"),
	COMPLETED("completed"),
	FAILED("failed"),
	CANCELLED("cancelled"),
	RAW_VENDOR_EVENT("raw_vendor_event");

	private final String value;

	AgentEventType(String value) {
		this.value = value;
	}

	public String getValue() {
		return value;
	}

	public boolean isTerminal() {
		return this == COMPLETED || this == FAILED || this == CANCELLED;
	}
}
