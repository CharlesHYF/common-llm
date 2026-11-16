package io.github.charleshyf.commonllm.core;

/**
 * 模型消息角色枚举
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public enum MessageRole {
	SYSTEM("system"),
	USER("user"),
	ASSISTANT("assistant"),
	TOOL("tool");

	private final String value;

	MessageRole(String value) {
		this.value = value;
	}

	public String getValue() {
		return value;
	}
}
