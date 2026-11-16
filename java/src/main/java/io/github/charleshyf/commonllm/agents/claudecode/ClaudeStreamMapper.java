package io.github.charleshyf.commonllm.agents.claudecode;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.charleshyf.commonllm.core.AgentEvent;
import io.github.charleshyf.commonllm.core.AgentEventEmitter;
import io.github.charleshyf.commonllm.core.AgentEventType;
import io.github.charleshyf.commonllm.core.AgentResult;
import io.github.charleshyf.commonllm.core.AgentStatus;
import io.github.charleshyf.commonllm.core.AgentUsage;
import io.github.charleshyf.commonllm.core.ApprovalDecision;
import io.github.charleshyf.commonllm.core.ApprovalKind;
import io.github.charleshyf.commonllm.core.ErrorCategory;
import io.github.charleshyf.commonllm.core.ErrorInfo;
import io.github.charleshyf.commonllm.core.JsonSupport;
import io.github.charleshyf.commonllm.core.TextTruncator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 claude -p stream-json 输出逐行映射为统一 AgentEvent
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
final class ClaudeStreamMapper {

	static final String AGENT = "claude_code";

	// raw_vendor_event 中保留的非法行长度，避免把超长内容放进事件
	private static final int INVALID_LINE_PREVIEW_CODE_POINTS = 1000;

	private final AgentEventEmitter emitter;
	private final int maxToolOutputChars;
	private final Map<String, String> toolNames = new HashMap<>();

	ClaudeStreamMapper(AgentEventEmitter emitter, int maxToolOutputChars) {
		this.emitter = emitter;
		this.maxToolOutputChars = maxToolOutputChars;
	}

	/**
	 * 映射一行输出，非法 JSON 转为 raw_vendor_event 且不中断运行
	 */
	void mapLine(String line) {
		JsonNode message;

		try {
			message = JsonSupport.parseTree(line);
		} catch (JsonProcessingException exception) {
			emitInvalidLine(line);
			return;
		}

		if (message == null || !message.isObject()) {
			emitInvalidLine(line);
			return;
		}

		String messageType = JsonSupport.textOrNull(message, "type");
		String subtype = JsonSupport.textOrNull(message, "subtype");

		if ("system".equals(messageType) && "init".equals(subtype)) {
			emitter.setSessionId(JsonSupport.textOrNull(message, "session_id"));
			emitter.emit(AgentEvent.builder(AgentEventType.STARTED).field("model", JsonSupport.textOrNull(message, "model")).field("workingDirectory", JsonSupport.textOrNull(message, "cwd")));
		} else if ("system".equals(messageType) && "permission_denied".equals(subtype)) {
			emitter.emit(AgentEvent.builder(AgentEventType.APPROVAL_REQUESTED)
				.field("approvalId", JsonSupport.textOrNull(message, "tool_use_id"))
				.field("kind", ApprovalKind.TOOL.getValue())
				.field("summary", JsonSupport.textOrNull(message, "tool_name"))
				.field("decision", ApprovalDecision.DECLINE.getValue()));
		} else if ("system".equals(messageType)) {
			emitRaw("system/" + subtype, message);
		} else if ("stream_event".equals(messageType)) {
			mapStreamEvent(message);
		} else if ("assistant".equals(messageType)) {
			mapAssistant(message);
		} else if ("user".equals(messageType)) {
			mapUser(message);
		} else if ("result".equals(messageType)) {
			mapResult(message);
		} else {
			emitRaw(messageType == null ? "unknown" : messageType, message);
		}
	}

	private void mapStreamEvent(JsonNode message) {
		JsonNode event = JsonSupport.fieldOrNull(message, "event");
		JsonNode delta = JsonSupport.fieldOrNull(event, "delta");

		// 其余 stream_event 与随后的完整消息内容重复，不发出
		if ("content_block_delta".equals(JsonSupport.textOrNull(event, "type")) && "text_delta".equals(JsonSupport.textOrNull(delta, "type"))) {
			String text = JsonSupport.textOrNull(delta, "text");
			emitMessage(text == null ? "" : text, true);
		}
	}

	private void mapAssistant(JsonNode message) {
		JsonNode content = JsonSupport.fieldOrNull(JsonSupport.fieldOrNull(message, "message"), "content");

		if (content == null || !content.isArray()) {
			return;
		}

		for (JsonNode block : content) {
			String blockType = JsonSupport.textOrNull(block, "type");

			if ("text".equals(blockType)) {
				String text = JsonSupport.textOrNull(block, "text");
				emitMessage(text == null ? "" : text, false);
			} else if ("tool_use".equals(blockType)) {
				String toolCallId = JsonSupport.textOrNull(block, "id");
				String toolName = JsonSupport.textOrNull(block, "name");
				toolNames.put(toolCallId, toolName);
				emitter.emit(AgentEvent.builder(AgentEventType.TOOL_STARTED)
					.field("toolCallId", toolCallId)
					.field("toolName", toolName)
					.field("input", JsonSupport.toMap(JsonSupport.fieldOrNull(block, "input"))));
			}
		}
	}

	private void mapUser(JsonNode message) {
		JsonNode content = JsonSupport.fieldOrNull(JsonSupport.fieldOrNull(message, "message"), "content");

		if (content == null || !content.isArray()) {
			return;
		}

		for (JsonNode block : content) {
			if (!"tool_result".equals(JsonSupport.textOrNull(block, "type"))) {
				continue;
			}

			String toolCallId = JsonSupport.textOrNull(block, "tool_use_id");
			String output = TextTruncator.truncateCodePoints(stringifyToolResult(JsonSupport.fieldOrNull(block, "content")), maxToolOutputChars);
			emitter.emit(AgentEvent.builder(AgentEventType.TOOL_COMPLETED)
				.field("toolCallId", toolCallId)
				.field("toolName", toolNames.get(toolCallId))
				.field("output", output)
				.field("isError", JsonSupport.isTrue(block, "is_error")));
		}
	}

	private void mapResult(JsonNode message) {
		AgentUsage usage = buildUsage(message);
		emitter.emit(AgentEvent.builder(AgentEventType.USAGE).usage(usage));
		String subtype = JsonSupport.textOrNull(message, "subtype");

		if ("success".equals(subtype) && !JsonSupport.isTrue(message, "is_error")) {
			String finalText = JsonSupport.textOrNull(message, "result");
			AgentResult result = new AgentResult(emitter.getRunId(), emitter.getSessionId(), AgentStatus.COMPLETED, finalText, null, usage, null, null);
			emitter.emit(AgentEvent.builder(AgentEventType.COMPLETED).result(result));
			return;
		}

		emitter.emit(AgentEvent.builder(AgentEventType.FAILED).error(buildResultError(message, subtype)));
	}

	/**
	 * 用量：inputTokens 为含缓存创建与缓存命中的总输入量
	 */
	private static AgentUsage buildUsage(JsonNode resultMessage) {
		JsonNode usageNode = JsonSupport.fieldOrNull(resultMessage, "usage");
		Long inputTokens = JsonSupport.longOrNull(usageNode, "input_tokens");
		Long cacheCreation = JsonSupport.longOrNull(usageNode, "cache_creation_input_tokens");
		Long cacheRead = JsonSupport.longOrNull(usageNode, "cache_read_input_tokens");
		Long outputTokens = JsonSupport.longOrNull(usageNode, "output_tokens");
		Long totalInput = null;

		if (inputTokens != null) {
			totalInput = inputTokens + (cacheCreation == null ? 0 : cacheCreation) + (cacheRead == null ? 0 : cacheRead);
		}

		Long totalTokens = totalInput != null && outputTokens != null ? totalInput + outputTokens : null;
		Long reasoningTokens = JsonSupport.longOrNull(JsonSupport.fieldOrNull(usageNode, "output_tokens_details"), "thinking_tokens");
		return new AgentUsage(totalInput, outputTokens, cacheRead, reasoningTokens, totalTokens, JsonSupport.doubleOrNull(resultMessage, "total_cost_usd"), JsonSupport.longOrNull(resultMessage, "num_turns"));
	}

	private static ErrorInfo buildResultError(JsonNode resultMessage, String subtype) {
		List<String> errorMessages = new ArrayList<>();
		JsonNode errors = JsonSupport.fieldOrNull(resultMessage, "errors");

		if (errors != null && errors.isArray()) {
			for (JsonNode errorNode : errors) {
				errorMessages.add(errorNode.isTextual() ? errorNode.asText() : errorNode.toString());
			}
		}

		String message = errorMessages.isEmpty() ? "Claude Code 执行失败：" + subtype : String.join("; ", errorMessages);
		return ErrorInfo.builder(ErrorCategory.PROVIDER_ERROR, message).provider(AGENT).vendorCode(subtype).build();
	}

	/**
	 * tool_result 的 content 为数组时只拼接 text 元素，以换行连接
	 */
	private static String stringifyToolResult(JsonNode content) {
		if (content == null) {
			return null;
		}

		if (content.isTextual()) {
			return content.asText();
		}

		if (!content.isArray()) {
			return content.toString();
		}

		List<String> textParts = new ArrayList<>();

		for (JsonNode part : content) {
			if ("text".equals(JsonSupport.textOrNull(part, "type"))) {
				String text = JsonSupport.textOrNull(part, "text");
				textParts.add(text == null ? "" : text);
			}
		}

		return String.join("\n", textParts);
	}

	private void emitMessage(String text, boolean isDelta) {
		emitter.emit(AgentEvent.builder(AgentEventType.MESSAGE).field("role", "assistant").field("text", text).field("isDelta", isDelta));
	}

	private void emitRaw(String vendorType, JsonNode message) {
		emitter.emit(AgentEvent.builder(AgentEventType.RAW_VENDOR_EVENT).field("vendorType", vendorType).field("payload", JsonSupport.toMap(message)));
	}

	private void emitInvalidLine(String line) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("line", TextTruncator.truncateCodePoints(line, INVALID_LINE_PREVIEW_CODE_POINTS));
		emitter.emit(AgentEvent.builder(AgentEventType.RAW_VENDOR_EVENT).field("vendorType", "invalid_json").field("payload", payload));
	}
}
