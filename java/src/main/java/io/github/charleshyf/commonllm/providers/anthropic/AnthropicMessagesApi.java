package io.github.charleshyf.commonllm.providers.anthropic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoiceAny;
import com.anthropic.models.messages.ToolChoiceAuto;
import com.anthropic.models.messages.ToolChoiceNone;
import com.anthropic.models.messages.ToolChoiceTool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.anthropic.models.messages.UrlImageSource;
import io.github.charleshyf.commonllm.core.CommonLlmException;
import io.github.charleshyf.commonllm.core.ContentPart;
import io.github.charleshyf.commonllm.core.ContentPartType;
import io.github.charleshyf.commonllm.core.ErrorCategory;
import io.github.charleshyf.commonllm.core.FinishReason;
import io.github.charleshyf.commonllm.core.JsonSupport;
import io.github.charleshyf.commonllm.core.Message;
import io.github.charleshyf.commonllm.core.MessageRole;
import io.github.charleshyf.commonllm.core.ModelRequest;
import io.github.charleshyf.commonllm.core.ModelResponse;
import io.github.charleshyf.commonllm.core.ResponseFormat;
import io.github.charleshyf.commonllm.core.ToolChoice;
import io.github.charleshyf.commonllm.core.ToolChoiceType;
import io.github.charleshyf.commonllm.core.ToolDefinition;
import io.github.charleshyf.commonllm.core.Usage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Anthropic Messages API 的请求组装与响应解析
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
final class AnthropicMessagesApi {

	static final Set<String> MAPPED_FIELDS = Set.of("model", "messages", "system", "max_tokens", "tools", "tool_choice", "output_config", "stream");
	static final String MAX_TOKENS_OPTION = "max_tokens";

	private AnthropicMessagesApi() {
	}

	/**
	 * 把统一请求组装为 MessageCreateParams，连续的 tool 消息合并为一条 user 消息
	 */
	static MessageCreateParams buildParams(ModelRequest request, String model, long maxTokens, Map<String, Object> extraBody, String provider) {
		MessageCreateParams.Builder builder = MessageCreateParams.builder().model(model).maxTokens(maxTokens);
		List<String> systemTexts = new ArrayList<>();
		List<ContentBlockParam> pendingToolResults = new ArrayList<>();

		for (Message message : request.getMessages()) {
			if (message.getRole() == MessageRole.SYSTEM) {
				systemTexts.add(message.joinText());
				continue;
			}

			if (message.getRole() == MessageRole.TOOL) {
				appendToolResults(message, pendingToolResults, provider);
				continue;
			}

			flushToolResults(builder, pendingToolResults);
			List<ContentBlockParam> blocks = buildBlocks(message, provider);

			if (message.getRole() == MessageRole.ASSISTANT) {
				builder.addMessage(MessageParam.ofAssistantOfContentBlockParams(blocks));
			} else {
				builder.addMessage(MessageParam.ofUserOfContentBlockParams(blocks));
			}
		}

		flushToolResults(builder, pendingToolResults);

		if (!systemTexts.isEmpty()) {
			builder.system(String.join("\n\n", systemTexts));
		}

		for (ToolDefinition tool : request.getTools()) {
			builder.addTool(buildTool(tool));
		}

		applyToolChoice(builder, request.getToolChoice());
		applyResponseFormat(builder, request.getResponseFormat());

		for (Map.Entry<String, Object> extraEntry : extraBody.entrySet()) {
			builder.putAdditionalBodyProperty(extraEntry.getKey(), JsonValue.from(extraEntry.getValue()));
		}

		return builder.build();
	}

	/**
	 * 按契约规则映射完整 Message 响应
	 */
	static ModelResponse mapMessage(String provider, JsonNode messageNode, String requestId, String traceId, boolean shouldIncludeRaw) {
		List<ContentPart> contentParts = new ArrayList<>();
		JsonNode contentNode = messageNode.get("content");

		if (contentNode != null && contentNode.isArray()) {
			for (JsonNode blockNode : contentNode) {
				String blockType = JsonSupport.textOrNull(blockNode, "type");

				if ("text".equals(blockType)) {
					String text = JsonSupport.textOrNull(blockNode, "text");
					contentParts.add(ContentPart.text(text == null ? "" : text));
				} else if ("tool_use".equals(blockType)) {
					JsonNode inputNode = JsonSupport.fieldOrNull(blockNode, "input");
					String arguments = inputNode == null ? "{}" : JsonSupport.toJson(inputNode);
					contentParts.add(ContentPart.toolCall(JsonSupport.textOrNull(blockNode, "id"), JsonSupport.textOrNull(blockNode, "name"), arguments));
				}
			}
		}

		String stopReason = JsonSupport.textOrNull(messageNode, "stop_reason");
		Usage usage = mapUsage(JsonSupport.fieldOrNull(messageNode, "usage"));
		Map<String, Object> rawResponse = shouldIncludeRaw ? JsonSupport.toMap(messageNode) : null;
		return new ModelResponse(provider, JsonSupport.textOrNull(messageNode, "model"), JsonSupport.textOrNull(messageNode, "id"), contentParts, mapFinishReason(stopReason), stopReason, usage, traceId, requestId, rawResponse);
	}

	/**
	 * 映射用量：inputTokens 为含缓存创建与缓存命中的总输入量
	 */
	static Usage mapUsage(JsonNode usageNode) {
		if (usageNode == null || !usageNode.isObject()) {
			return Usage.empty();
		}

		Long inputTokens = JsonSupport.longOrNull(usageNode, "input_tokens");
		Long cacheCreation = JsonSupport.longOrNull(usageNode, "cache_creation_input_tokens");
		Long cacheRead = JsonSupport.longOrNull(usageNode, "cache_read_input_tokens");
		Long outputTokens = JsonSupport.longOrNull(usageNode, "output_tokens");
		Long totalInput = null;

		if (inputTokens != null) {
			totalInput = inputTokens + zeroIfNull(cacheCreation) + zeroIfNull(cacheRead);
		}

		Long totalTokens = totalInput != null && outputTokens != null ? totalInput + outputTokens : null;
		return new Usage(totalInput, outputTokens, cacheRead, null, totalTokens);
	}

	static FinishReason mapFinishReason(String stopReason) {
		if ("end_turn".equals(stopReason) || "stop_sequence".equals(stopReason)) {
			return FinishReason.STOP;
		}

		if ("max_tokens".equals(stopReason)) {
			return FinishReason.LENGTH;
		}

		if ("tool_use".equals(stopReason)) {
			return FinishReason.TOOL_CALLS;
		}

		if ("refusal".equals(stopReason)) {
			return FinishReason.CONTENT_FILTER;
		}

		return FinishReason.OTHER;
	}

	/**
	 * 用 SDK 自带的 ObjectMapper 把响应对象转换为 JSON 树
	 */
	static JsonNode toNode(Object sdkObject) {
		return ObjectMappers.jsonMapper().valueToTree(sdkObject);
	}

	private static long zeroIfNull(Long value) {
		return value == null ? 0L : value;
	}

	private static List<ContentBlockParam> buildBlocks(Message message, String provider) {
		List<ContentBlockParam> blocks = new ArrayList<>();

		for (ContentPart contentPart : message.getContentParts()) {
			if (contentPart.getType() == ContentPartType.TEXT) {
				blocks.add(ContentBlockParam.ofText(contentPart.getText()));
			} else if (contentPart.getType() == ContentPartType.IMAGE && message.getRole() == MessageRole.USER) {
				blocks.add(buildImageBlock(contentPart));
			} else if (contentPart.getType() == ContentPartType.TOOL_CALL && message.getRole() == MessageRole.ASSISTANT) {
				ToolUseBlockParam.Input input = ToolUseBlockParam.Input.builder().putAllAdditionalProperties(parseArguments(contentPart.getArguments(), provider)).build();
				blocks.add(ContentBlockParam.ofToolUse(ToolUseBlockParam.builder().id(contentPart.getId()).name(contentPart.getName()).input(input).build()));
			} else {
				throw CommonLlmException.of(ErrorCategory.CONFIG_INVALID, message.getRole().getValue() + " 消息不支持 " + contentPart.getType().getValue() + " 内容", provider);
			}
		}

		return blocks;
	}

	private static ContentBlockParam buildImageBlock(ContentPart contentPart) {
		if (contentPart.getUrl() != null) {
			return ContentBlockParam.ofImage(UrlImageSource.of(contentPart.getUrl()));
		}

		Base64ImageSource source = Base64ImageSource.builder().data(contentPart.getBase64()).mediaType(Base64ImageSource.MediaType.of(contentPart.getMimeType())).build();
		return ContentBlockParam.ofImage(source);
	}

	private static void appendToolResults(Message message, List<ContentBlockParam> pendingToolResults, String provider) {
		for (ContentPart contentPart : message.getContentParts()) {
			if (contentPart.getType() != ContentPartType.TOOL_RESULT) {
				throw CommonLlmException.of(ErrorCategory.CONFIG_INVALID, "tool 消息只能包含 tool_result", provider);
			}

			ToolResultBlockParam.Builder resultBuilder = ToolResultBlockParam.builder().toolUseId(contentPart.getToolCallId()).content(contentPart.getContent());

			if (contentPart.isError()) {
				resultBuilder.isError(true);
			}

			pendingToolResults.add(ContentBlockParam.ofToolResult(resultBuilder.build()));
		}
	}

	private static void flushToolResults(MessageCreateParams.Builder builder, List<ContentBlockParam> pendingToolResults) {
		if (pendingToolResults.isEmpty()) {
			return;
		}

		builder.addMessage(MessageParam.ofUserOfContentBlockParams(new ArrayList<>(pendingToolResults)));
		pendingToolResults.clear();
	}

	private static Tool buildTool(ToolDefinition tool) {
		Tool.InputSchema.Builder schemaBuilder = Tool.InputSchema.builder();

		for (Map.Entry<String, Object> schemaEntry : tool.getParameters().entrySet()) {
			String key = schemaEntry.getKey();

			if ("type".equals(key)) {
				continue;
			}

			if ("properties".equals(key) && schemaEntry.getValue() instanceof Map) {
				Tool.InputSchema.Properties.Builder propertiesBuilder = Tool.InputSchema.Properties.builder();

				for (Map.Entry<?, ?> propertyEntry : ((Map<?, ?>) schemaEntry.getValue()).entrySet()) {
					propertiesBuilder.putAdditionalProperty(String.valueOf(propertyEntry.getKey()), JsonValue.from(propertyEntry.getValue()));
				}

				schemaBuilder.properties(propertiesBuilder.build());
			} else if ("required".equals(key) && schemaEntry.getValue() instanceof List) {
				List<String> requiredFields = new ArrayList<>();

				for (Object requiredField : (List<?>) schemaEntry.getValue()) {
					requiredFields.add(String.valueOf(requiredField));
				}

				schemaBuilder.required(requiredFields);
			} else {
				schemaBuilder.putAdditionalProperty(key, JsonValue.from(schemaEntry.getValue()));
			}
		}

		Tool.Builder toolBuilder = Tool.builder().name(tool.getName()).inputSchema(schemaBuilder.build());

		if (tool.getDescription() != null) {
			toolBuilder.description(tool.getDescription());
		}

		return toolBuilder.build();
	}

	private static void applyToolChoice(MessageCreateParams.Builder builder, ToolChoice toolChoice) {
		if (toolChoice == null) {
			return;
		}

		if (toolChoice.getType() == ToolChoiceType.AUTO) {
			builder.toolChoice(ToolChoiceAuto.builder().build());
		} else if (toolChoice.getType() == ToolChoiceType.NONE) {
			builder.toolChoice(ToolChoiceNone.builder().build());
		} else if (toolChoice.getType() == ToolChoiceType.REQUIRED) {
			builder.toolChoice(ToolChoiceAny.builder().build());
		} else {
			builder.toolChoice(ToolChoiceTool.of(toolChoice.getName()));
		}
	}

	private static void applyResponseFormat(MessageCreateParams.Builder builder, ResponseFormat responseFormat) {
		if (responseFormat == null || !responseFormat.isJsonSchema()) {
			return;
		}

		Map<String, JsonValue> schemaProperties = new LinkedHashMap<>();

		for (Map.Entry<String, Object> schemaEntry : responseFormat.getSchema().entrySet()) {
			schemaProperties.put(schemaEntry.getKey(), JsonValue.from(schemaEntry.getValue()));
		}

		JsonOutputFormat.Schema schema = JsonOutputFormat.Schema.builder().putAllAdditionalProperties(schemaProperties).build();
		builder.outputConfig(OutputConfig.builder().format(JsonOutputFormat.builder().schema(schema).build()).build());
	}

	private static Map<String, JsonValue> parseArguments(String arguments, String provider) {
		Map<String, JsonValue> input = new LinkedHashMap<>();

		if (arguments == null || arguments.isBlank()) {
			return input;
		}

		Map<String, Object> parsedArguments;

		try {
			parsedArguments = JsonSupport.parseMap(arguments);
		} catch (JsonProcessingException exception) {
			throw CommonLlmException.of(ErrorCategory.CONFIG_INVALID, "tool_call.arguments 不是合法的 JSON 对象", provider);
		}

		for (Map.Entry<String, Object> argumentEntry : parsedArguments.entrySet()) {
			input.put(argumentEntry.getKey(), JsonValue.from(argumentEntry.getValue()));
		}

		return input;
	}
}
