package io.github.charleshyf.commonllm.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Coding Agent 单次运行的结果
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class AgentResult {

	private final String runId;
	private final String sessionId;
	private final AgentStatus status;
	private final String finalText;
	private final List<AgentEvent> events;
	private final AgentUsage usage;
	private final Integer exitCode;
	private final ErrorInfo error;

	public AgentResult(String runId, String sessionId, AgentStatus status, String finalText, List<AgentEvent> events, AgentUsage usage, Integer exitCode, ErrorInfo error) {
		this.runId = runId;
		this.sessionId = sessionId;
		this.status = status;
		this.finalText = finalText == null ? "" : finalText;
		this.events = events == null ? null : Collections.unmodifiableList(new ArrayList<>(events));
		this.usage = usage;
		this.exitCode = exitCode;
		this.error = error;
	}

	/**
	 * 返回附带事件列表与退出码的新结果
	 */
	public AgentResult withEventsAndExitCode(List<AgentEvent> collectedEvents, Integer processExitCode) {
		return new AgentResult(runId, sessionId, status, finalText, collectedEvents, usage, processExitCode, error);
	}

	public String getRunId() {
		return runId;
	}

	public String getSessionId() {
		return sessionId;
	}

	public AgentStatus getStatus() {
		return status;
	}

	public String getFinalText() {
		return finalText;
	}

	public List<AgentEvent> getEvents() {
		return events;
	}

	public AgentUsage getUsage() {
		return usage;
	}

	public Integer getExitCode() {
		return exitCode;
	}

	public ErrorInfo getError() {
		return error;
	}

	/**
	 * 转换为契约 JSON 结构，events 仅在收集时输出
	 */
	public Map<String, Object> toContractMap() {
		Map<String, Object> contractMap = new LinkedHashMap<>();
		contractMap.put("runId", runId);
		contractMap.put("sessionId", sessionId);
		contractMap.put("status", status.getValue());
		contractMap.put("finalText", finalText);

		if (events != null) {
			List<Map<String, Object>> eventMaps = new ArrayList<>();

			for (AgentEvent event : events) {
				eventMaps.add(event.toContractMap());
			}

			contractMap.put("events", eventMaps);
		}

		contractMap.put("usage", usage == null ? null : usage.toContractMap());
		contractMap.put("exitCode", exitCode);
		contractMap.put("error", error == null ? null : error.toContractMap());
		return contractMap;
	}

	@Override
	public String toString() {
		return "AgentResult{runId=" + runId + ", sessionId=" + sessionId + ", status=" + status + ", usage=" + usage + ", exitCode=" + exitCode + ", error=" + error + "}";
	}
}
