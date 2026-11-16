package io.github.charleshyf.commonllm.core;

/**
 * 审批处理器的决定
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public enum ApprovalDecision {
	APPROVE("approve"),
	APPROVE_FOR_SESSION("approve_for_session"),
	DECLINE("decline"),
	CANCEL("cancel");

	private final String value;

	ApprovalDecision(String value) {
		this.value = value;
	}

	public String getValue() {
		return value;
	}
}
