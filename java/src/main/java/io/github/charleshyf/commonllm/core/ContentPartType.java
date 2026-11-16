package io.github.charleshyf.commonllm.core;

/**
 * 消息内容片段的类型枚举
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public enum ContentPartType {
	TEXT("text"),
	IMAGE("image"),
	TOOL_CALL("tool_call"),
	TOOL_RESULT("tool_result");

	private final String value;

	ContentPartType(String value) {
		this.value = value;
	}

	public String getValue() {
		return value;
	}
}
