package io.github.charleshyf.commonllm.providers.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.core.RequestOptions;
import com.openai.core.http.HttpResponseFor;
import com.openai.core.http.StreamResponse;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.ResponseFormatJsonSchema;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionContentPartImage;
import com.openai.models.chat.completions.ChatCompletionContentPartText;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionNamedToolChoice;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionToolChoiceOption;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import io.github.charleshyf.commonllm.core.CommonLlmException;
import io.github.charleshyf.commonllm.core.ContentPart;
import io.github.charleshyf.commonllm.core.ContentPartType;
import io.github.charleshyf.commonllm.core.ErrorCategory;
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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * OpenAI Chat Completions 的请求组装、响应解析与流式映射
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
final class OpenAiChatApi {

	static final Set<String> MAPPED_FIELDS = Set.of("model", "messages", "temperature", "max_completion_tokens", "tools", "tool_choice", "response_format", "stream", "stream_options");

	private OpenAiChatApi() {
	}

	/**
	 * 把统一请求组装为 ChatCompletionCreateParams，system 消息保留为 system 角色
	 */
	static ChatCompletionCreateParams buildParams(ModelRequest request, String model, Map<String, Object> extraBody, boolean isStreaming, String provider) {
		ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder().model(model);

		for (Message message : request.getMessages()) {
			appendMessage(builder, message, provider);
		}

		if (request.getTemperature() != null) {
			builder.temperature(request.getTemperature());
		}

		if (request.getMaxOutputTokens() != null) {
			builder.maxCompletionTokens(request.getMaxOutputTokens());
		}

		for (ToolDefinition tool : request.getTools()) {
			FunctionParameters parameters = FunctionParameters.builder().putAllAdditionalProperties(OpenAiSupport.toJsonValueMap(tool.getParameters())).build();
			FunctionDefinition.Builder functionBuilder = FunctionDefinition.builder().name(tool.getName()).parameters(parameters);

			if (tool.getDescription() != null) {
				functionBuilder.description(tool.getDescription());
			}

			builder.addTool(ChatCompletionFunctionTool.builder().function(functionBuilder.build()).build());
		}

		applyToolChoice(builder, request.getToolChoice());
		applyResponseFormat(builder, request.getResponseFormat());

		// 流式请求附带 include_usage，末尾 chunk 才会带回用量
		if (isStreaming) {
			builder.streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build());
		}

		for (Map.Entry<String, Object> extraEntry : extraBody.entrySet()) {
			builder.putAdditionalBodyProperty(extraEntry.getKey(), JsonValue.from(extraEntry.getValue()));
		}

		return builder.build();
	}

	/**
	 * 同步调用并映射响应，requestId 取响应头 x-request-id
	 */
	static ModelResponse generate(OpenAIClient client, ChatCompletionCreateParams params, RequestOptions requestOptions, String provider, String traceId, boolean shouldIncludeRaw) {
		try (HttpResponseFor<ChatCompletion> rawResponse = client.chat().completions().withRawResponse().create(params, requestOptions)) {
			String requestId = OpenAiSupport.readRequestId(rawResponse.headers());
			JsonNode completionNode = OpenAiSupport.toNode(rawResponse.parse());
			return mapCompletion(provider, completionNode, requestId, traceId, shouldIncludeRaw);
		} catch (RuntimeException exception) {
			throw OpenAiSupport.mapException(exception, provider);
		}
	}

	static ModelStreamSource stream(OpenAIClient client, ChatCompletionCreateParams params, RequestOptions requestOptions, String provider, String traceId) {
		return new ChatStreamSource(client, params, requestOptions, provider, traceId);
	}

	/**
	 * 按契约规则映射 Chat Completions 完整响应，只取第一个 choice
	 */
	static ModelResponse mapCompletion(String provider, JsonNode completionNode, String requestId, String traceId, boolean shouldIncludeRaw) {
		List<ContentPart> contentParts = new ArrayList<>();
		JsonNode choicesNode = completionNode.get("choices");
		JsonNode choiceNode = choicesNode != null && choicesNode.isArray() && choicesNode.size() > 0 ? choicesNode.get(0) : null;
		JsonNode messageNode = JsonSupport.fieldOrNull(choiceNode, "message");
		String content = JsonSupport.textOrNull(messageNode, "content");

		if (content != null && !content.isEmpty()) {
			contentParts.add(ContentPart.text(content));
		}

		JsonNode toolCallsNode = JsonSupport.fieldOrNull(messageNode, "tool_calls");

		if (toolCallsNode != null && toolCallsNode.isArray()) {
			for (JsonNode toolCallNode : toolCallsNode) {
				JsonNode functionNode = toolCallNode.get("function");
				String arguments = JsonSupport.textOrNull(functionNode, "arguments");
				contentParts.add(ContentPart.toolCall(JsonSupport.textOrNull(toolCallNode, "id"), JsonSupport.textOrNull(functionNode, "name"), arguments == null ? "" : arguments));
			}
		}

		String vendorFinishReason = JsonSupport.textOrNull(choiceNode, "finish_reason");
		Usage usage = OpenAiSupport.mapChatUsage(completionNode.get("usage"));
		Map<String, Object> rawResponse = shouldIncludeRaw ? JsonSupport.toMap(completionNode) : null;
		return new ModelResponse(provider, JsonSupport.textOrNull(completionNode, "model"), JsonSupport.textOrNull(completionNode, "id"), contentParts, mapFinishReason(vendorFinishReason), vendorFinishReason, usage, traceId, requestId, rawResponse);
	}

	static FinishReason mapFinishReason(String vendorFinishReason) {
		if ("stop".equals(vendorFinishReason)) {
			return FinishReason.STOP;
		}

		if ("length".equals(vendorFinishReason)) {
			return FinishReason.LENGTH;
		}

		if ("tool_calls".equals(vendorFinishReason) || "function_call".equals(vendorFinishReason)) {
			return FinishReason.TOOL_CALLS;
		}

		if ("content_filter".equals(vendorFinishReason)) {
			return FinishReason.CONTENT_FILTER;
		}

		return FinishReason.OTHER;
	}

	private static void appendMessage(ChatCompletionCreateParams.Builder builder, Message message, String provider) {
		if (message.getRole() == MessageRole.SYSTEM) {
			builder.addMessage(ChatCompletionSystemMessageParam.builder().content(message.joinText()).build());
			return;
		}

		if (message.getRole() == MessageRole.TOOL) {
			for (ContentPart contentPart : message.getContentParts()) {
				if (contentPart.getType() != ContentPartType.TOOL_RESULT) {
					throw CommonLlmException.of(ErrorCategory.CONFIG_INVALID, "tool 消息只能包含 tool_result", provider);
				}

				builder.addMessage(ChatCompletionToolMessageParam.builder().toolCallId(contentPart.getToolCallId()).content(contentPart.getContent()).build());
			}

			return;
		}

		if (message.getRole() == MessageRole.ASSISTANT) {
			appendAssistantMessage(builder, message, provider);
			return;
		}

		List<ChatCompletionContentPart> userParts = new ArrayList<>();

		for (ContentPart contentPart : message.getContentParts()) {
			if (contentPart.getType() == ContentPartType.TEXT) {
				userParts.add(ChatCompletionContentPart.ofText(ChatCompletionContentPartText.builder().text(contentPart.getText()).build()));
			} else if (contentPart.getType() == ContentPartType.IMAGE) {
				ChatCompletionContentPartImage.ImageUrl imageUrl = ChatCompletionContentPartImage.ImageUrl.builder().url(buildImageUrl(contentPart)).build();
				userParts.add(ChatCompletionContentPart.ofImageUrl(ChatCompletionContentPartImage.builder().imageUrl(imageUrl).build()));
			} else {
				throw CommonLlmException.of(ErrorCategory.CONFIG_INVALID, "user 消息不支持 " + contentPart.getType().getValue() + " 内容", provider);
			}
		}

		builder.addMessage(ChatCompletionUserMessageParam.builder().contentOfArrayOfContentParts(userParts).build());
	}

	private static void appendAssistantMessage(ChatCompletionCreateParams.Builder builder, Message message, String provider) {
		ChatCompletionAssistantMessageParam.Builder assistantBuilder = ChatCompletionAssistantMessageParam.builder();
		String text = message.joinText();

		if (!text.isEmpty()) {
			assistantBuilder.content(text);
		}

		for (ContentPart contentPart : message.getContentParts()) {
			if (contentPart.getType() == ContentPartType.TOOL_CALL) {
				ChatCompletionMessageFunctionToolCall.Function function = ChatCompletionMessageFunctionToolCall.Function.builder().name(contentPart.getName()).arguments(contentPart.getArguments()).build();
				assistantBuilder.addToolCall(ChatCompletionMessageFunctionToolCall.builder().id(contentPart.getId()).function(function).build());
			} else if (contentPart.getType() != ContentPartType.TEXT) {
				throw CommonLlmException.of(ErrorCategory.CONFIG_INVALID, "assistant 消息不支持 " + contentPart.getType().getValue() + " 内容", provider);
			}
		}

		builder.addMessage(assistantBuilder.build());
	}

	private static String buildImageUrl(ContentPart contentPart) {
		if (contentPart.getUrl() != null) {
			return contentPart.getUrl();
		}

		return "data:" + contentPart.getMimeType() + ";base64," + contentPart.getBase64();
	}

	private static void applyToolChoice(ChatCompletionCreateParams.Builder builder, ToolChoice toolChoice) {
		if (toolChoice == null) {
			return;
		}

		if (toolChoice.getType() == ToolChoiceType.AUTO) {
			builder.toolChoice(ChatCompletionToolChoiceOption.Auto.AUTO);
		} else if (toolChoice.getType() == ToolChoiceType.NONE) {
			builder.toolChoice(ChatCompletionToolChoiceOption.Auto.NONE);
		} else if (toolChoice.getType() == ToolChoiceType.REQUIRED) {
			builder.toolChoice(ChatCompletionToolChoiceOption.Auto.REQUIRED);
		} else {
			ChatCompletionNamedToolChoice.Function function = ChatCompletionNamedToolChoice.Function.builder().name(toolChoice.getName()).build();
			builder.toolChoice(ChatCompletionNamedToolChoice.builder().function(function).build());
		}
	}

	private static void applyResponseFormat(ChatCompletionCreateParams.Builder builder, ResponseFormat responseFormat) {
		if (responseFormat == null || !responseFormat.isJsonSchema()) {
			return;
		}

		ResponseFormatJsonSchema.JsonSchema.Schema schema = ResponseFormatJsonSchema.JsonSchema.Schema.builder().putAllAdditionalProperties(OpenAiSupport.toJsonValueMap(responseFormat.getSchema())).build();
		ResponseFormatJsonSchema.JsonSchema.Builder schemaBuilder = ResponseFormatJsonSchema.JsonSchema.builder().name(responseFormat.getName()).schema(schema);

		if (responseFormat.getStrict() != null) {
			schemaBuilder.strict(responseFormat.getStrict());
		}

		builder.responseFormat(ResponseFormatJsonSchema.builder().jsonSchema(schemaBuilder.build()).build());
	}

	/**
	 * Chat Completions 流式事件来源，流结束后发出用量与完成事件
	 */
	private static final class ChatStreamSource implements ModelStreamSource {

		private final OpenAIClient client;
		private final ChatCompletionCreateParams params;
		private final RequestOptions requestOptions;
		private final String provider;
		private final String traceId;
		private final StringBuilder textBuilder = new StringBuilder();
		private final Map<Integer, PendingToolCall> toolCalls = new TreeMap<>();
		private HttpResponseFor<StreamResponse<ChatCompletionChunk>> rawResponse;
		private StreamResponse<ChatCompletionChunk> streamResponse;
		private Iterator<ChatCompletionChunk> vendorChunks;
		private String requestId;
		private String model;
		private String responseId;
		private boolean hasStarted;
		private String vendorFinishReason;
		private Usage usage;

		private ChatStreamSource(OpenAIClient client, ChatCompletionCreateParams params, RequestOptions requestOptions, String provider, String traceId) {
			this.client = client;
			this.params = params;
			this.requestOptions = requestOptions;
			this.provider = provider;
			this.traceId = traceId;
		}

		@Override
		public boolean readNext(ModelEventEmitter emitter) {
			try {
				if (vendorChunks == null) {
					rawResponse = client.chat().completions().withRawResponse().createStreaming(params, requestOptions);
					requestId = OpenAiSupport.readRequestId(rawResponse.headers());
					streamResponse = rawResponse.parse();
					// SDK 流式响应只提供 Stream 接口，这里仅取迭代器逐个读取 chunk
					vendorChunks = streamResponse.stream().iterator(); // check-ignore
				}

				if (!vendorChunks.hasNext()) {
					emitFinalEvents(emitter);
					return false;
				}

				handleChunk(OpenAiSupport.toNode(vendorChunks.next()), emitter);
				return true;
			} catch (RuntimeException exception) {
				throw OpenAiSupport.mapException(exception, provider);
			}
		}

		private void handleChunk(JsonNode chunkNode, ModelEventEmitter emitter) {
			if (!hasStarted) {
				hasStarted = true;
				model = JsonSupport.textOrNull(chunkNode, "model");
				responseId = JsonSupport.textOrNull(chunkNode, "id");
				emitter.emitStarted(model, responseId);
			}

			JsonNode usageNode = JsonSupport.fieldOrNull(chunkNode, "usage");

			if (usageNode != null && usageNode.isObject()) {
				usage = OpenAiSupport.mapChatUsage(usageNode);
			}

			JsonNode choicesNode = chunkNode.get("choices");

			if (choicesNode == null || !choicesNode.isArray()) {
				return;
			}

			for (JsonNode choiceNode : choicesNode) {
				handleChoice(choiceNode, emitter);
			}
		}

		private void handleChoice(JsonNode choiceNode, ModelEventEmitter emitter) {
			JsonNode deltaNode = JsonSupport.fieldOrNull(choiceNode, "delta");
			String content = JsonSupport.textOrNull(deltaNode, "content");

			if (content != null && !content.isEmpty()) {
				textBuilder.append(content);
				emitter.emitTextDelta(content);
			}

			JsonNode toolDeltas = JsonSupport.fieldOrNull(deltaNode, "tool_calls");

			if (toolDeltas != null && toolDeltas.isArray()) {
				for (JsonNode toolDelta : toolDeltas) {
					int toolIndex = toolDelta.path("index").asInt();
					JsonNode functionNode = JsonSupport.fieldOrNull(toolDelta, "function");
					String toolCallId = JsonSupport.textOrNull(toolDelta, "id");
					String toolName = JsonSupport.textOrNull(functionNode, "name");
					String argumentsDelta = JsonSupport.textOrNull(functionNode, "arguments");
					String safeDelta = argumentsDelta == null ? "" : argumentsDelta;
					PendingToolCall toolCall = toolCalls.computeIfAbsent(toolIndex, index -> new PendingToolCall(toolCallId, toolName));
					toolCall.arguments.append(safeDelta);
					emitter.emitToolCallDelta(toolIndex, toolCallId, toolName, safeDelta);
				}
			}

			String finishReason = JsonSupport.textOrNull(choiceNode, "finish_reason");

			if (finishReason != null) {
				vendorFinishReason = finishReason;
			}
		}

		private void emitFinalEvents(ModelEventEmitter emitter) {
			List<ContentPart> contentParts = new ArrayList<>();

			if (textBuilder.length() > 0) {
				contentParts.add(ContentPart.text(textBuilder.toString()));
			}

			for (PendingToolCall toolCall : toolCalls.values()) {
				contentParts.add(ContentPart.toolCall(toolCall.toolCallId, toolCall.toolName, toolCall.arguments.toString()));
			}

			if (usage != null) {
				emitter.emitUsage(usage);
			}

			Usage finalUsage = usage == null ? Usage.empty() : usage;
			emitter.emitCompleted(new ModelResponse(provider, model, responseId, contentParts, mapFinishReason(vendorFinishReason), vendorFinishReason, finalUsage, traceId, requestId, null));
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

	/**
	 * 流式过程中按序号累积的工具调用
	 */
	private static final class PendingToolCall {

		private final String toolCallId;
		private final String toolName;
		private final StringBuilder arguments = new StringBuilder();

		private PendingToolCall(String toolCallId, String toolName) {
			this.toolCallId = toolCallId;
			this.toolName = toolName;
		}
	}
}
