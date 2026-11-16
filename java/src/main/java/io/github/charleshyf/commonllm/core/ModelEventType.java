package io.github.charleshyf.commonllm.core;

/**
 * 模型流式事件类型
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public enum ModelEventType {
	STARTED("started"),
	TEXT_DELTA("text_delta"),
	TOOL_CALL_DELTA("tool_call_delta"),
	USAGE("usage"),
	COMPLETED("completed"),
	ERROR("error");

	private final String value;

	ModelEventType(String value) {
		this.value = value;
	}

	public String getValue() {
		return value;
	}

	public boolean isTerminal() {
		return this == COMPLETED || this == ERROR;
	}
}
