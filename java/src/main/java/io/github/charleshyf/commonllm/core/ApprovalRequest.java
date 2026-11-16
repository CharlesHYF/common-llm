package io.github.charleshyf.commonllm.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 运行中发起的审批请求
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class ApprovalRequest {

	private final String approvalId;
	private final ApprovalKind kind;
	private final String summary;
	private final Map<String, Object> vendorParams;

	public ApprovalRequest(String approvalId, ApprovalKind kind, String summary, Map<String, Object> vendorParams) {
		this.approvalId = approvalId;
		this.kind = kind;
		this.summary = summary;
		this.vendorParams = Collections.unmodifiableMap(new LinkedHashMap<>(vendorParams));
	}

	public String getApprovalId() {
		return approvalId;
	}

	public ApprovalKind getKind() {
		return kind;
	}

	public String getSummary() {
		return summary;
	}

	/**
	 * 厂商原始审批参数，便于调用方按需判断
	 */
	public Map<String, Object> getVendorParams() {
		return vendorParams;
	}
}
