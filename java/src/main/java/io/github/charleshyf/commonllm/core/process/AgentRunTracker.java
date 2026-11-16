package io.github.charleshyf.commonllm.core.process;

import io.github.charleshyf.commonllm.core.CommonLlmException;
import io.github.charleshyf.commonllm.core.ErrorCategory;
import io.github.charleshyf.commonllm.core.ErrorInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * 限制单个 AgentClient 的并发运行数并按 runId 登记取消操作
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class AgentRunTracker {

	private final String agent;
	private final int maxConcurrentRuns;
	private final Semaphore permits;
	private final Map<String, Runnable> cancelActions = new ConcurrentHashMap<>();

	public AgentRunTracker(String agent, int maxConcurrentRuns) {
		this.agent = agent;
		this.maxConcurrentRuns = maxConcurrentRuns;
		this.permits = new Semaphore(maxConcurrentRuns);
	}

	/**
	 * 占用一个运行名额，超出上限报可重试的 RATE_LIMITED
	 */
	public void acquire() {
		if (!permits.tryAcquire()) {
			throw new CommonLlmException(ErrorInfo.builder(ErrorCategory.RATE_LIMITED, "并发运行数已达上限 max_concurrent_runs=" + maxConcurrentRuns).provider(agent).retriable(true).build());
		}
	}

	public void register(String runId, Runnable cancelAction) {
		cancelActions.put(runId, cancelAction);
	}

	/**
	 * 运行结束时注销并释放名额，重复调用只释放一次
	 */
	public void release(String runId) {
		if (cancelActions.remove(runId) != null) {
			permits.release();
		}
	}

	/**
	 * 取消指定运行，runId 不存在时返回 false
	 */
	public boolean cancel(String runId) {
		Runnable cancelAction = cancelActions.get(runId);

		if (cancelAction == null) {
			return false;
		}

		cancelAction.run();
		return true;
	}

	public void cancelAll() {
		List<Runnable> actions = new ArrayList<>(cancelActions.values());

		for (Runnable cancelAction : actions) {
			cancelAction.run();
		}
	}
}
