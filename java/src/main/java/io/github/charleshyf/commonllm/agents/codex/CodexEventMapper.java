package io.github.charleshyf.commonllm.agents.codex;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.charleshyf.commonllm.core.AgentEvent;
import io.github.charleshyf.commonllm.core.AgentEventEmitter;
import io.github.charleshyf.commonllm.core.AgentEventType;
import io.github.charleshyf.commonllm.core.AgentResult;
import io.github.charleshyf.commonllm.core.AgentStatus;
import io.github.charleshyf.commonllm.core.AgentUsage;
import io.github.charleshyf.commonllm.core.ErrorCategory;
import io.github.charleshyf.commonllm.core.ErrorInfo;
import io.github.charleshyf.commonllm.core.FileChange;
import io.github.charleshyf.commonllm.core.JsonSupport;
import io.github.charleshyf.commonllm.core.TextTruncator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把 Codex App Server 的线程结果与通知映射为统一 AgentEvent
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
final class CodexEventMapper {

	static final String AGENT = "codex";

	private static final Set<String> TOOL_ITEM_TYPES = Set.of("commandExecution", "mcpToolCall", "webSearch");
	private static final Set<String> FAILED_ITEM_STATUSES = Set.of("failed", "declined");

	private final AgentEventEmitter emitter;
	private final int maxToolOutputChars;
	private String startedModel;
	private String workingDirectory;
	private volatile String turnId;
	private JsonNode baselineTotal;
	private JsonNode turnTotal;
	private String finalText = "";

	CodexEventMapper(AgentEventEmitter emitter, int maxToolOutputChars, String workingDirectory) {
		this.emitter = emitter;
		this.maxToolOutputChars = maxToolOutputChars;
		this.workingDirectory = workingDirectory;
	}

	String getTurnId() {
		return turnId;
	}

	void setTurnId(String turnId) {
		if (this.turnId == null) {
			this.turnId = turnId;
		}
	}

	/**
	 * thread/start 或 thread/resume 的响应：记录 sessionId、模型与工作目录，不发事件
	 */
	void mapThreadResult(JsonNode result) {
		emitter.setSessionId(JsonSupport.textOrNull(JsonSupport.fieldOrNull(result, "thread"), "id"));
		startedModel = JsonSupport.textOrNull(result, "model");
		String resultCwd = JsonSupport.textOrNull(result, "cwd");

		if (resultCwd != null) {
			workingDirectory = resultCwd;
		}
	}

	/**
	 * 映射服务端通知，无法映射的通知转为 raw_vendor_event
	 */
	void mapNotification(String method, JsonNode params, JsonNode rawMessage) {
		if ("thread/tokenUsage/updated".equals(method)) {
			JsonNode total = JsonSupport.fieldOrNull(JsonSupport.fieldOrNull(params, "tokenUsage"), "total");

			// resume 时服务端先回放上一轮累计值，turnId 不同的值作为本轮基线
			if (turnId != null && turnId.equals(JsonSupport.textOrNull(params, "turnId"))) {
				turnTotal = total;
			} else {
				baselineTotal = total;
			}

			return;
		}

		if ("turn/started".equals(method)) {
			turnId = JsonSupport.textOrNull(JsonSupport.fieldOrNull(params, "turn"), "id");
			finalText = "";
			emitter.emit(AgentEvent.builder(AgentEventType.STARTED).field("model", startedModel).field("workingDirectory", workingDirectory));
			return;
		}

		if ("item/agentMessage/delta".equals(method)) {
			String delta = JsonSupport.textOrNull(params, "delta");
			emitMessage(delta == null ? "" : delta, true);
			return;
		}

		if ("item/started".equals(method) && mapItemStarted(JsonSupport.fieldOrNull(params, "item"))) {
			return;
		}

		if ("item/completed".equals(method) && mapItemCompleted(JsonSupport.fieldOrNull(params, "item"))) {
			return;
		}

		if ("turn/completed".equals(method)) {
			mapTurnCompleted(JsonSupport.fieldOrNull(params, "turn"));
			return;
		}

		emitRaw(method, rawMessage);
	}

	/**
	 * 发出审批事件，decision 为审批处理器的结果
	 */
	void emitApproval(String approvalId, String kind, String summary, String decision) {
		emitter.emit(AgentEvent.builder(AgentEventType.APPROVAL_REQUESTED)
			.field("approvalId", approvalId)
			.field("kind", kind)
			.field("summary", summary)
			.field("decision", decision));
	}

	void emitRaw(String vendorType, JsonNode rawMessage) {
		emitter.emit(AgentEvent.builder(AgentEventType.RAW_VENDOR_EVENT).field("vendorType", vendorType).field("payload", JsonSupport.toMap(rawMessage)));
	}

	private boolean mapItemStarted(JsonNode item) {
		String itemType = JsonSupport.textOrNull(item, "type");

		if ("userMessage".equals(itemType)) {
			return true;
		}

		if (!TOOL_ITEM_TYPES.contains(itemType)) {
			return false;
		}

		emitter.emit(AgentEvent.builder(AgentEventType.TOOL_STARTED)
			.field("toolCallId", JsonSupport.textOrNull(item, "id"))
			.field("toolName", buildToolName(item))
			.field("input", buildToolInput(item)));
		return true;
	}

	private boolean mapItemCompleted(JsonNode item) {
		String itemType = JsonSupport.textOrNull(item, "type");

		if ("userMessage".equals(itemType)) {
			return true;
		}

		if ("agentMessage".equals(itemType)) {
			String text = JsonSupport.textOrNull(item, "text");
			finalText = text == null ? "" : text;
			emitMessage(finalText, false);
			return true;
		}

		if (TOOL_ITEM_TYPES.contains(itemType)) {
			Long exitCode = JsonSupport.longOrNull(item, "exitCode");
			boolean isError = FAILED_ITEM_STATUSES.contains(JsonSupport.textOrNull(item, "status")) || (exitCode != null && exitCode != 0);
			emitter.emit(AgentEvent.builder(AgentEventType.TOOL_COMPLETED)
				.field("toolCallId", JsonSupport.textOrNull(item, "id"))
				.field("toolName", buildToolName(item))
				.field("output", TextTruncator.truncateCodePoints(JsonSupport.textOrNull(item, "aggregatedOutput"), maxToolOutputChars))
				.field("isError", isError));
			return true;
		}

		if ("fileChange".equals(itemType)) {
			emitter.emit(AgentEvent.builder(AgentEventType.FILE_CHANGED).changes(buildFileChanges(item)));
			return true;
		}

		return false;
	}

	private void mapTurnCompleted(JsonNode turn) {
		AgentUsage usage = buildTurnUsage();

		if (usage != null) {
			emitter.emit(AgentEvent.builder(AgentEventType.USAGE).usage(usage));
		}

		String status = JsonSupport.textOrNull(turn, "status");

		if ("completed".equals(status)) {
			AgentResult result = new AgentResult(emitter.getRunId(), emitter.getSessionId(), AgentStatus.COMPLETED, finalText, null, usage, null, null);
			emitter.emit(AgentEvent.builder(AgentEventType.COMPLETED).result(result));
		} else if ("interrupted".equals(status)) {
			emitter.emit(AgentEvent.builder(AgentEventType.CANCELLED));
		} else {
			emitter.emit(AgentEvent.builder(AgentEventType.FAILED).error(buildTurnError(JsonSupport.fieldOrNull(turn, "error"))));
		}
	}

	/**
	 * 本轮最后一次累计值减去本轮开始前的累计值，没有前值时按 0 计
	 */
	private AgentUsage buildTurnUsage() {
		if (turnTotal == null) {
			return null;
		}

		return new AgentUsage(
			subtract(turnTotal, baselineTotal, "inputTokens"),
			subtract(turnTotal, baselineTotal, "outputTokens"),
			subtract(turnTotal, baselineTotal, "cachedInputTokens"),
			subtract(turnTotal, baselineTotal, "reasoningOutputTokens"),
			subtract(turnTotal, baselineTotal, "totalTokens"),
			null,
			null
		);
	}

	private static Long subtract(JsonNode current, JsonNode baseline, String fieldName) {
		Long currentValue = JsonSupport.longOrNull(current, fieldName);

		if (currentValue == null) {
			return null;
		}

		Long baselineValue = JsonSupport.longOrNull(baseline, fieldName);
		return baselineValue == null ? currentValue : currentValue - baselineValue;
	}

	private static ErrorInfo buildTurnError(JsonNode turnError) {
		String message = JsonSupport.textOrNull(turnError, "message");
		JsonNode errorInfo = JsonSupport.fieldOrNull(turnError, "codexErrorInfo");
		return ErrorInfo.builder(ErrorCategory.PROVIDER_ERROR, message == null ? "Codex turn 执行失败" : message)
			.provider(AGENT)
			.vendorCode(errorInfo == null ? null : JsonSupport.toJson(errorInfo))
			.build();
	}

	private static String buildToolName(JsonNode item) {
		String itemType = JsonSupport.textOrNull(item, "type");

		if ("commandExecution".equals(itemType)) {
			return "command_execution";
		}

		if ("mcpToolCall".equals(itemType)) {
			return JsonSupport.textOrNull(item, "server") + "." + JsonSupport.textOrNull(item, "tool");
		}

		return "web_search";
	}

	private static Map<String, Object> buildToolInput(JsonNode item) {
		String itemType = JsonSupport.textOrNull(item, "type");

		if ("mcpToolCall".equals(itemType)) {
			return JsonSupport.toMap(JsonSupport.fieldOrNull(item, "arguments"));
		}

		Map<String, Object> input = new LinkedHashMap<>();

		if ("commandExecution".equals(itemType)) {
			input.put("command", JsonSupport.textOrNull(item, "command"));
			input.put("cwd", JsonSupport.textOrNull(item, "cwd"));
		} else {
			input.put("query", JsonSupport.textOrNull(item, "query"));
		}

		return input;
	}

	private static List<FileChange> buildFileChanges(JsonNode item) {
		List<FileChange> changes = new ArrayList<>();
		JsonNode changeNodes = JsonSupport.fieldOrNull(item, "changes");

		if (changeNodes == null || !changeNodes.isArray()) {
			return changes;
		}

		for (JsonNode changeNode : changeNodes) {
			JsonNode kindNode = JsonSupport.fieldOrNull(changeNode, "kind");
			String kind = "update";

			if (kindNode != null && kindNode.isObject() && kindNode.has("type")) {
				kind = kindNode.get("type").asText();
			} else if (kindNode != null && kindNode.isTextual()) {
				kind = kindNode.asText();
			}

			changes.add(new FileChange(JsonSupport.textOrNull(changeNode, "path"), kind));
		}

		return changes;
	}

	private void emitMessage(String text, boolean isDelta) {
		emitter.emit(AgentEvent.builder(AgentEventType.MESSAGE).field("role", "assistant").field("text", text).field("isDelta", isDelta));
	}
}
