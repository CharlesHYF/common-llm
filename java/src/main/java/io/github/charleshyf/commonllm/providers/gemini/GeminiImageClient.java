package io.github.charleshyf.commonllm.providers.gemini;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.genai.Client;
import com.google.genai.ResponseStream;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.ImageConfig;
import com.google.genai.types.Part;
import io.github.charleshyf.commonllm.config.ProviderConfig;
import io.github.charleshyf.commonllm.core.CommonLlmException;
import io.github.charleshyf.commonllm.core.ErrorCategory;
import io.github.charleshyf.commonllm.core.ErrorInfo;
import io.github.charleshyf.commonllm.core.GeneratedImage;
import io.github.charleshyf.commonllm.core.ImageClient;
import io.github.charleshyf.commonllm.core.ImageEventEmitter;
import io.github.charleshyf.commonllm.core.ImageFeature;
import io.github.charleshyf.commonllm.core.ImageGenerationLoop;
import io.github.charleshyf.commonllm.core.ImageInput;
import io.github.charleshyf.commonllm.core.ImageRequest;
import io.github.charleshyf.commonllm.core.ImageRequestValidator;
import io.github.charleshyf.commonllm.core.ImageResponse;
import io.github.charleshyf.commonllm.core.ImageStream;
import io.github.charleshyf.commonllm.core.ImageStreamSource;
import io.github.charleshyf.commonllm.core.JsonSupport;
import io.github.charleshyf.commonllm.core.ProviderCapability;
import io.github.charleshyf.commonllm.core.ProviderOptionsMerger;
import io.github.charleshyf.commonllm.core.Usage;
import io.github.charleshyf.commonllm.observability.CallLogger;
import io.github.charleshyf.commonllm.observability.IdGenerator;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于 generateContent 原生出图的 Gemini 图片生成客户端
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class GeminiImageClient implements ImageClient {

	private static final Set<String> MAPPED_FIELDS = Set.of("contents", "generationConfig");
	private static final String PATH_NAME = "Gemini 图片生成";

	private final ProviderConfig config;
	private final IdGenerator idGenerator;
	private final Client client;

	/**
	 * 复用同一 Provider 的 com.google.genai.Client
	 */
	public GeminiImageClient(ProviderConfig config, IdGenerator idGenerator, Client client) {
		this.config = config;
		this.idGenerator = idGenerator;
		this.client = client;
	}

	@Override
	public ImageResponse generate(ImageRequest request) {
		String provider = config.getInstanceName();
		String traceId = resolveTraceId(request);
		String model = validate(request, false);
		List<Content> contents = buildContents(request);
		GenerateContentConfig generateConfig = buildConfig(request);
		long startNanos = System.nanoTime();

		try {
			ImageResponse response = ImageGenerationLoop.run(ImageRequestValidator.resolveCount(request), config.getImageConcurrency(), callIndex -> generateOnce(model, contents, generateConfig, traceId, request.shouldIncludeRawResponse()));
			CallLogger.logModelCall(provider, model, elapsedMillis(startNanos), traceId, response.getUsage(), null);
			return response;
		} catch (CommonLlmException exception) {
			CallLogger.logModelCall(provider, model, elapsedMillis(startNanos), traceId, null, exception.getErrorInfo());
			throw exception;
		}
	}

	@Override
	public ImageStream stream(ImageRequest request) {
		String provider = config.getInstanceName();
		String traceId = resolveTraceId(request);

		try {
			String model = validate(request, true);
			GeminiImageStreamSource source = new GeminiImageStreamSource(model, buildContents(request), buildConfig(request), traceId, ImageRequestValidator.resolveCount(request));
			return new ImageStream(new ImageEventEmitter(traceId, provider), source);
		} catch (CommonLlmException exception) {
			return ImageStream.failed(traceId, provider, exception.getErrorInfo());
		}
	}

	/**
	 * Gemini 不支持 OpenAI 专属参数、蒙版、部分图片与 URL 输入，一律报 SDK_UNSUPPORTED_FEATURE
	 */
	private String validate(ImageRequest request, boolean isStreaming) {
		String provider = config.getInstanceName();
		String model = ImageRequestValidator.validateAndResolveModel(request, config.getDefaultImageModel(), provider);
		ImageRequestValidator.rejectIfPresent(request.getSize(), "size（请使用 aspectRatio 与 imageSize）", PATH_NAME, provider);
		ImageRequestValidator.rejectIfPresent(request.getQuality(), "quality", PATH_NAME, provider);
		ImageRequestValidator.rejectIfPresent(request.getOutputFormat(), "outputFormat", PATH_NAME, provider);
		ImageRequestValidator.rejectIfPresent(request.getOutputCompression(), "outputCompression", PATH_NAME, provider);
		ImageRequestValidator.rejectIfPresent(request.getBackground(), "background", PATH_NAME, provider);
		ImageRequestValidator.rejectIfPresent(request.getMask(), "mask", PATH_NAME, provider);
		ImageRequestValidator.rejectUrlInputs(request, PATH_NAME, provider);
		Integer partialImages = request.getPartialImages();

		if (partialImages != null && (!isStreaming || partialImages > 0)) {
			ImageRequestValidator.rejectIfPresent(partialImages, "partialImages", PATH_NAME, provider);
		}

		return model;
	}

	private static List<Content> buildContents(ImageRequest request) {
		List<Part> parts = new ArrayList<>();
		parts.add(Part.fromText(request.getPrompt()));

		for (ImageInput image : request.getImages()) {
			parts.add(Part.fromBytes(image.toBytes(), image.getMimeType()));
		}

		return List.of(Content.builder().role("user").parts(parts).build());
	}

	private GenerateContentConfig buildConfig(ImageRequest request) {
		GenerateContentConfig.Builder builder = GenerateContentConfig.builder().responseModalities(List.of("TEXT", "IMAGE"));

		if (request.getAspectRatio() != null || request.getImageSize() != null) {
			ImageConfig.Builder imageConfig = ImageConfig.builder();

			if (request.getAspectRatio() != null) {
				imageConfig.aspectRatio(request.getAspectRatio());
			}

			if (request.getImageSize() != null) {
				imageConfig.imageSize(request.getImageSize());
			}

			builder.imageConfig(imageConfig.build());
		}

		Map<String, Object> extraBody = ProviderOptionsMerger.merge(Map.of(), request.getProviderOptions(), MAPPED_FIELDS, config.getInstanceName());
		double timeoutSeconds = request.getTimeoutSeconds() != null ? request.getTimeoutSeconds() : config.getImageTimeoutSeconds();
		HttpOptions.Builder httpOptions = HttpOptions.builder().timeout((int) Math.round(timeoutSeconds * 1000));

		if (!extraBody.isEmpty()) {
			httpOptions.extraBody(extraBody);
		}

		return builder.httpOptions(httpOptions.build()).build();
	}

	private ImageResponse generateOnce(String model, List<Content> contents, GenerateContentConfig generateConfig, String traceId, boolean shouldIncludeRaw) {
		String provider = config.getInstanceName();

		try {
			JsonNode responseNode = toNode(client.models.generateContent(model, contents, generateConfig).toJson());
			ImageResponse response = mapResponse(provider, responseNode, traceId, shouldIncludeRaw);

			if (response.getImages().isEmpty()) {
				throw noImageError(provider, finishReasonOf(responseNode));
			}

			return response;
		} catch (RuntimeException exception) {
			throw GeminiModelClient.mapException(exception, provider);
		}
	}

	/**
	 * 映射响应：只取第一个候选，inlineData 为图片，文本 part 拼接为 text，跳过 thought
	 */
	static ImageResponse mapResponse(String provider, JsonNode responseNode, String traceId, boolean shouldIncludeRaw) {
		List<GeneratedImage> images = new ArrayList<>();
		StringBuilder textBuilder = new StringBuilder();
		collectParts(responseNode, images, textBuilder);
		Object rawResponse = shouldIncludeRaw ? JsonSupport.toMap(responseNode) : null;
		Usage usage = GeminiModelClient.mapUsage(JsonSupport.fieldOrNull(responseNode, "usageMetadata"));
		return new ImageResponse(provider, JsonSupport.textOrNull(responseNode, "modelVersion"), JsonSupport.textOrNull(responseNode, "responseId"), images, textBuilder.toString(), usage, traceId, null, rawResponse);
	}

	private static void collectParts(JsonNode responseNode, List<GeneratedImage> images, StringBuilder textBuilder) {
		JsonNode candidates = JsonSupport.fieldOrNull(responseNode, "candidates");

		if (candidates == null || !candidates.isArray() || candidates.size() == 0) {
			return;
		}

		JsonNode parts = JsonSupport.fieldOrNull(JsonSupport.fieldOrNull(candidates.get(0), "content"), "parts");

		if (parts == null || !parts.isArray()) {
			return;
		}

		for (JsonNode part : parts) {
			if (JsonSupport.isTrue(part, "thought")) {
				continue;
			}

			JsonNode inlineData = JsonSupport.fieldOrNull(part, "inlineData");

			if (inlineData != null) {
				String mimeType = JsonSupport.textOrNull(inlineData, "mimeType");
				images.add(GeneratedImage.fromBase64(JsonSupport.textOrNull(inlineData, "data"), mimeType == null ? "image/png" : mimeType, null, null));
			} else if (part.has("text")) {
				textBuilder.append(JsonSupport.textOrNull(part, "text"));
			}
		}
	}

	private static String finishReasonOf(JsonNode responseNode) {
		JsonNode candidates = JsonSupport.fieldOrNull(responseNode, "candidates");

		if (candidates == null || !candidates.isArray() || candidates.size() == 0) {
			return null;
		}

		return JsonSupport.textOrNull(candidates.get(0), "finishReason");
	}

	/**
	 * 响应中没有图片（如被安全策略拦截）时报 PROVIDER_ERROR，vendorCode 为 finishReason
	 */
	private static CommonLlmException noImageError(String provider, String finishReason) {
		return new CommonLlmException(ErrorInfo.builder(ErrorCategory.PROVIDER_ERROR, "Gemini 未返回图片，finishReason=" + finishReason).provider(provider).vendorCode(finishReason).build());
	}

	private static JsonNode toNode(String json) {
		try {
			return JsonSupport.parseTree(json);
		} catch (JsonProcessingException exception) {
			throw new CommonLlmException(ErrorInfo.builder(ErrorCategory.PROVIDER_ERROR, "Gemini 响应无法解析").cause(exception).build(), exception);
		}
	}

	@Override
	public boolean supports(String feature) {
		return capability().getFeatures().contains(feature);
	}

	@Override
	public ProviderCapability capability() {
		return new ProviderCapability(config.getInstanceName(), List.of(ImageFeature.IMAGE_GENERATION.getValue(), ImageFeature.IMAGE_EDIT.getValue()));
	}

	/**
	 * 返回与 GeminiModelClient 共用的 com.google.genai.Client
	 */
	@Override
	public Object nativeClient() {
		return client;
	}

	/**
	 * SDK 客户端由 GeminiModelClient 关闭，这里没有自有资源需要释放
	 */
	@Override
	public void close() {
		// 共用的 Client 由所属 ModelClient 负责关闭
	}

	private String resolveTraceId(ImageRequest request) {
		if (request.getTraceId() != null && !request.getTraceId().isBlank()) {
			return request.getTraceId();
		}

		return idGenerator.newTraceId();
	}

	private static long elapsedMillis(long startNanos) {
		return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
	}

	/**
	 * generateContentStream 事件来源：n 大于 1 时按顺序逐次调用，结束时发出合并后的用量与结果
	 */
	private final class GeminiImageStreamSource implements ImageStreamSource {

		private final String model;
		private final List<Content> contents;
		private final GenerateContentConfig generateConfig;
		private final String traceId;
		private final int count;
		private final List<ImageResponse> completedResponses = new ArrayList<>();
		private int imageOffset;
		private ResponseStream<GenerateContentResponse> responseStream;
		private Iterator<GenerateContentResponse> vendorChunks;
		private List<GeneratedImage> iterationImages = new ArrayList<>();
		private StringBuilder iterationText = new StringBuilder();
		private String iterationModel;
		private String iterationId;
		private String iterationFinishReason;
		private JsonNode iterationUsage;
		private boolean hasUsage;

		private GeminiImageStreamSource(String model, List<Content> contents, GenerateContentConfig generateConfig, String traceId, int count) {
			this.model = model;
			this.contents = contents;
			this.generateConfig = generateConfig;
			this.traceId = traceId;
			this.count = count;
		}

		@Override
		public boolean readNext(ImageEventEmitter emitter) {
			String provider = config.getInstanceName();

			try {
				if (vendorChunks == null) {
					responseStream = client.models.generateContentStream(model, contents, generateConfig);
					vendorChunks = responseStream.iterator();
				}

				if (vendorChunks.hasNext()) {
					handleChunk(toNode(vendorChunks.next().toJson()), emitter);
					return true;
				}

				return finishIteration(emitter);
			} catch (RuntimeException exception) {
				throw GeminiModelClient.mapException(exception, provider);
			}
		}

		private void handleChunk(JsonNode chunkNode, ImageEventEmitter emitter) {
			if (iterationModel == null) {
				iterationModel = JsonSupport.textOrNull(chunkNode, "modelVersion");
				iterationId = JsonSupport.textOrNull(chunkNode, "responseId");
				emitter.emitStarted(iterationModel, iterationId);
			}

			List<GeneratedImage> chunkImages = new ArrayList<>();
			collectParts(chunkNode, chunkImages, iterationText);

			for (GeneratedImage image : chunkImages) {
				emitter.emitImageCompleted(imageOffset + iterationImages.size(), image);
				iterationImages.add(image);
			}

			String finishReason = finishReasonOf(chunkNode);

			if (finishReason != null) {
				iterationFinishReason = finishReason;
			}

			JsonNode usageNode = JsonSupport.fieldOrNull(chunkNode, "usageMetadata");

			if (usageNode != null) {
				iterationUsage = usageNode;
				hasUsage = true;
			}
		}

		/**
		 * 一次调用结束：没有图片报错；还有剩余次数时开始下一次，否则发出合并后的用量与完成事件
		 */
		private boolean finishIteration(ImageEventEmitter emitter) {
			String provider = config.getInstanceName();
			close();

			if (iterationImages.isEmpty()) {
				throw noImageError(provider, iterationFinishReason);
			}

			Usage usage = GeminiModelClient.mapUsage(iterationUsage);
			completedResponses.add(new ImageResponse(provider, iterationModel, iterationId, iterationImages, iterationText.toString(), usage, traceId, null, null));

			if (completedResponses.size() < count) {
				imageOffset += iterationImages.size();
				iterationImages = new ArrayList<>();
				iterationText = new StringBuilder();
				iterationModel = null;
				iterationId = null;
				iterationFinishReason = null;
				iterationUsage = null;
				vendorChunks = null;
				responseStream = null;
				return true;
			}

			ImageResponse response = count == 1 ? completedResponses.get(0) : ImageResponse.combine(completedResponses);

			// usage 事件只在厂商返回过用量时发出
			if (hasUsage) {
				emitter.emitUsage(response.getUsage());
			}

			emitter.emitCompleted(response);
			return false;
		}

		@Override
		public void close() {
			if (responseStream != null) {
				responseStream.close();
			}
		}
	}
}
