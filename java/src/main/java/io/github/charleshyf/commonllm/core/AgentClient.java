package io.github.charleshyf.commonllm.core;

/**
 * 统一的 Coding Agent 客户端
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public interface AgentClient extends AutoCloseable {

	/**
	 * 同步执行一次运行，运行期失败体现在结果的 status 与 error 中
	 */
	AgentResult run(AgentRequest request);

	/**
	 * 启动运行并返回事件流，启动前的校验失败直接抛出 CommonLlmException
	 */
	AgentStream runStream(AgentRequest request);

	/**
	 * 恢复指定会话继续运行
	 */
	AgentResult resume(String sessionId, AgentRequest request);

	/**
	 * 中断运行，runId 不存在或已结束时返回 false
	 */
	boolean interrupt(String runId);

	AgentCapability capabilities();

	@Override
	void close();
}
