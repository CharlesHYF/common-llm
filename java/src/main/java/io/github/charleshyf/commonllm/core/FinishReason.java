package io.github.charleshyf.commonllm.core;

/**
 * 统一的模型结束原因
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public enum FinishReason {
	STOP("stop"),
	LENGTH("length"),
	TOOL_CALLS("tool_calls"),
	CONTENT_FILTER("content_filter"),
	OTHER("other");

	private final String value;

	FinishReason(String value) {
		this.value = value;
	}

	public String getValue() {
		return value;
	}
}
