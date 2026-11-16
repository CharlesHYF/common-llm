package io.github.charleshyf.commonllm.core;

/**
 * 审批请求的操作类别
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public enum ApprovalKind {
	COMMAND("command"),
	FILE_CHANGE("file_change"),
	TOOL("tool");

	private final String value;

	ApprovalKind(String value) {
		this.value = value;
	}

	public String getValue() {
		return value;
	}
}
