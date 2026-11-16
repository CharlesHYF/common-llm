package io.github.charleshyf.commonllm.core;

import java.util.Iterator;

/**
 * 同步迭代的 Agent 事件流，关闭即取消运行
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public interface AgentStream extends Iterator<AgentEvent>, AutoCloseable {

	/**
	 * 本次运行的 runId，可用于 AgentClient.interrupt
	 */
	String getRunId();

	@Override
	void close();
}
