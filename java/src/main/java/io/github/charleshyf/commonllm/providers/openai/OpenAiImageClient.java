package io.github.charleshyf.commonllm.providers.openai;

import com.openai.client.OpenAIClient;
import com.openai.core.RequestOptions;
import com.openai.core.Timeout;
import com.openai.models.responses.ResponseCreateParams;
import io.github.charleshyf.commonllm.config.ProviderConfig;
import io.github.charleshyf.commonllm.core.CommonLlmException;
import io.github.charleshyf.commonllm.core.ImageClient;
import io.github.charleshyf.commonllm.core.ImageEventEmitter;
import io.github.charleshyf.commonllm.core.ImageFeature;
import io.github.charleshyf.commonllm.core.ImageGenerationLoop;
import io.github.charleshyf.commonllm.core.ImageRequest;
import io.github.charleshyf.commonllm.core.ImageRequestValidator;
import io.github.charleshyf.commonllm.core.ImageResponse;
import io.github.charleshyf.commonllm.core.ImageStream;
import io.github.charleshyf.commonllm.core.ImageStreamSource;
import io.github.charleshyf.commonllm.core.ProviderCapability;
import io.github.charleshyf.commonllm.core.ProviderOptionsMerger;
import io.github.charleshyf.commonllm.observability.CallLogger;
import io.github.charleshyf.commonllm.observability.IdGenerator;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基于官方 OpenAI Java SDK 的图片生成客户端，按 image_api 选择 Images API 或 Responses 图片工具
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class OpenAiImageClient implements ImageClient {

	private final ProviderConfig config;
	private final IdGenerator idGenerator;
	private final OpenAIClient client;
	private final boolean isResponsesApi;

	/**
	 * 复用同一 Provider 的 OpenAIClient，不另建连接池
	 */
	public OpenAiImageClient(ProviderConfig config, IdGenerator idGenerator, OpenAIClient client) {
		this.config = config;
		this.idGenerator = idGenerator;
		this.client = client;
		this.isResponsesApi = ProviderConfig.IMAGE_API_RESPONSES.equals(config.getImageApi());
	}

	@Override
	public ImageResponse generate(ImageRequest request) {
		String provider = config.getInstanceName();
		String traceId = resolveTraceId(request);
		String imageModel = ImageRequestValidator.validateAndResolveModel(request, config.getDefaultImageModel(), provider);
		long startNanos = System.nanoTime();

		try {
			ImageResponse response = isResponsesApi ? generateWithResponses(request, imageModel, traceId) : generateWithImages(request, imageModel, traceId);
			CallLogger.logModelCall(provider, imageModel, elapsedMillis(startNanos), traceId, response.getUsage(), null);
			return response;
		} catch (CommonLlmException exception) {
			CallLogger.logModelCall(provider, imageModel, elapsedMillis(startNanos), traceId, null, exception.getErrorInfo());
			throw exception;
		}
	}

	@Override
	public ImageStream stream(ImageRequest request) {
		String provider = config.getInstanceName();
		String traceId = resolveTraceId(request);

		try {
			String imageModel = ImageRequestValidator.validateAndResolveModel(request, config.getDefaultImageModel(), provider);
			ImageStreamSource source;

			if (isResponsesApi) {
				String chatModel = OpenAiResponsesImageApi.validateAndResolveChatModel(request, config.getDefaultModel(), true, provider);
				Map<String, Object> extraBody = ProviderOptionsMerger.merge(Map.of(), request.getProviderOptions(), OpenAiResponsesImageApi.MAPPED_FIELDS, provider);
				ResponseCreateParams params = OpenAiResponsesImageApi.buildParams(request, chatModel, imageModel, extraBody);
				source = OpenAiResponsesImageApi.stream(client, params, buildRequestOptions(request), provider, imageModel, traceId, ImageRequestValidator.resolveCount(request));
			} else {
				OpenAiImagesApi.validate(request, true, provider);
				Map<String, Object> extraBody = ProviderOptionsMerger.merge(Map.of(), request.getProviderOptions(), OpenAiImagesApi.MAPPED_FIELDS, provider);
				source = OpenAiImagesApi.stream(client, request, imageModel, extraBody, buildRequestOptions(request), provider, traceId);
			}

			return new ImageStream(new ImageEventEmitter(traceId, provider), source);
		} catch (CommonLlmException exception) {
			return ImageStream.failed(traceId, provider, exception.getErrorInfo());
		}
	}

	private ImageResponse generateWithImages(ImageRequest request, String imageModel, String traceId) {
		String provider = config.getInstanceName();
		OpenAiImagesApi.validate(request, false, provider);
		Map<String, Object> extraBody = ProviderOptionsMerger.merge(Map.of(), request.getProviderOptions(), OpenAiImagesApi.MAPPED_FIELDS, provider);
		return OpenAiImagesApi.generate(client, request, imageModel, extraBody, buildRequestOptions(request), provider, traceId);
	}

	/**
	 * Responses 路径不支持原生多张，n 大于 1 时按 image_concurrency 库内循环
	 */
	private ImageResponse generateWithResponses(ImageRequest request, String imageModel, String traceId) {
		String provider = config.getInstanceName();
		String chatModel = OpenAiResponsesImageApi.validateAndResolveChatModel(request, config.getDefaultModel(), false, provider);
		Map<String, Object> extraBody = ProviderOptionsMerger.merge(Map.of(), request.getProviderOptions(), OpenAiResponsesImageApi.MAPPED_FIELDS, provider);
		ResponseCreateParams params = OpenAiResponsesImageApi.buildParams(request, chatModel, imageModel, extraBody);
		RequestOptions requestOptions = buildRequestOptions(request);
		int count = ImageRequestValidator.resolveCount(request);
		return ImageGenerationLoop.run(count, config.getImageConcurrency(), callIndex -> OpenAiResponsesImageApi.generateOnce(client, params, requestOptions, provider, imageModel, traceId, request.shouldIncludeRawResponse()));
	}

	@Override
	public boolean supports(String feature) {
		return capability().getFeatures().contains(feature);
	}

	@Override
	public ProviderCapability capability() {
		List<String> features = new ArrayList<>();
		features.add(ImageFeature.IMAGE_GENERATION.getValue());
		features.add(ImageFeature.IMAGE_EDIT.getValue());
		features.add(ImageFeature.IMAGE_MASK.getValue());
		features.add(ImageFeature.IMAGE_STREAMING.getValue());

		if (!isResponsesApi) {
			features.add(ImageFeature.IMAGE_MULTIPLE.getValue());
		}

		return new ProviderCapability(config.getInstanceName(), features);
	}

	/**
	 * 返回与 OpenAiModelClient 共用的 com.openai.client.OpenAIClient
	 */
	@Override
	public Object nativeClient() {
		return client;
	}

	/**
	 * SDK 客户端由 OpenAiModelClient 关闭，这里没有自有资源需要释放
	 */
	@Override
	public void close() {
		// 共用的 OpenAIClient 由所属 ModelClient 负责关闭
	}

	/**
	 * 超时取请求 timeoutSeconds，其次配置 image_timeout_seconds
	 */
	private RequestOptions buildRequestOptions(ImageRequest request) {
		double timeoutSeconds = request.getTimeoutSeconds() != null ? request.getTimeoutSeconds() : config.getImageTimeoutSeconds();
		Timeout timeout = Timeout.builder().connect(toDuration(config.getConnectTimeoutSeconds())).request(toDuration(timeoutSeconds)).build();
		return RequestOptions.builder().timeout(timeout).build();
	}

	private String resolveTraceId(ImageRequest request) {
		if (request.getTraceId() != null && !request.getTraceId().isBlank()) {
			return request.getTraceId();
		}

		return idGenerator.newTraceId();
	}

	private static Duration toDuration(double seconds) {
		return Duration.ofMillis(Math.round(seconds * 1000));
	}

	private static long elapsedMillis(long startNanos) {
		return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
	}
}
