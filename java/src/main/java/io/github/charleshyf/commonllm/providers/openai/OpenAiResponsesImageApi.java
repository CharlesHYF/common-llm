package io.github.charleshyf.commonllm.providers.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.core.RequestOptions;
import com.openai.core.http.HttpResponseFor;
import com.openai.core.http.StreamResponse;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputContent;
import com.openai.models.responses.ResponseInputImage;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseInputText;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.Tool;
import com.openai.models.responses.ToolChoiceTypes;
import io.github.charleshyf.commonllm.core.CommonLlmException;
import io.github.charleshyf.commonllm.core.ErrorCategory;
import io.github.charleshyf.commonllm.core.ErrorInfo;
import io.github.charleshyf.commonllm.core.GeneratedImage;
import io.github.charleshyf.commonllm.core.ImageEventEmitter;
import io.github.charleshyf.commonllm.core.ImageInput;
import io.github.charleshyf.commonllm.core.ImageRequest;
import io.github.charleshyf.commonllm.core.ImageRequestValidator;
import io.github.charleshyf.commonllm.core.ImageResponse;
import io.github.charleshyf.commonllm.core.ImageStreamSource;
import io.github.charleshyf.commonllm.core.JsonSupport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenAI Responses API image_generation 工具路径：对话模型调用图片工具生成图片
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
final class OpenAiResponsesImageApi {

	static final Set<String> MAPPED_FIELDS = Set.of("model", "input", "tools", "tool_choice", "stream");

	private static final String PATH_NAME = "OpenAI Responses 图片工具";

	private OpenAiResponsesImageApi() {
	}

	/**
	 * Responses 路径不支持 Gemini 专属尺寸参数；partialImages 只能用于流式；对话模型取配置 default_model
	 */
	static String validateAndResolveChatModel(ImageRequest request, String defaultModel, boolean isStreaming, String provider) {
		ImageRequestValidator.rejectIfPresent(request.getAspectRatio(), "aspectRatio", PATH_NAME, provider);
		ImageRequestValidator.rejectIfPresent(request.getImageSize(), "imageSize", PATH_NAME, provider);

		if (!isStreaming) {
			ImageRequestValidator.rejectIfPresent(request.getPartialImages(), "partialImages（仅 streamImage 可用）", PATH_NAME, provider);
		}

		if (defaultModel == null || defaultModel.isBlank()) {
			throw CommonLlmException.of(ErrorCategory.CONFIG_INVALID, "image_api=responses 需要配置 default_model 作为对话模型", provider);
		}

		return defaultModel;
	}

	/**
	 * 组装请求：prompt 与输入图片作为 user 消息，图片参数写在 image_generation 工具上并强制选择该工具
	 */
	static ResponseCreateParams buildParams(ImageRequest request, String chatModel, String imageModel, Map<String, Object> extraBody) {
		List<ResponseInputContent> contents = new ArrayList<>();
		contents.add(ResponseInputContent.ofInputText(ResponseInputText.builder().text(request.getPrompt()).build()));

		for (ImageInput image : request.getImages()) {
			ResponseInputImage inputImage = ResponseInputImage.builder().detail(ResponseInputImage.Detail.AUTO).imageUrl(image.toDataUrl()).build();
			contents.add(ResponseInputContent.ofInputImage(inputImage));
		}

		EasyInputMessage userMessage = EasyInputMessage.builder().role(EasyInputMessage.Role.USER).content(EasyInputMessage.Content.ofResponseInputMessageContentList(contents)).build();
		Tool.ImageGeneration.Builder toolBuilder = Tool.ImageGeneration.builder().model(imageModel);

		if (request.getSize() != null) {
			toolBuilder.size(request.getSize());
		}

		if (request.getQuality() != null) {
			toolBuilder.quality(Tool.ImageGeneration.Quality.of(request.getQuality()));
		}

		if (request.getOutputFormat() != null) {
			toolBuilder.outputFormat(Tool.ImageGeneration.OutputFormat.of(request.getOutputFormat()));
		}

		if (request.getOutputCompression() != null) {
			toolBuilder.outputCompression(request.getOutputCompression().longValue());
		}

		if (request.getBackground() != null) {
			toolBuilder.background(Tool.ImageGeneration.Background.of(request.getBackground()));
		}

		if (request.getPartialImages() != null) {
			toolBuilder.partialImages(request.getPartialImages().longValue());
		}

		if (request.getMask() != null) {
			toolBuilder.inputImageMask(Tool.ImageGeneration.InputImageMask.builder().imageUrl(request.getMask().toDataUrl()).build());
		}

		ResponseCreateParams.Builder builder = ResponseCreateParams.builder()
			.model(chatModel)
			.inputOfResponse(List.of(ResponseInputItem.ofEasyInputMessage(userMessage)))
			.addTool(toolBuilder.build())
			.toolChoice(ToolChoiceTypes.builder().type(ToolChoiceTypes.Type.IMAGE_GENERATION).build());

		for (Map.Entry<String, Object> extraEntry : extraBody.entrySet()) {
			builder.putAdditionalBodyProperty(extraEntry.getKey(), JsonValue.from(extraEntry.getValue()));
		}

		return builder.build();
	}

	/**
	 * 单次同步调用，requestId 取响应头 x-request-id
	 */
	static ImageResponse generateOnce(OpenAIClient client, ResponseCreateParams params, RequestOptions requestOptions, String provider, String imageModel, String traceId, boolean shouldIncludeRaw) {
		try (HttpResponseFor<Response> rawResponse = client.responses().withRawResponse().create(params, requestOptions)) {
			return mapResponse(provider, imageModel, OpenAiSupport.toNode(rawResponse.parse()), OpenAiSupport.readRequestId(rawResponse.headers()), traceId, shouldIncludeRaw);
		} catch (RuntimeException exception) {
			throw OpenAiSupport.mapException(exception, provider);
		}
	}

	/**
	 * 映射 Responses 响应：图片取 image_generation_call.result，文字取 output_text 拼接，model 为图片模型
	 */
	static ImageResponse mapResponse(String provider, String imageModel, JsonNode responseNode, String requestId, String traceId, boolean shouldIncludeRaw) {
		List<GeneratedImage> images = new ArrayList<>();
		StringBuilder textBuilder = new StringBuilder();
		JsonNode outputNode = responseNode.get("output");

		if (outputNode != null && outputNode.isArray()) {
			for (JsonNode outputItem : outputNode) {
				String itemType = JsonSupport.textOrNull(outputItem, "type");

				if ("image_generation_call".equals(itemType) && JsonSupport.textOrNull(outputItem, "result") != null) {
					images.add(toGeneratedImage(outputItem));
				} else if ("message".equals(itemType)) {
					appendOutputText(outputItem, textBuilder);
				}
			}
		}

		Object rawResponse = shouldIncludeRaw ? JsonSupport.toMap(responseNode) : null;
		return new ImageResponse(provider, imageModel, JsonSupport.textOrNull(responseNode, "id"), images, textBuilder.toString(), OpenAiSupport.mapResponsesUsage(responseNode.get("usage")), traceId, requestId, rawResponse);
	}

	private static GeneratedImage toGeneratedImage(JsonNode imageCall) {
		String mimeType = ImageRequestValidator.resolveMimeType(JsonSupport.textOrNull(imageCall, "output_format"));
		return GeneratedImage.fromBase64(JsonSupport.textOrNull(imageCall, "result"), mimeType, null, JsonSupport.textOrNull(imageCall, "revised_prompt"));
	}

	private static void appendOutputText(JsonNode messageItem, StringBuilder textBuilder) {
		JsonNode contentNode = messageItem.get("content");

		if (contentNode == null || !contentNode.isArray()) {
			return;
		}

		for (JsonNode content : contentNode) {
			if ("output_text".equals(JsonSupport.textOrNull(content, "type"))) {
				String text = JsonSupport.textOrNull(content, "text");
				textBuilder.append(text == null ? "" : text);
			}
		}
	}

	/**
	 * 流式来源：n 大于 1 时按顺序逐次调用，imageIndex 跨次连续编号，结束时发出合并后的用量与结果
	 */
	static ImageStreamSource stream(OpenAIClient client, ResponseCreateParams params, RequestOptions requestOptions, String provider, String imageModel, String traceId, int count) {
		return new ResponsesImageStreamSource(client, params, requestOptions, provider, imageModel, traceId, count);
	}

	/**
	 * Responses 图片流式事件来源
	 */
	private static final class ResponsesImageStreamSource implements ImageStreamSource {

		private final OpenAIClient client;
		private final ResponseCreateParams params;
		private final RequestOptions requestOptions;
		private final String provider;
		private final String imageModel;
		private final String traceId;
		private final int count;
		private final List<ImageResponse> completedResponses = new ArrayList<>();
		private final Map<String, Integer> imageIndexes = new HashMap<>();
		private int imageOffset;
		private int startedIterations;
		private boolean hasUsage;
		private HttpResponseFor<StreamResponse<ResponseStreamEvent>> rawResponse;
		private StreamResponse<ResponseStreamEvent> streamResponse;
		private Iterator<ResponseStreamEvent> vendorEvents;
		private String requestId;

		private ResponsesImageStreamSource(OpenAIClient client, ResponseCreateParams params, RequestOptions requestOptions, String provider, String imageModel, String traceId, int count) {
			this.client = client;
			this.params = params;
			this.requestOptions = requestOptions;
			this.provider = provider;
			this.imageModel = imageModel;
			this.traceId = traceId;
			this.count = count;
		}

		@Override
		public boolean readNext(ImageEventEmitter emitter) {
			try {
				if (vendorEvents == null) {
					startedIterations++;
					rawResponse = client.responses().withRawResponse().createStreaming(params, requestOptions);
					requestId = OpenAiSupport.readRequestId(rawResponse.headers());
					streamResponse = rawResponse.parse();
					// SDK 流式响应只提供 Stream 接口，这里仅取迭代器逐个读取事件
					vendorEvents = streamResponse.stream().iterator(); // check-ignore
				}

				if (vendorEvents.hasNext()) {
					handleEvent(OpenAiSupport.toNode(vendorEvents.next()), emitter);
					return true;
				}

				return finishIteration(emitter);
			} catch (RuntimeException exception) {
				throw OpenAiSupport.mapException(exception, provider);
			}
		}

		private void handleEvent(JsonNode eventNode, ImageEventEmitter emitter) {
			String eventType = JsonSupport.textOrNull(eventNode, "type");

			if ("response.created".equals(eventType)) {
				emitter.emitStarted(imageModel, JsonSupport.textOrNull(eventNode.get("response"), "id"));
			} else if ("response.output_item.added".equals(eventType) && "image_generation_call".equals(JsonSupport.textOrNull(eventNode.get("item"), "type"))) {
				imageIndexes.put(JsonSupport.textOrNull(eventNode.get("item"), "id"), imageOffset + imageIndexes.size());
			} else if ("response.image_generation_call.partial_image".equals(eventType)) {
				Integer imageIndex = imageIndexes.get(JsonSupport.textOrNull(eventNode, "item_id"));
				Long partialIndex = JsonSupport.longOrNull(eventNode, "partial_image_index");
				String mimeType = ImageRequestValidator.resolveMimeType(JsonSupport.textOrNull(eventNode, "output_format"));
				emitter.emitPartialImage(imageIndex == null ? imageOffset : imageIndex, partialIndex == null ? 0 : partialIndex.intValue(), JsonSupport.textOrNull(eventNode, "partial_image_b64"), mimeType);
			} else if ("response.output_item.done".equals(eventType) && "image_generation_call".equals(JsonSupport.textOrNull(eventNode.get("item"), "type"))) {
				JsonNode item = eventNode.get("item");
				Integer imageIndex = imageIndexes.get(JsonSupport.textOrNull(item, "id"));
				emitter.emitImageCompleted(imageIndex == null ? imageOffset : imageIndex, toGeneratedImage(item));
			} else if ("response.completed".equals(eventType)) {
				JsonNode responseNode = eventNode.get("response");
				hasUsage = hasUsage || JsonSupport.fieldOrNull(responseNode, "usage") != null;
				completedResponses.add(mapResponse(provider, imageModel, responseNode, requestId, traceId, false));
			} else if ("response.failed".equals(eventType) || "error".equals(eventType)) {
				JsonNode errorNode = JsonSupport.fieldOrNull(JsonSupport.fieldOrNull(eventNode, "response"), "error");
				JsonNode sourceNode = errorNode == null ? eventNode : errorNode;
				String message = JsonSupport.textOrNull(sourceNode, "message");
				throw new CommonLlmException(ErrorInfo.builder(ErrorCategory.PROVIDER_ERROR, message == null ? "OpenAI 图片生成失败" : message).provider(provider).vendorCode(JsonSupport.textOrNull(sourceNode, "code")).requestId(requestId).build());
			}
		}

		/**
		 * 一次调用结束：还有剩余次数时开始下一次，否则发出合并后的用量与完成事件
		 */
		private boolean finishIteration(ImageEventEmitter emitter) {
			close();

			if (completedResponses.size() < startedIterations) {
				throw new CommonLlmException(ErrorInfo.builder(ErrorCategory.PROVIDER_ERROR, "OpenAI 流式响应在 response.completed 前结束").provider(provider).requestId(requestId).build());
			}

			if (completedResponses.size() < count) {
				imageOffset += completedResponses.get(completedResponses.size() - 1).getImages().size();
				imageIndexes.clear();
				vendorEvents = null;
				streamResponse = null;
				rawResponse = null;
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
			if (streamResponse != null) {
				streamResponse.close();
			}

			if (rawResponse != null) {
				rawResponse.close();
			}
		}
	}
}
