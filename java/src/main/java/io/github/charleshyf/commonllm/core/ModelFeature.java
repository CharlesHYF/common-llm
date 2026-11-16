package io.github.charleshyf.commonllm.core;

/**
 * 模型 Provider 能力标识
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public enum ModelFeature {
	STREAMING("streaming"),
	TOOL_CALLS("tool_calls"),
	STRUCTURED_OUTPUT("structured_output"),
	VISION("vision"),
	REASONING("reasoning");

	private final String value;

	ModelFeature(String value) {
		this.value = value;
	}

	public String getValue() {
		return value;
	}
}
