package io.github.charleshyf.commonllm.core;

/**
 * 为 Agent 事件填写公共字段与序号并写入缓存
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class AgentEventEmitter {

	private final String traceId;
	private final String runId;
	private final String agent;
	private final AgentEventBuffer buffer;
	private String sessionId;
	private long nextSequence;
	private boolean hasTerminalEvent;

	public AgentEventEmitter(String traceId, String runId, String agent, AgentEventBuffer buffer) {
		this.traceId = traceId;
		this.runId = runId;
		this.agent = agent;
		this.buffer = buffer;
	}

	public synchronized void setSessionId(String sessionId) {
		this.sessionId = sessionId;
	}

	public synchronized String getSessionId() {
		return sessionId;
	}

	public String getTraceId() {
		return traceId;
	}

	public String getRunId() {
		return runId;
	}

	public String getAgent() {
		return agent;
	}

	/**
	 * 发出事件，终止事件之后的事件一律丢弃并返回 null
	 */
	public AgentEvent emit(AgentEvent.Builder builder) {
		AgentEvent event;

		synchronized (this) {
			if (hasTerminalEvent) {
				return null;
			}

			event = builder.build(traceId, runId, sessionId, nextSequence, agent);
			nextSequence++;

			if (event.getType().isTerminal()) {
				hasTerminalEvent = true;
			}
		}

		buffer.add(event);
		return event;
	}

	public synchronized boolean hasTerminalEvent() {
		return hasTerminalEvent;
	}
}
