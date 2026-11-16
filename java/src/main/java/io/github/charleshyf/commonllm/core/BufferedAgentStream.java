package io.github.charleshyf.commonllm.core;

import java.util.NoSuchElementException;

/**
 * 从事件缓存读取的 Agent 事件流
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class BufferedAgentStream implements AgentStream {

	private final String runId;
	private final AgentEventBuffer buffer;
	private final Runnable cancelAction;
	private AgentEvent nextEvent;
	private boolean isEnded;
	private boolean isClosed;

	public BufferedAgentStream(String runId, AgentEventBuffer buffer, Runnable cancelAction) {
		this.runId = runId;
		this.buffer = buffer;
		this.cancelAction = cancelAction;
	}

	/**
	 * 组装只含一个 failed 终止事件的流，用于启动前的预检失败
	 */
	public static BufferedAgentStream failed(String traceId, String runId, String agent, ErrorInfo error) {
		AgentEventBuffer buffer = new AgentEventBuffer(1);
		AgentEventEmitter emitter = new AgentEventEmitter(traceId, runId, agent, buffer);
		emitter.emit(AgentEvent.builder(AgentEventType.FAILED).error(error));
		return new BufferedAgentStream(runId, buffer, () -> {
		});
	}

	@Override
	public String getRunId() {
		return runId;
	}

	@Override
	public boolean hasNext() {
		if (nextEvent != null) {
			return true;
		}

		if (isEnded || isClosed) {
			return false;
		}

		nextEvent = buffer.take();

		if (nextEvent == null) {
			isEnded = true;
			return false;
		}

		return true;
	}

	@Override
	public AgentEvent next() {
		if (!hasNext()) {
			throw new NoSuchElementException("Agent 事件流已结束");
		}

		AgentEvent event = nextEvent;
		nextEvent = null;

		if (event.getType().isTerminal()) {
			isEnded = true;
		}

		return event;
	}

	/**
	 * 未读到终止事件就关闭视为取消运行
	 */
	@Override
	public void close() {
		if (isClosed) {
			return;
		}

		isClosed = true;

		if (!isEnded) {
			cancelAction.run();
		}

		buffer.abandon();
	}
}
