package io.github.charleshyf.commonllm.providers.openai;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.RequestOptions;
import com.openai.core.Timeout;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.responses.ResponseCreateParams;
import io.github.charleshyf.commonllm.config.ProviderConfig;
import io.github.charleshyf.commonllm.core.CommonLlmException;
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
import io.github.charleshyf.commonllm.observability.CallLogger;
import io.github.charleshyf.commonllm.observability.IdGenerator;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基于官方 OpenAI Java SDK 的模型客户端，支持 Responses 与 Chat Completions
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class OpenAiModelClient implements ModelClient {


	private final ProviderConfig config;
	private final IdGenerator idGenerator;
	private final OpenAIClient client;
	private final boolean isChatCompletions;

	public OpenAiModelClient(ProviderConfig config, IdGenerator idGenerator) {
		this.config = config;
		this.idGenerator = idGenerator;
		this.isChatCompletions = ProviderConfig.API_CHAT_COMPLETIONS.equals(config.getApi());
		OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder()
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
		Map<String, Object> extraBody = ProviderOptionsMerger.merge(config.getNativeOptions(), request.getProviderOptions(), isChatCompletions ? OpenAiChatApi.MAPPED_FIELDS : OpenAiResponsesApi.MAPPED_FIELDS, provider);
		RequestOptions requestOptions = buildRequestOptions(request);
		long startNanos = System.nanoTime();

		try {
			ModelResponse response;

			if (isChatCompletions) {
				ChatCompletionCreateParams params = OpenAiChatApi.buildParams(request, model, extraBody, false, provider);
				response = OpenAiChatApi.generate(client, params, requestOptions, provider, traceId, request.shouldIncludeRawResponse());
			} else {
				ResponseCreateParams params = OpenAiResponsesApi.buildParams(request, model, extraBody, provider);
				response = OpenAiResponsesApi.generate(client, params, requestOptions, provider, traceId, request.shouldIncludeRawResponse());
			}

			CallLogger.logModelCall(provider, model, elapsedMillis(startNanos), traceId, response.getUsage(), null);
			return response;
		} catch (CommonLlmException exception) {
			CallLogger.logModelCall(provider, model, elapsedMillis(startNanos), traceId, null, exception.getErrorInfo());
			throw exception;
		}
	}

	@Override
	public ModelStream stream(ModelRequest request) {
		String provider = config.getInstanceName();
		String traceId = ModelRequestValidator.resolveTraceId(request, idGenerator.newTraceId());

		try {
			String model = ModelRequestValidator.validateAndResolveModel(request, config.getDefaultModel(), provider);
			Map<String, Object> extraBody = ProviderOptionsMerger.merge(config.getNativeOptions(), request.getProviderOptions(), isChatCompletions ? OpenAiChatApi.MAPPED_FIELDS : OpenAiResponsesApi.MAPPED_FIELDS, provider);
			RequestOptions requestOptions = buildRequestOptions(request);
			ModelStreamSource source;

			if (isChatCompletions) {
				ChatCompletionCreateParams params = OpenAiChatApi.buildParams(request, model, extraBody, true, provider);
				source = OpenAiChatApi.stream(client, params, requestOptions, provider, traceId);
			} else {
				ResponseCreateParams params = OpenAiResponsesApi.buildParams(request, model, extraBody, provider);
				source = OpenAiResponsesApi.stream(client, params, requestOptions, provider, traceId, request.shouldIncludeRawResponse());
			}

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
	 * 返回官方 com.openai.client.OpenAIClient
	 */
	@Override
	public Object nativeClient() {
		return client;
	}

	@Override
	public void close() {
		client.close();
	}

	private RequestOptions buildRequestOptions(ModelRequest request) {
		if (request.getTimeoutSeconds() == null) {
			return RequestOptions.none();
		}

		return RequestOptions.builder().timeout(Timeout.builder().connect(toDuration(config.getConnectTimeoutSeconds())).request(toDuration(request.getTimeoutSeconds())).build()).build();
	}

	private static Duration toDuration(double seconds) {
		return Duration.ofMillis(Math.round(seconds * 1000));
	}

	private static long elapsedMillis(long startNanos) {
		return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
	}
}
