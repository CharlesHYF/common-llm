package io.github.charleshyf.commonllm.core;

/**
 * 处理 Agent 审批请求的回调
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
@FunctionalInterface
public interface ApprovalHandler {

	/**
	 * 返回审批决定，抛出异常时按拒绝处理
	 */
	ApprovalDecision decide(ApprovalRequest request);
}
