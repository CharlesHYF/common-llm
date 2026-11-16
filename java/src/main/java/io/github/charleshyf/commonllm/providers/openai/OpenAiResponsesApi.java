package io.github.charleshyf.commonllm.providers.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.core.RequestOptions;
import com.openai.core.http.HttpResponseFor;
import com.openai.core.http.StreamResponse;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputContent;
import com.openai.models.responses.ResponseInputImage;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseInputText;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseTextConfig;
import com.openai.models.responses.ToolChoiceFunction;
import com.openai.models.responses.ToolChoiceOptions;
import io.github.charleshyf.commonllm.core.CommonLlmException;
import io.github.charleshyf.commonllm.core.ContentPart;
import io.github.charleshyf.commonllm.core.ContentPartType;
import io.github.charleshyf.commonllm.core.ErrorCategory;
import io.github.charleshyf.commonllm.core.ErrorInfo;
import io.github.charleshyf.commonllm.core.FinishReason;
import io.github.charleshyf.commonllm.core.JsonSupport;
import io.github.charleshyf.commonllm.core.Message;
import io.github.charleshyf.commonllm.core.MessageRole;
import io.github.charleshyf.commonllm.core.ModelEventEmitter;
import io.github.charleshyf.commonllm.core.ModelRequest;
import io.github.charleshyf.commonllm.core.ModelResponse;
import io.github.charleshyf.commonllm.core.ModelStreamSource;
import io.github.charleshyf.commonllm.core.ResponseFormat;
import io.github.charleshyf.commonllm.core.ToolChoice;
import io.github.charleshyf.commonllm.core.ToolChoiceType;
import io.github.charleshyf.commonllm.core.ToolDefinition;
import io.github.charleshyf.commonllm.core.Usage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenAI Responses API 的请求组装、响应解析与流式映射
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
final class OpenAiResponsesApi {

	static final Set<String> MAPPED_FIELDS = Set.of("model", "input", "instructions", "temperature", "max_output_tokens", "tools", "tool_choice", "text", "stream");

	private OpenAiResponsesApi() {
	}

	/**
	 * 把统一请求组装为 ResponseCreateParams，extraBody 经 additional body properties 透传
	 */
	static ResponseCreateParams buildParams(ModelRequest request, String model, Map<String, Object> extraBody, String provider) {
		ResponseCreateParams.Builder builder = ResponseCreateParams.builder().model(model);
		List<ResponseInputItem> inputItems = new ArrayList<>();
		List<String> systemTexts = new ArrayList<>();

		for (Message message : request.getMessages()) {
			if (message.getRole() == MessageRole.SYSTEM) {
				systemTexts.add(message.joinText());
				continue;
			}

			appendMessageItems(message, inputItems, provider);
		}

		if (!systemTexts.isEmpty()) {
			builder.instructions(String.join("\n\n", systemTexts));
		}

		builder.inputOfResponse(inputItems);

		if (request.getTemperature() != null) {
			builder.temperature(request.getTemperature());
		}

		if (request.getMaxOutputTokens() != null) {
			builder.maxOutputTokens(request.getMaxOutputTokens());
		}

		for (ToolDefinition tool : request.getTools()) {
			FunctionTool.Parameters parameters = FunctionTool.Parameters.builder().putAllAdditionalProperties(OpenAiSupport.toJsonValueMap(tool.getParameters())).build();
			FunctionTool.Builder toolBuilder = FunctionTool.builder().name(tool.getName()).parameters(parameters).strict(false);

			if (tool.getDescription() != null) {
				toolBuilder.description(tool.getDescription());
			}

			builder.addTool(toolBuilder.build());
		}

		applyToolChoice(builder, request.getToolChoice());
		applyResponseFormat(builder, request.getResponseFormat());

		for (Map.Entry<String, Object> extraEntry : extraBody.entrySet()) {
			builder.putAdditionalBodyProperty(extraEntry.getKey(), JsonValue.from(extraEntry.getValue()));
		}

		return builder.build();
	}

	/**
	 * 同步调用并映射响应，requestId 取响应头 x-request-id
	 */
	static ModelResponse generate(OpenAIClient client, ResponseCreateParams params, RequestOptions requestOptions, String provider, String traceId, boolean shouldIncludeRaw) {
		try (HttpResponseFor<Response> rawResponse = client.responses().withRawResponse().create(params, requestOptions)) {
			String requestId = OpenAiSupport.readRequestId(rawResponse.headers());
			JsonNode responseNode = OpenAiSupport.toNode(rawResponse.parse());
			return mapResponse(provider, responseNode, requestId, traceId, shouldIncludeRaw);
		} catch (RuntimeException exception) {
			throw OpenAiSupport.mapException(exception, provider);
		}
	}

	/**
	 * 发起流式调用并返回事件来源，HTTP 错误在首次读取时以 error 事件结束
	 */
	static ModelStreamSource stream(OpenAIClient client, ResponseCreateParams params, RequestOptions requestOptions, String provider, String traceId, boolean shouldIncludeRaw) {
		return new ResponsesStreamSource(client, params, requestOptions, provider, traceId, shouldIncludeRaw);
	}

	/**
	 * 按契约规则映射 Responses API 的完整响应
	 */
	static ModelResponse mapResponse(String provider, JsonNode responseNode, String requestId, String traceId, boolean shouldIncludeRaw) {
		List<ContentPart> contentParts = new ArrayList<>();
		boolean hasToolCall = false;
		JsonNode outputNode = responseNode.get("output");

		if (outputNode != null && outputNode.isArray()) {
			for (JsonNode outputItem : outputNode) {
				String itemType = JsonSupport.textOrNull(outputItem, "type");

				if ("message".equals(itemType)) {
					appendOutputText(outputItem, contentParts);
				} else if ("function_call".equals(itemType)) {
					hasToolCall = true;
					String arguments = JsonSupport.textOrNull(outputItem, "arguments");
					contentParts.add(ContentPart.toolCall(JsonSupport.textOrNull(outputItem, "call_id"), JsonSupport.textOrNull(outputItem, "name"), arguments == null ? "" : arguments));
				}
			}
		}

		String status = JsonSupport.textOrNull(responseNode, "status");
		String vendorFinishReason = status;
		FinishReason finishReason = FinishReason.OTHER;

		if (hasToolCall) {
			finishReason = FinishReason.TOOL_CALLS;
		} else if ("completed".equals(status)) {
			finishReason = FinishReason.STOP;
		} else if ("incomplete".equals(status)) {
			String incompleteReason = JsonSupport.textOrNull(JsonSupport.fieldOrNull(responseNode, "incomplete_details"), "reason");
			vendorFinishReason = incompleteReason;
			finishReason = mapIncompleteReason(incompleteReason);
		}

		Usage usage = OpenAiSupport.mapResponsesUsage(responseNode.get("usage"));
		Map<String, Object> rawResponse = shouldIncludeRaw ? JsonSupport.toMap(responseNode) : null;
		return new ModelResponse(provider, JsonSupport.textOrNull(responseNode, "model"), JsonSupport.textOrNull(responseNode, "id"), contentParts, finishReason, vendorFinishReason, usage, traceId, requestId, rawResponse);
	}

	private static FinishReason mapIncompleteReason(String incompleteReason) {
		if ("max_output_tokens".equals(incompleteReason)) {
			return FinishReason.LENGTH;
		}

		if ("content_filter".equals(incompleteReason)) {
			return FinishReason.CONTENT_FILTER;
		}

		return FinishReason.OTHER;
	}

	private static void appendOutputText(JsonNode messageItem, List<ContentPart> contentParts) {
		JsonNode contentNode = messageItem.get("content");

		if (contentNode == null || !contentNode.isArray()) {
			return;
		}

		for (JsonNode content : contentNode) {
			if (!"output_text".equals(JsonSupport.textOrNull(content, "type"))) {
				continue;
			}

			String text = JsonSupport.textOrNull(content, "text");
			contentParts.add(ContentPart.text(text == null ? "" : text));
		}
	}

	private static void appendMessageItems(Message message, List<ResponseInputItem> inputItems, String provider) {
		if (message.getRole() == MessageRole.TOOL) {
			for (ContentPart contentPart : message.getContentParts()) {
				if (contentPart.getType() != ContentPartType.TOOL_RESULT) {
					throw CommonLlmException.of(ErrorCategory.CONFIG_INVALID, "tool 消息只能包含 tool_result", provider);
				}

				ResponseInputItem.FunctionCallOutput output = ResponseInputItem.FunctionCallOutput.builder().callId(contentPart.getToolCallId()).output(contentPart.getContent()).build();
				inputItems.add(ResponseInputItem.ofFunctionCallOutput(output));
			}

			return;
		}

		List<ResponseInputContent> contents = new ArrayList<>();
		List<ResponseInputItem> toolCallItems = new ArrayList<>();

		for (ContentPart contentPart : message.getContentParts()) {
			if (contentPart.getType() == ContentPartType.TEXT) {
				contents.add(ResponseInputContent.ofInputText(ResponseInputText.builder().text(contentPart.getText()).build()));
			} else if (contentPart.getType() == ContentPartType.IMAGE) {
				ResponseInputImage image = ResponseInputImage.builder().detail(ResponseInputImage.Detail.AUTO).imageUrl(buildImageUrl(contentPart)).build();
				contents.add(ResponseInputContent.ofInputImage(image));
			} else if (contentPart.getType() == ContentPartType.TOOL_CALL && message.getRole() == MessageRole.ASSISTANT) {
				ResponseFunctionToolCall toolCall = ResponseFunctionToolCall.builder().callId(contentPart.getId()).name(contentPart.getName()).arguments(contentPart.getArguments()).build();
				toolCallItems.add(ResponseInputItem.ofFunctionCall(toolCall));
			} else {
				throw CommonLlmException.of(ErrorCategory.CONFIG_INVALID, message.getRole().getValue() + " 消息不支持 " + contentPart.getType().getValue() + " 内容", provider);
			}
		}

		if (!contents.isEmpty()) {
			EasyInputMessage.Role role = message.getRole() == MessageRole.ASSISTANT ? EasyInputMessage.Role.ASSISTANT : EasyInputMessage.Role.USER;
			EasyInputMessage.Content content = message.getRole() == MessageRole.ASSISTANT ? EasyInputMessage.Content.ofTextInput(message.joinText()) : EasyInputMessage.Content.ofResponseInputMessageContentList(contents);
			inputItems.add(ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder().role(role).content(content).build()));
		}

		inputItems.addAll(toolCallItems);
	}

	private static String buildImageUrl(ContentPart contentPart) {
		if (contentPart.getUrl() != null) {
			return contentPart.getUrl();
		}

		return "data:" + contentPart.getMimeType() + ";base64," + contentPart.getBase64();
	}

	private static void applyToolChoice(ResponseCreateParams.Builder builder, ToolChoice toolChoice) {
		if (toolChoice == null) {
			return;
		}

		if (toolChoice.getType() == ToolChoiceType.AUTO) {
			builder.toolChoice(ToolChoiceOptions.AUTO);
		} else if (toolChoice.getType() == ToolChoiceType.NONE) {
			builder.toolChoice(ToolChoiceOptions.NONE);
		} else if (toolChoice.getType() == ToolChoiceType.REQUIRED) {
			builder.toolChoice(ToolChoiceOptions.REQUIRED);
		} else {
			builder.toolChoice(ToolChoiceFunction.builder().name(toolChoice.getName()).build());
		}
	}

	private static void applyResponseFormat(ResponseCreateParams.Builder builder, ResponseFormat responseFormat) {
		if (responseFormat == null || !responseFormat.isJsonSchema()) {
			return;
		}

		ResponseFormatTextJsonSchemaConfig.Schema schema = ResponseFormatTextJsonSchemaConfig.Schema.builder().putAllAdditionalProperties(OpenAiSupport.toJsonValueMap(responseFormat.getSchema())).build();
		ResponseFormatTextJsonSchemaConfig.Builder formatBuilder = ResponseFormatTextJsonSchemaConfig.builder().name(responseFormat.getName()).schema(schema);

		if (responseFormat.getStrict() != null) {
			formatBuilder.strict(responseFormat.getStrict());
		}

		builder.text(ResponseTextConfig.builder().format(formatBuilder.build()).build());
	}

	/**
	 * Responses API 流式事件来源
	 */
	private static final class ResponsesStreamSource implements ModelStreamSource {

		private final OpenAIClient client;
		private final ResponseCreateParams params;
		private final RequestOptions requestOptions;
		private final String provider;
		private final String traceId;
		private final boolean shouldIncludeRaw;
		private final Map<String, Integer> toolIndexes = new HashMap<>();
		private HttpResponseFor<StreamResponse<ResponseStreamEvent>> rawResponse;
		private StreamResponse<ResponseStreamEvent> streamResponse;
		private Iterator<ResponseStreamEvent> vendorEvents;
		private String requestId;

		private ResponsesStreamSource(OpenAIClient client, ResponseCreateParams params, RequestOptions requestOptions, String provider, String traceId, boolean shouldIncludeRaw) {
			this.client = client;
			this.params = params;
			this.requestOptions = requestOptions;
			this.provider = provider;
			this.traceId = traceId;
			this.shouldIncludeRaw = shouldIncludeRaw;
		}

		@Override
		public boolean readNext(ModelEventEmitter emitter) {
			try {
				if (vendorEvents == null) {
					rawResponse = client.responses().withRawResponse().createStreaming(params, requestOptions);
					requestId = OpenAiSupport.readRequestId(rawResponse.headers());
					streamResponse = rawResponse.parse();
					// SDK 流式响应只提供 Stream 接口，这里仅取迭代器逐个读取事件
					vendorEvents = streamResponse.stream().iterator(); // check-ignore
				}

				if (!vendorEvents.hasNext()) {
					return false;
				}

				handleEvent(OpenAiSupport.toNode(vendorEvents.next()), emitter);
				return true;
			} catch (RuntimeException exception) {
				throw OpenAiSupport.mapException(exception, provider);
			}
		}

		private void handleEvent(JsonNode eventNode, ModelEventEmitter emitter) {
			String eventType = JsonSupport.textOrNull(eventNode, "type");

			if ("response.created".equals(eventType)) {
				JsonNode responseNode = eventNode.get("response");
				emitter.emitStarted(JsonSupport.textOrNull(responseNode, "model"), JsonSupport.textOrNull(responseNode, "id"));
			} else if ("response.output_text.delta".equals(eventType)) {
				emitter.emitTextDelta(JsonSupport.textOrNull(eventNode, "delta"));
			} else if ("response.output_item.added".equals(eventType)) {
				JsonNode itemNode = eventNode.get("item");

				if ("function_call".equals(JsonSupport.textOrNull(itemNode, "type"))) {
					int toolIndex = toolIndexes.size();
					toolIndexes.put(JsonSupport.textOrNull(itemNode, "id"), toolIndex);
					String arguments = JsonSupport.textOrNull(itemNode, "arguments");
					emitter.emitToolCallDelta(toolIndex, JsonSupport.textOrNull(itemNode, "call_id"), JsonSupport.textOrNull(itemNode, "name"), arguments == null ? "" : arguments);
				}
			} else if ("response.function_call_arguments.delta".equals(eventType)) {
				Integer toolIndex = toolIndexes.get(JsonSupport.textOrNull(eventNode, "item_id"));

				if (toolIndex != null) {
					emitter.emitToolCallDelta(toolIndex, null, null, JsonSupport.textOrNull(eventNode, "delta"));
				}
			} else if ("response.completed".equals(eventType) || "response.incomplete".equals(eventType)) {
				JsonNode responseNode = eventNode.get("response");
				ModelResponse response = mapResponse(provider, responseNode, requestId, traceId, shouldIncludeRaw);
				JsonNode usageNode = JsonSupport.fieldOrNull(responseNode, "usage");

				if (usageNode != null) {
					emitter.emitUsage(response.getUsage());
				}

				emitter.emitCompleted(response);
			} else if ("response.failed".equals(eventType) || "error".equals(eventType)) {
				emitter.emitError(buildStreamError(eventNode));
			}
		}

		private ErrorInfo buildStreamError(JsonNode eventNode) {
			JsonNode errorNode = JsonSupport.fieldOrNull(JsonSupport.fieldOrNull(eventNode, "response"), "error");

			if (errorNode == null) {
				errorNode = eventNode;
			}

			String message = JsonSupport.textOrNull(errorNode, "message");
			return ErrorInfo.builder(ErrorCategory.PROVIDER_ERROR, message == null ? "OpenAI 流式响应失败" : message)
				.provider(provider)
				.vendorCode(JsonSupport.textOrNull(errorNode, "code"))
				.requestId(requestId)
				.build();
		}

		@Override
		public void close() {
			if (streamResponse != null) {
				streamResponse.close();
			}

			if (rawResponse != null) {
				rawResponse.close();
			}
		}
	}
}
