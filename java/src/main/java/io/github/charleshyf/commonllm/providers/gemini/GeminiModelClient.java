package io.github.charleshyf.commonllm.providers.gemini;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.genai.Client;
import com.google.genai.JsonSerializable;
import com.google.genai.ResponseStream;
import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCallingConfig;
import com.google.genai.types.FunctionCallingConfigMode;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.google.genai.types.Part;
import com.google.genai.types.Tool;
import com.google.genai.types.ToolConfig;
import io.github.charleshyf.commonllm.config.ProviderConfig;
import io.github.charleshyf.commonllm.core.CommonLlmException;
import io.github.charleshyf.commonllm.core.ContentPart;
import io.github.charleshyf.commonllm.core.ContentPartType;
import io.github.charleshyf.commonllm.core.ErrorCategory;
import io.github.charleshyf.commonllm.core.ErrorInfo;
import io.github.charleshyf.commonllm.core.FinishReason;
import io.github.charleshyf.commonllm.core.HttpErrorClassifier;
import io.github.charleshyf.commonllm.core.JsonSupport;
import io.github.charleshyf.commonllm.core.Message;
import io.github.charleshyf.commonllm.core.MessageRole;
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
import io.github.charleshyf.commonllm.core.ToolChoice;
import io.github.charleshyf.commonllm.core.ToolChoiceType;
import io.github.charleshyf.commonllm.core.ToolDefinition;
import io.github.charleshyf.commonllm.core.Usage;
import io.github.charleshyf.commonllm.observability.CallLogger;
import io.github.charleshyf.commonllm.observability.IdGenerator;

import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 基于官方 Google GenAI Java SDK 的 Gemini 模型客户端
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class GeminiModelClient implements ModelClient {

	private static final Set<String> MAPPED_FIELDS = Set.of("contents", "systemInstruction", "generationConfig", "tools", "toolConfig");
	private static final Set<String> CONTENT_FILTER_REASONS = Set.of("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY");
	private static final String GENERATED_CALL_ID_PREFIX = "gemini_call_";

	private final ProviderConfig config;
	private final IdGenerator idGenerator;
	private final Client client;

	/**
	 * 重试交给 SDK：attempts 含首次请求，因此为 max_retries + 1
	 */
	public GeminiModelClient(ProviderConfig config, IdGenerator idGenerator) {
		this.config = config;
		this.idGenerator = idGenerator;
		HttpOptions.Builder httpOptions = HttpOptions.builder()
			.timeout(toMillis(config.getTimeoutSeconds()))
			.retryOptions(HttpRetryOptions.builder().attempts(config.getMaxRetries() + 1).build());

		if (config.getBaseUrl() != null) {
			httpOptions.baseUrl(config.getBaseUrl());
		}

		this.client = Client.builder().apiKey(config.getApiKey()).vertexAI(false).httpOptions(httpOptions.build()).build();
	}

	@Override
	public ModelResponse generate(ModelRequest request) {
		String provider = config.getInstanceName();
		String model = ModelRequestValidator.validateAndResolveModel(request, config.getDefaultModel(), provider);
		String traceId = ModelRequestValidator.resolveTraceId(request, idGenerator.newTraceId());
		List<Content> contents = buildContents(request);
		GenerateContentConfig generateConfig = buildConfig(request);
		long startNanos = System.nanoTime();

		try {
			GenerateContentResponse vendorResponse = client.models.generateContent(model, contents, generateConfig);
			ModelResponse response = mapResponse(provider, toNode(vendorResponse), traceId, request.shouldIncludeRawResponse());
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
			GeminiStreamSource source = new GeminiStreamSource(model, buildContents(request), buildConfig(request), provider, traceId);
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
	 * 返回官方 com.google.genai.Client
	 */
	@Override
	public Object nativeClient() {
		return client;
	}

	@Override
	public void close() {
		client.close();
	}

	private List<Content> buildContents(ModelRequest request) {
		String provider = config.getInstanceName();
		List<Content> contents = new ArrayList<>();
		Map<String, String> toolNamesById = new HashMap<>();

		for (Message message : request.getMessages()) {
			if (message.getRole() == MessageRole.SYSTEM) {
				continue;
			}

			List<Part> parts = new ArrayList<>();

			for (ContentPart contentPart : message.getContentParts()) {
				parts.add(buildPart(message.getRole(), contentPart, toolNamesById, provider));
			}

			String role = message.getRole() == MessageRole.ASSISTANT ? "model" : "user";
			contents.add(Content.builder().role(role).parts(parts).build());
		}

		return contents;
	}

	private Part buildPart(MessageRole role, ContentPart contentPart, Map<String, String> toolNamesById, String provider) {
		if (contentPart.getType() == ContentPartType.TEXT) {
			return Part.fromText(contentPart.getText());
		}

		if (contentPart.getType() == ContentPartType.IMAGE && role == MessageRole.USER) {
			if (contentPart.getBase64() != null) {
				return Part.fromBytes(Base64.getDecoder().decode(contentPart.getBase64()), contentPart.getMimeType());
			}

			return Part.fromUri(contentPart.getUrl(), resolveImageMimeType(contentPart, provider));
		}

		if (contentPart.getType() == ContentPartType.TOOL_CALL && role == MessageRole.ASSISTANT) {
			toolNamesById.put(contentPart.getId(), contentPart.getName());
			return Part.fromFunctionCall(contentPart.getName(), parseArguments(contentPart.getArguments(), provider));
		}

		if (contentPart.getType() == ContentPartType.TOOL_RESULT && role == MessageRole.TOOL) {
			String toolName = toolNamesById.get(contentPart.getToolCallId());

			if (toolName == null) {
				throw CommonLlmException.of(ErrorCategory.CONFIG_INVALID, "Gemini 的 tool_result 需要在之前的 assistant 消息中找到同 id 的 tool_call：" + contentPart.getToolCallId(), provider);
			}

			Map<String, Object> response = new LinkedHashMap<>();
			response.put(contentPart.isError() ? "error" : "output", contentPart.getContent());
			return Part.fromFunctionResponse(toolName, response);
		}

		throw CommonLlmException.of(ErrorCategory.CONFIG_INVALID, role.getValue() + " 消息不支持 " + contentPart.getType().getValue() + " 内容", provider);
	}

	/**
	 * Gemini 的 URL 图片必须带 mimeType，未提供时按扩展名推断
	 */
	private static String resolveImageMimeType(ContentPart contentPart, String provider) {
		if (contentPart.getMimeType() != null) {
			return contentPart.getMimeType();
		}

		String lowerUrl = contentPart.getUrl().toLowerCase(Locale.ROOT);

		if (lowerUrl.endsWith(".png")) {
			return "image/png";
		}

		if (lowerUrl.endsWith(".jpg") || lowerUrl.endsWith(".jpeg")) {
			return "image/jpeg";
		}

		if (lowerUrl.endsWith(".gif")) {
			return "image/gif";
		}

		if (lowerUrl.endsWith(".webp")) {
			return "image/webp";
		}

		throw CommonLlmException.of(ErrorCategory.CONFIG_INVALID, "无法从图片 URL 推断 mimeType，请改用 base64 并提供 mimeType", provider);
	}

	private GenerateContentConfig buildConfig(ModelRequest request) {
		String provider = config.getInstanceName();
		GenerateContentConfig.Builder builder = GenerateContentConfig.builder();
		List<String> systemTexts = new ArrayList<>();

		for (Message message : request.getMessages()) {
			if (message.getRole() == MessageRole.SYSTEM) {
				systemTexts.add(message.joinText());
			}
		}

		if (!systemTexts.isEmpty()) {
			builder.systemInstruction(Content.builder().parts(List.of(Part.fromText(String.join("\n\n", systemTexts)))).build());
		}

		if (request.getTemperature() != null) {
			builder.temperature(request.getTemperature().floatValue());
		}

		if (request.getMaxOutputTokens() != null) {
			builder.maxOutputTokens(request.getMaxOutputTokens().intValue());
		}

		if (!request.getTools().isEmpty()) {
			List<FunctionDeclaration> declarations = new ArrayList<>();

			for (ToolDefinition tool : request.getTools()) {
				FunctionDeclaration.Builder declaration = FunctionDeclaration.builder().name(tool.getName()).parametersJsonSchema(tool.getParameters());

				if (tool.getDescription() != null) {
					declaration.description(tool.getDescription());
				}

				declarations.add(declaration.build());
			}

			builder.tools(List.of(Tool.builder().functionDeclarations(declarations).build()));
		}

		if (request.getToolChoice() != null) {
			builder.toolConfig(ToolConfig.builder().functionCallingConfig(buildFunctionCallingConfig(request.getToolChoice())).build());
		}

		if (request.getResponseFormat() != null && request.getResponseFormat().isJsonSchema()) {
			builder.responseMimeType("application/json").responseJsonSchema(request.getResponseFormat().getSchema());
		}

		Map<String, Object> extraBody = ProviderOptionsMerger.merge(config.getNativeOptions(), request.getProviderOptions(), MAPPED_FIELDS, provider);

		if (!extraBody.isEmpty() || request.getTimeoutSeconds() != null) {
			HttpOptions.Builder httpOptions = HttpOptions.builder();

			if (!extraBody.isEmpty()) {
				httpOptions.extraBody(extraBody);
			}

			if (request.getTimeoutSeconds() != null) {
				httpOptions.timeout(toMillis(request.getTimeoutSeconds()));
			}

			builder.httpOptions(httpOptions.build());
		}

		return builder.build();
	}

	private static FunctionCallingConfig buildFunctionCallingConfig(ToolChoice toolChoice) {
		if (toolChoice.getType() == ToolChoiceType.AUTO) {
			return FunctionCallingConfig.builder().mode(FunctionCallingConfigMode.Known.AUTO).build();
		}

		if (toolChoice.getType() == ToolChoiceType.NONE) {
			return FunctionCallingConfig.builder().mode(FunctionCallingConfigMode.Known.NONE).build();
		}

		if (toolChoice.getType() == ToolChoiceType.REQUIRED) {
			return FunctionCallingConfig.builder().mode(FunctionCallingConfigMode.Known.ANY).build();
		}

		return FunctionCallingConfig.builder().mode(FunctionCallingConfigMode.Known.ANY).allowedFunctionNames(List.of(toolChoice.getName())).build();
	}

	/**
	 * 按契约规则映射完整响应：只取第一个候选，跳过 thought 部分，Gemini 没有请求 ID
	 */
	static ModelResponse mapResponse(String provider, JsonNode responseNode, String traceId, boolean shouldIncludeRaw) {
		List<ContentPart> contentParts = new ArrayList<>();
		String vendorFinishReason = collectParts(responseNode, 0, contentParts);
		Usage usage = mapUsage(JsonSupport.fieldOrNull(responseNode, "usageMetadata"));
		Map<String, Object> rawResponse = shouldIncludeRaw ? JsonSupport.toMap(responseNode) : null;
		return new ModelResponse(provider, JsonSupport.textOrNull(responseNode, "modelVersion"), JsonSupport.textOrNull(responseNode, "responseId"), contentParts, mapFinishReason(vendorFinishReason, hasToolCall(contentParts)), vendorFinishReason, usage, traceId, null, rawResponse);
	}

	/**
	 * 提取首个候选的文本与函数调用，缺少 id 的函数调用按出现顺序生成 id，返回 finishReason
	 */
	private static String collectParts(JsonNode responseNode, int toolCountBefore, List<ContentPart> contentParts) {
		JsonNode candidates = JsonSupport.fieldOrNull(responseNode, "candidates");

		if (candidates == null || !candidates.isArray() || candidates.size() == 0) {
			return null;
		}

		JsonNode candidate = candidates.get(0);
		JsonNode parts = JsonSupport.fieldOrNull(JsonSupport.fieldOrNull(candidate, "content"), "parts");
		int toolCount = toolCountBefore;

		if (parts != null && parts.isArray()) {
			for (JsonNode part : parts) {
				if (JsonSupport.isTrue(part, "thought")) {
					continue;
				}

				if (part.has("text")) {
					contentParts.add(ContentPart.text(JsonSupport.textOrNull(part, "text")));
					continue;
				}

				JsonNode functionCall = JsonSupport.fieldOrNull(part, "functionCall");

				if (functionCall == null) {
					continue;
				}

				String callId = JsonSupport.textOrNull(functionCall, "id");

				if (callId == null || callId.isEmpty()) {
					callId = GENERATED_CALL_ID_PREFIX + toolCount;
				}

				toolCount++;
				JsonNode args = JsonSupport.fieldOrNull(functionCall, "args");
				String arguments = args == null ? "{}" : JsonSupport.toJson(args);
				contentParts.add(ContentPart.toolCall(callId, JsonSupport.textOrNull(functionCall, "name"), arguments));
			}
		}

		return JsonSupport.textOrNull(candidate, "finishReason");
	}

	private static boolean hasToolCall(List<ContentPart> contentParts) {
		for (ContentPart contentPart : contentParts) {
			if (contentPart.getType() == ContentPartType.TOOL_CALL) {
				return true;
			}
		}

		return false;
	}

	static FinishReason mapFinishReason(String vendorFinishReason, boolean hasToolCall) {
		if (hasToolCall) {
			return FinishReason.TOOL_CALLS;
		}

		if ("STOP".equals(vendorFinishReason)) {
			return FinishReason.STOP;
		}

		if ("MAX_TOKENS".equals(vendorFinishReason)) {
			return FinishReason.LENGTH;
		}

		if (CONTENT_FILTER_REASONS.contains(vendorFinishReason)) {
			return FinishReason.CONTENT_FILTER;
		}

		return FinishReason.OTHER;
	}

	static Usage mapUsage(JsonNode usageNode) {
		if (usageNode == null || !usageNode.isObject()) {
			return Usage.empty();
		}

		return new Usage(
			JsonSupport.longOrNull(usageNode, "promptTokenCount"),
			JsonSupport.longOrNull(usageNode, "candidatesTokenCount"),
			JsonSupport.longOrNull(usageNode, "cachedContentTokenCount"),
			JsonSupport.longOrNull(usageNode, "thoughtsTokenCount"),
			JsonSupport.longOrNull(usageNode, "totalTokenCount")
		);
	}

	private static JsonNode toNode(JsonSerializable vendorObject) {
		try {
			return JsonSupport.parseTree(vendorObject.toJson());
		} catch (JsonProcessingException exception) {
			throw new CommonLlmException(ErrorInfo.builder(ErrorCategory.PROVIDER_ERROR, "Gemini 响应无法解析").cause(exception).build(), exception);
		}
	}

	private static Map<String, Object> parseArguments(String arguments, String provider) {
		if (arguments == null || arguments.isBlank()) {
			return new LinkedHashMap<>();
		}

		try {
			return JsonSupport.parseMap(arguments);
		} catch (JsonProcessingException exception) {
			throw CommonLlmException.of(ErrorCategory.CONFIG_INVALID, "tool_call.arguments 不是合法的 JSON 对象", provider);
		}
	}

	/**
	 * 把 SDK 异常映射为统一错误分类，400 且详情含 API_KEY_INVALID 视为认证失败
	 */
	static CommonLlmException mapException(RuntimeException exception, String provider) {
		if (exception instanceof CommonLlmException) {
			return (CommonLlmException) exception;
		}

		if (exception instanceof ApiException) {
			ApiException apiException = (ApiException) exception;
			String vendorMessage = apiException.message();
			boolean isAuthFailure = apiException.code() == 400 && vendorMessage != null && vendorMessage.contains("API_KEY_INVALID");
			ErrorInfo error = HttpErrorClassifier.buildError(provider, apiException.code(), apiException.status(), vendorMessage, null, false, isAuthFailure, null);
			return new CommonLlmException(error, exception);
		}

		if (exception instanceof GenAiIOException) {
			boolean isTimeout = hasTimeoutCause(exception);
			ErrorCategory category = isTimeout ? ErrorCategory.TIMEOUT : ErrorCategory.NETWORK_ERROR;
			String message = isTimeout ? "请求 Gemini 超时" : "连接 Gemini 失败";
			return new CommonLlmException(ErrorInfo.builder(category, message).provider(provider).retriable(true).cause(exception.getCause()).build(), exception);
		}

		return new CommonLlmException(ErrorInfo.builder(ErrorCategory.INTERNAL_ERROR, "调用 Gemini SDK 失败").provider(provider).cause(exception).build(), exception);
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

	private static int toMillis(double seconds) {
		return (int) Math.round(seconds * 1000);
	}

	private static long elapsedMillis(long startNanos) {
		return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
	}

	/**
	 * generateContentStream 的事件来源，流结束后发出用量与完成事件
	 */
	private final class GeminiStreamSource implements ModelStreamSource {

		private final String model;
		private final List<Content> contents;
		private final GenerateContentConfig generateConfig;
		private final String provider;
		private final String traceId;
		private final StringBuilder textBuilder = new StringBuilder();
		private final List<ContentPart> toolParts = new ArrayList<>();
		private ResponseStream<GenerateContentResponse> responseStream;
		private Iterator<GenerateContentResponse> vendorChunks;
		private String responseModel;
		private String responseId;
		private boolean hasStarted;
		private String vendorFinishReason;
		private JsonNode lastUsage;

		private GeminiStreamSource(String model, List<Content> contents, GenerateContentConfig generateConfig, String provider, String traceId) {
			this.model = model;
			this.contents = contents;
			this.generateConfig = generateConfig;
			this.provider = provider;
			this.traceId = traceId;
		}

		@Override
		public boolean readNext(ModelEventEmitter emitter) {
			try {
				if (vendorChunks == null) {
					responseStream = client.models.generateContentStream(model, contents, generateConfig);
					vendorChunks = responseStream.iterator();
				}

				if (!vendorChunks.hasNext()) {
					emitFinalEvents(emitter);
					return false;
				}

				handleChunk(toNode(vendorChunks.next()), emitter);
				return true;
			} catch (RuntimeException exception) {
				throw mapException(exception, provider);
			}
		}

		private void handleChunk(JsonNode chunkNode, ModelEventEmitter emitter) {
			if (!hasStarted) {
				hasStarted = true;
				responseModel = JsonSupport.textOrNull(chunkNode, "modelVersion");
				responseId = JsonSupport.textOrNull(chunkNode, "responseId");
				emitter.emitStarted(responseModel, responseId);
			}

			List<ContentPart> chunkParts = new ArrayList<>();
			String chunkFinishReason = collectParts(chunkNode, toolParts.size(), chunkParts);

			for (ContentPart chunkPart : chunkParts) {
				if (chunkPart.getType() == ContentPartType.TEXT) {
					textBuilder.append(chunkPart.getText());
					emitter.emitTextDelta(chunkPart.getText());
					continue;
				}

				emitter.emitToolCallDelta(toolParts.size(), chunkPart.getId(), chunkPart.getName(), chunkPart.getArguments());
				toolParts.add(chunkPart);
			}

			if (chunkFinishReason != null && !chunkFinishReason.isEmpty()) {
				vendorFinishReason = chunkFinishReason;
			}

			JsonNode usageNode = JsonSupport.fieldOrNull(chunkNode, "usageMetadata");

			if (usageNode != null) {
				lastUsage = usageNode;
			}
		}

		private void emitFinalEvents(ModelEventEmitter emitter) {
			List<ContentPart> finalParts = new ArrayList<>();

			if (textBuilder.length() > 0) {
				finalParts.add(ContentPart.text(textBuilder.toString()));
			}

			finalParts.addAll(toolParts);
			Usage usage = mapUsage(lastUsage);

			if (lastUsage != null) {
				emitter.emitUsage(usage);
			}

			emitter.emitCompleted(new ModelResponse(provider, responseModel, responseId, finalParts, mapFinishReason(vendorFinishReason, !toolParts.isEmpty()), vendorFinishReason, usage, traceId, null, null));
		}

		@Override
		public void close() {
			if (responseStream != null) {
				responseStream.close();
			}
		}
	}
}
