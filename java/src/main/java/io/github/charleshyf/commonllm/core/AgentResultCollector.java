package io.github.charleshyf.commonllm.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 读取完整 Agent 事件流并汇总为 AgentResult
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class AgentResultCollector {

	private AgentResultCollector() {
	}

	/**
	 * 读到终止事件为止；completed 取事件内结果，failed / cancelled 由已收到的事件组装结果
	 */
	public static AgentResult collect(AgentStream stream, boolean shouldCollectEvents) {
		List<AgentEvent> events = new ArrayList<>();
		AgentEvent terminalEvent = null;
		AgentUsage lastUsage = null;
		String lastText = "";
		String sessionId = null;

		try (AgentStream runStream = stream) {
			while (runStream.hasNext()) {
				AgentEvent event = runStream.next();
				events.add(event);

				if (event.getSessionId() != null) {
					sessionId = event.getSessionId();
				}

				if (event.getType() == AgentEventType.USAGE) {
					lastUsage = event.getUsage();
				} else if (event.getType() == AgentEventType.MESSAGE && Boolean.FALSE.equals(event.getField("isDelta"))) {
					lastText = event.getText();
				}

				if (event.getType().isTerminal()) {
					terminalEvent = event;
					break;
				}
			}
		}

		List<AgentEvent> collectedEvents = shouldCollectEvents ? events : null;

		if (terminalEvent == null) {
			ErrorInfo error = ErrorInfo.builder(ErrorCategory.INTERNAL_ERROR, "Agent 事件流在终止事件前结束").build();
			return new AgentResult(stream.getRunId(), sessionId, AgentStatus.FAILED, lastText, collectedEvents, lastUsage, null, error);
		}

		if (terminalEvent.getType() == AgentEventType.COMPLETED) {
			return terminalEvent.getResult().withEventsAndExitCode(collectedEvents, null);
		}

		if (terminalEvent.getType() == AgentEventType.CANCELLED) {
			ErrorInfo error = ErrorInfo.builder(ErrorCategory.CANCELLED, "Agent 运行已取消").provider(terminalEvent.getAgent()).build();
			return new AgentResult(stream.getRunId(), sessionId, AgentStatus.CANCELLED, lastText, collectedEvents, lastUsage, null, error);
		}

		return new AgentResult(stream.getRunId(), sessionId, AgentStatus.FAILED, lastText, collectedEvents, lastUsage, null, terminalEvent.getError());
	}
}
