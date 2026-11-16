package io.github.charleshyf.commonllm.providers.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.RequestOptions;
import com.anthropic.core.Timeout;
import com.anthropic.core.http.Headers;
import com.anthropic.core.http.HttpResponseFor;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicInvalidDataException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.charleshyf.commonllm.config.ProviderConfig;
import io.github.charleshyf.commonllm.core.CommonLlmException;
import io.github.charleshyf.commonllm.core.ContentPart;
import io.github.charleshyf.commonllm.core.ErrorCategory;
import io.github.charleshyf.commonllm.core.ErrorInfo;
import io.github.charleshyf.commonllm.core.HttpErrorClassifier;
import io.github.charleshyf.commonllm.core.JsonSupport;
import io.github.charleshyf.commonllm.core.ModelClient;
import io.github.charleshyf.commonllm.core.ModelEventEmitter;
import io.github.charleshyf.commonllm.core.ModelFeature;
import io.github.charleshyf.commonllm.core.ModelRequest;
import io.github.charleshyf.commonllm.core.ModelRequestValidator;
import io.github.charleshyf.commonllm.core.ModelResponse;
import io.github.charleshyf.commonllm.core.ModelStream;
import io.github.charleshyf.commonllm.core.ModelStreamSource;
import io.github.charleshyf.commonllm.core.ProviderCapability;
import io.github.charleshyf.commonllm.core.ProviderOptionsMerger;
import io.github.charleshyf.commonllm.core.Usage;
import io.github.charleshyf.commonllm.observability.CallLogger;
import io.github.charleshyf.commonllm.observability.IdGenerator;

import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 基于官方 Anthropic Java SDK 的 Messages API 模型客户端
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class AnthropicModelClient implements ModelClient {

	private static final String REQUEST_ID_HEADER = "request-id";

	private final ProviderConfig config;
	private final IdGenerator idGenerator;
	private final AnthropicClient client;

	public AnthropicModelClient(ProviderConfig config, IdGenerator idGenerator) {
		this.config = config;
		this.idGenerator = idGenerator;
		AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
			.apiKey(config.getApiKey())
			.maxRetries(config.getMaxRetries())
			.timeout(Timeout.builder().connect(toDuration(config.getConnectTimeoutSeconds())).request(toDuration(config.getTimeoutSeconds())).build());

		if (config.getBaseUrl() != null) {
			builder.baseUrl(config.getBaseUrl());
		}

		this.client = builder.build();
	}

	@Override
	public ModelResponse generate(ModelRequest request) {
		String provider = config.getInstanceName();
		String model = ModelRequestValidator.validateAndResolveModel(request, config.getDefaultModel(), provider);
		String traceId = ModelRequestValidator.resolveTraceId(request, idGenerator.newTraceId());
		MessageCreateParams params = buildParams(request, model);
		long startNanos = System.nanoTime();

		try (HttpResponseFor<Message> rawResponse = client.messages().withRawResponse().create(params, buildRequestOptions(request))) {
			String requestId = readRequestId(rawResponse.headers());
			ModelResponse response = AnthropicMessagesApi.mapMessage(provider, AnthropicMessagesApi.toNode(rawResponse.parse()), requestId, traceId, request.shouldIncludeRawResponse());
			CallLogger.logModelCall(provider, model, elapsedMillis(startNanos), traceId, response.getUsage(), null);
			return response;
		} catch (RuntimeException exception) {
			CommonLlmException mappedException = mapException(exception, provider);
			CallLogger.logModelCall(provider, model, elapsedMillis(startNanos), traceId, null, mappedException.getErrorInfo());
			throw mappedException;
		}
	}

	@Override
	public ModelStream stream(ModelRequest request) {
		String provider = config.getInstanceName();
		String traceId = ModelRequestValidator.resolveTraceId(request, idGenerator.newTraceId());

		try {
			String model = ModelRequestValidator.validateAndResolveModel(request, config.getDefaultModel(), provider);
			MessageCreateParams params = buildParams(request, model);
			MessagesStreamSource source = new MessagesStreamSource(params, buildRequestOptions(request), provider, traceId);
			return new ModelStream(new ModelEventEmitter(traceId, provider), source);
		} catch (CommonLlmException exception) {
			return ModelStream.failed(traceId, provider, exception.getErrorInfo());
		}
	}

	@Override
	public boolean supports(String feature) {
		return capability().getFeatures().contains(feature);
	}

	@Override
	public ProviderCapability capability() {
		List<String> features = new ArrayList<>();
		features.add(ModelFeature.STREAMING.getValue());
		features.add(ModelFeature.TOOL_CALLS.getValue());
		features.add(ModelFeature.STRUCTURED_OUTPUT.getValue());
		features.add(ModelFeature.VISION.getValue());
		features.add(ModelFeature.REASONING.getValue());
		return new ProviderCapability(config.getInstanceName(), features);
	}

	/**
	 * 返回官方 com.anthropic.client.AnthropicClient
	 */
	@Override
	public Object nativeClient() {
		return client;
	}

	@Override
	public void close() {
		client.close();
	}

	/**
	 * max_tokens 必填：请求 maxOutputTokens 优先，其次取配置 native_options.max_tokens
	 */
	private MessageCreateParams buildParams(ModelRequest request, String model) {
		String provider = config.getInstanceName();

		// Anthropic 已弃用 temperature，anthropic-java 3.8.0 的 MessageCreateParams 也没有该字段
		if (request.getTemperature() != null) {
			throw CommonLlmException.of(ErrorCategory.SDK_UNSUPPORTED_FEATURE, "Anthropic 不支持 temperature，如需传入请使用 providerOptions", provider);
		}

		Map<String, Object> nativeOptions = new LinkedHashMap<>(config.getNativeOptions());
		Object configuredMaxTokens = nativeOptions.remove(AnthropicMessagesApi.MAX_TOKENS_OPTION);
		Long maxTokens = request.getMaxOutputTokens();

		if (maxTokens == null && configuredMaxTokens instanceof Number) {
			maxTokens = ((Number) configuredMaxTokens).longValue();
		}

		if (maxTokens == null) {
			throw CommonLlmException.of(ErrorCategory.CONFIG_INVALID, "Anthropic 需要 max_tokens：请设置 maxOutputTokens 或配置 native_options.max_tokens", provider);
		}

		Map<String, Object> extraBody = ProviderOptionsMerger.merge(nativeOptions, request.getProviderOptions(), AnthropicMessagesApi.MAPPED_FIELDS, provider);
		return AnthropicMessagesApi.buildParams(request, model, maxTokens, extraBody, provider);
	}

	private RequestOptions buildRequestOptions(ModelRequest request) {
		if (request.getTimeoutSeconds() == null) {
			return RequestOptions.none();
		}

		return RequestOptions.builder().timeout(Timeout.builder().connect(toDuration(config.getConnectTimeoutSeconds())).request(toDuration(request.getTimeoutSeconds())).build()).build();
	}

	private static String readRequestId(Headers headers) {
		List<String> values = headers.values(REQUEST_ID_HEADER);
		return values.isEmpty() ? null : values.get(0);
	}

	/**
	 * 把 SDK 异常映射为统一错误分类，vendorCode 取 error.type
	 */
	static CommonLlmException mapException(RuntimeException exception, String provider) {
		if (exception instanceof CommonLlmException) {
			return (CommonLlmException) exception;
		}

		if (exception instanceof AnthropicServiceException) {
			AnthropicServiceException serviceException = (AnthropicServiceException) exception;
			JsonNode errorNode = JsonSupport.fieldOrNull(AnthropicMessagesApi.toNode(serviceException.body()), "error");
			String vendorCode = JsonSupport.textOrNull(errorNode, "type");
			String vendorMessage = JsonSupport.textOrNull(errorNode, "message");
			ErrorInfo error = HttpErrorClassifier.buildError(provider, serviceException.statusCode(), vendorCode, vendorMessage, readRequestId(serviceException.headers()), false, false, null);
			return new CommonLlmException(error, exception);
		}

		if (exception instanceof AnthropicIoException) {
			boolean isTimeout = hasTimeoutCause(exception);
			ErrorCategory category = isTimeout ? ErrorCategory.TIMEOUT : ErrorCategory.NETWORK_ERROR;
			String message = isTimeout ? "请求 Anthropic 超时" : "连接 Anthropic 失败";
			return new CommonLlmException(ErrorInfo.builder(category, message).provider(provider).retriable(true).cause(exception.getCause()).build(), exception);
		}

		if (exception instanceof AnthropicInvalidDataException) {
			return new CommonLlmException(ErrorInfo.builder(ErrorCategory.PROVIDER_ERROR, "Anthropic 响应数据不符合预期").provider(provider).cause(exception).build(), exception);
		}

		return new CommonLlmException(ErrorInfo.builder(ErrorCategory.INTERNAL_ERROR, "调用 Anthropic SDK 失败").provider(provider).cause(exception).build(), exception);
	}

	private static boolean hasTimeoutCause(Throwable throwable) {
		Throwable current = throwable;

		while (current != null) {
			if (current instanceof InterruptedIOException) {
				return true;
			}

			current = current.getCause();
		}

		return false;
	}

	private static Duration toDuration(double seconds) {
		return Duration.ofMillis(Math.round(seconds * 1000));
	}

	private static long elapsedMillis(long startNanos) {
		return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
	}

	/**
	 * Messages API 流式事件来源，message_stop 时发出用量与完成事件
	 */
	private final class MessagesStreamSource implements ModelStreamSource {

		private final MessageCreateParams params;
		private final RequestOptions requestOptions;
		private final String provider;
		private final String traceId;
		private final Map<String, JsonNode> rawUsage = new LinkedHashMap<>();
		private final Map<Integer, PendingBlock> blocks = new TreeMap<>();
		private final Map<Integer, Integer> toolIndexes = new HashMap<>();
		private HttpResponseFor<StreamResponse<RawMessageStreamEvent>> rawResponse;
		private StreamResponse<RawMessageStreamEvent> streamResponse;
		private Iterator<RawMessageStreamEvent> vendorEvents;
		private String requestId;
		private String model;
		private String messageId;
		private String stopReason;

		private MessagesStreamSource(MessageCreateParams params, RequestOptions requestOptions, String provider, String traceId) {
			this.params = params;
			this.requestOptions = requestOptions;
			this.provider = provider;
			this.traceId = traceId;
		}

		@Override
		public boolean readNext(ModelEventEmitter emitter) {
			try {
				if (vendorEvents == null) {
					rawResponse = client.messages().withRawResponse().createStreaming(params, requestOptions);
					requestId = readRequestId(rawResponse.headers());
					streamResponse = rawResponse.parse();
					// SDK 流式响应只提供 Stream 接口，这里仅取迭代器逐个读取事件
					vendorEvents = streamResponse.stream().iterator(); // check-ignore
				}

				if (!vendorEvents.hasNext()) {
					return false;
				}

				handleEvent(AnthropicMessagesApi.toNode(vendorEvents.next()), emitter);
				return true;
			} catch (RuntimeException exception) {
				throw mapException(exception, provider);
			}
		}

		private void handleEvent(JsonNode eventNode, ModelEventEmitter emitter) {
			String eventType = JsonSupport.textOrNull(eventNode, "type");

			if ("message_start".equals(eventType)) {
				JsonNode messageNode = eventNode.get("message");
				model = JsonSupport.textOrNull(messageNode, "model");
				messageId = JsonSupport.textOrNull(messageNode, "id");
				copyUsage(JsonSupport.fieldOrNull(messageNode, "usage"), false);
				emitter.emitStarted(model, messageId);
			} else if ("content_block_start".equals(eventType)) {
				handleBlockStart(eventNode, emitter);
			} else if ("content_block_delta".equals(eventType)) {
				handleBlockDelta(eventNode, emitter);
			} else if ("message_delta".equals(eventType)) {
				stopReason = JsonSupport.textOrNull(JsonSupport.fieldOrNull(eventNode, "delta"), "stop_reason");
				copyUsage(JsonSupport.fieldOrNull(eventNode, "usage"), true);
			} else if ("message_stop".equals(eventType)) {
				emitFinalEvents(emitter);
			}
		}

		private void handleBlockStart(JsonNode eventNode, ModelEventEmitter emitter) {
			JsonNode blockNode = eventNode.get("content_block");
			int blockIndex = eventNode.path("index").asInt();
			String blockType = JsonSupport.textOrNull(blockNode, "type");

			if ("text".equals(blockType)) {
				String text = JsonSupport.textOrNull(blockNode, "text");
				PendingBlock block = new PendingBlock(null, null, false);
				block.content.append(text == null ? "" : text);
				blocks.put(blockIndex, block);
				emitter.emitTextDelta(text);
			} else if ("tool_use".equals(blockType)) {
				int toolIndex = toolIndexes.size();
				toolIndexes.put(blockIndex, toolIndex);
				String toolCallId = JsonSupport.textOrNull(blockNode, "id");
				String toolName = JsonSupport.textOrNull(blockNode, "name");
				blocks.put(blockIndex, new PendingBlock(toolCallId, toolName, true));
				emitter.emitToolCallDelta(toolIndex, toolCallId, toolName, "");
			}
		}

		private void handleBlockDelta(JsonNode eventNode, ModelEventEmitter emitter) {
			int blockIndex = eventNode.path("index").asInt();
			JsonNode deltaNode = eventNode.get("delta");
			String deltaType = JsonSupport.textOrNull(deltaNode, "type");
			PendingBlock block = blocks.get(blockIndex);

			if (block == null) {
				return;
			}

			if ("text_delta".equals(deltaType)) {
				String text = JsonSupport.textOrNull(deltaNode, "text");
				block.content.append(text == null ? "" : text);
				emitter.emitTextDelta(text);
			} else if ("input_json_delta".equals(deltaType)) {
				String partialJson = JsonSupport.textOrNull(deltaNode, "partial_json");
				block.content.append(partialJson == null ? "" : partialJson);
				emitter.emitToolCallDelta(toolIndexes.get(blockIndex), null, null, partialJson);
			}
		}

		/**
		 * message_start 的用量整体写入，message_delta 只覆盖非空字段
		 */
		private void copyUsage(JsonNode usageNode, boolean shouldSkipNull) {
			if (usageNode == null || !usageNode.isObject()) {
				return;
			}

			for (Map.Entry<String, JsonNode> field : usageNode.properties()) {
				if (shouldSkipNull && field.getValue().isNull()) {
					continue;
				}

				rawUsage.put(field.getKey(), field.getValue());
			}
		}

		private void emitFinalEvents(ModelEventEmitter emitter) {
			List<ContentPart> contentParts = new ArrayList<>();

			for (PendingBlock block : blocks.values()) {
				if (!block.isToolCall) {
					contentParts.add(ContentPart.text(block.content.toString()));
					continue;
				}

				String arguments = block.content.length() == 0 ? "{}" : block.content.toString();
				contentParts.add(ContentPart.toolCall(block.toolCallId, block.toolName, arguments));
			}

			Usage usage = AnthropicMessagesApi.mapUsage(JsonSupport.toTree(rawUsage));
			emitter.emitUsage(usage);
			emitter.emitCompleted(new ModelResponse(provider, model, messageId, contentParts, AnthropicMessagesApi.mapFinishReason(stopReason), stopReason, usage, traceId, requestId, null));
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
	 * 流式过程中按 block 序号累积的内容
	 */
	private static final class PendingBlock {

		private final String toolCallId;
		private final String toolName;
		private final boolean isToolCall;
		private final StringBuilder content = new StringBuilder();

		private PendingBlock(String toolCallId, String toolName, boolean isToolCall) {
			this.toolCallId = toolCallId;
			this.toolName = toolName;
			this.isToolCall = isToolCall;
		}
	}
}
