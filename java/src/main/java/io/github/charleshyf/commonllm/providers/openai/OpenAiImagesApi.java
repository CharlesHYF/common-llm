package io.github.charleshyf.commonllm.providers.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.core.MultipartField;
import com.openai.core.RequestOptions;
import com.openai.core.http.HttpResponseFor;
import com.openai.core.http.StreamResponse;
import com.openai.models.images.ImageEditParams;
import com.openai.models.images.ImageEditStreamEvent;
import com.openai.models.images.ImageGenStreamEvent;
import com.openai.models.images.ImageGenerateParams;
import com.openai.models.images.ImagesResponse;
import io.github.charleshyf.commonllm.core.CommonLlmException;
import io.github.charleshyf.commonllm.core.ErrorCategory;
import io.github.charleshyf.commonllm.core.GeneratedImage;
import io.github.charleshyf.commonllm.core.ImageEventEmitter;
import io.github.charleshyf.commonllm.core.ImageInput;
import io.github.charleshyf.commonllm.core.ImageRequest;
import io.github.charleshyf.commonllm.core.ImageRequestValidator;
import io.github.charleshyf.commonllm.core.ImageResponse;
import io.github.charleshyf.commonllm.core.ImageStreamSource;
import io.github.charleshyf.commonllm.core.JsonSupport;
import io.github.charleshyf.commonllm.core.Usage;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenAI Images API 路径：无输入图片走 images.generate，有输入图片走 images.edit
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
final class OpenAiImagesApi {

	static final Set<String> MAPPED_FIELDS = Set.of("prompt", "model", "n", "size", "quality", "output_format", "output_compression", "background", "partial_images", "stream", "image", "mask");

	private static final String PATH_NAME = "OpenAI Images API";

	private OpenAiImagesApi() {
	}

	/**
	 * 校验 Images API 不支持的字段：Gemini 专属尺寸参数、URL 输入、超过 16 张输入图与 1-10 之外的 n
	 */
	static void validate(ImageRequest request, boolean isStreaming, String provider) {
		ImageRequestValidator.rejectIfPresent(request.getAspectRatio(), "aspectRatio", PATH_NAME, provider);
		ImageRequestValidator.rejectIfPresent(request.getImageSize(), "imageSize", PATH_NAME, provider);
		ImageRequestValidator.rejectUrlInputs(request, PATH_NAME, provider);

		if (!isStreaming) {
			ImageRequestValidator.rejectIfPresent(request.getPartialImages(), "partialImages（仅 streamImage 可用）", PATH_NAME, provider);
		}

		if (request.getImages().size() > 16) {
			throw CommonLlmException.of(ErrorCategory.CONFIG_INVALID, "OpenAI Images API 最多接受 16 张输入图片", provider);
		}

		if (ImageRequestValidator.resolveCount(request) > 10) {
			throw CommonLlmException.of(ErrorCategory.CONFIG_INVALID, "OpenAI Images API 的 n 取值范围为 1-10", provider);
		}
	}

	/**
	 * 同步生成，requestId 取响应头 x-request-id
	 */
	static ImageResponse generate(OpenAIClient client, ImageRequest request, String model, Map<String, Object> extraBody, RequestOptions requestOptions, String provider, String traceId) {
		try {
			if (request.getImages().isEmpty()) {
				try (HttpResponseFor<ImagesResponse> rawResponse = client.images().withRawResponse().generate(buildGenerateParams(request, model, extraBody), requestOptions)) {
					return mapResponse(provider, model, OpenAiSupport.toNode(rawResponse.parse()), OpenAiSupport.readRequestId(rawResponse.headers()), traceId, request.shouldIncludeRawResponse());
				}
			}

			try (HttpResponseFor<ImagesResponse> rawResponse = client.images().withRawResponse().edit(buildEditParams(request, model, extraBody), requestOptions)) {
				return mapResponse(provider, model, OpenAiSupport.toNode(rawResponse.parse()), OpenAiSupport.readRequestId(rawResponse.headers()), traceId, request.shouldIncludeRawResponse());
			}
		} catch (RuntimeException exception) {
			throw OpenAiSupport.mapException(exception, provider);
		}
	}

	static ImageStreamSource stream(OpenAIClient client, ImageRequest request, String model, Map<String, Object> extraBody, RequestOptions requestOptions, String provider, String traceId) {
		return new ImagesStreamSource(client, request, model, extraBody, requestOptions, provider, traceId);
	}

	private static ImageGenerateParams buildGenerateParams(ImageRequest request, String model, Map<String, Object> extraBody) {
		ImageGenerateParams.Builder builder = ImageGenerateParams.builder().prompt(request.getPrompt()).model(model);

		if (request.getImageCount() != null) {
			builder.n(request.getImageCount().longValue());
		}

		if (request.getSize() != null) {
			builder.size(request.getSize());
		}

		if (request.getQuality() != null) {
			builder.quality(ImageGenerateParams.Quality.of(request.getQuality()));
		}

		if (request.getOutputFormat() != null) {
			builder.outputFormat(ImageGenerateParams.OutputFormat.of(request.getOutputFormat()));
		}

		if (request.getOutputCompression() != null) {
			builder.outputCompression(request.getOutputCompression().longValue());
		}

		if (request.getBackground() != null) {
			builder.background(ImageGenerateParams.Background.of(request.getBackground()));
		}

		if (request.getPartialImages() != null) {
			builder.partialImages(request.getPartialImages().longValue());
		}

		for (Map.Entry<String, Object> extraEntry : extraBody.entrySet()) {
			builder.putAdditionalBodyProperty(extraEntry.getKey(), JsonValue.from(extraEntry.getValue()));
		}

		return builder.build();
	}

	/**
	 * 输入图片以 multipart 上传，单张与多张分别使用 SDK 的 InputStream 与 InputStream 列表形式
	 */
	private static ImageEditParams buildEditParams(ImageRequest request, String model, Map<String, Object> extraBody) {
		List<ImageInput> images = request.getImages();
		ImageEditParams.Image image;

		if (images.size() == 1) {
			image = ImageEditParams.Image.ofInputStream(new ByteArrayInputStream(images.get(0).toBytes()));
		} else {
			List<InputStream> imageStreams = new ArrayList<>();

			for (ImageInput imageInput : images) {
				imageStreams.add(new ByteArrayInputStream(imageInput.toBytes()));
			}

			image = ImageEditParams.Image.ofInputStreams(imageStreams);
		}

		ImageInput firstImage = images.get(0);
		MultipartField<ImageEditParams.Image> imageField = MultipartField.<ImageEditParams.Image>builder()
			.value(image)
			.contentType(firstImage.getMimeType())
			.filename("image" + extensionOf(firstImage.getMimeType()))
			.build();
		ImageEditParams.Builder builder = ImageEditParams.builder().image(imageField).prompt(request.getPrompt()).model(model);

		if (request.getMask() != null) {
			MultipartField<InputStream> maskField = MultipartField.<InputStream>builder()
				.value(new ByteArrayInputStream(request.getMask().toBytes()))
				.contentType(request.getMask().getMimeType())
				.filename("mask" + extensionOf(request.getMask().getMimeType()))
				.build();
			builder.mask(maskField);
		}

		if (request.getImageCount() != null) {
			builder.n(request.getImageCount().longValue());
		}

		if (request.getSize() != null) {
			builder.size(request.getSize());
		}

		if (request.getQuality() != null) {
			builder.quality(ImageEditParams.Quality.of(request.getQuality()));
		}

		if (request.getOutputFormat() != null) {
			builder.outputFormat(ImageEditParams.OutputFormat.of(request.getOutputFormat()));
		}

		if (request.getOutputCompression() != null) {
			builder.outputCompression(request.getOutputCompression().longValue());
		}

		if (request.getBackground() != null) {
			builder.background(ImageEditParams.Background.of(request.getBackground()));
		}

		if (request.getPartialImages() != null) {
			builder.partialImages(request.getPartialImages().longValue());
		}

		for (Map.Entry<String, Object> extraEntry : extraBody.entrySet()) {
			builder.putAdditionalBodyProperty(extraEntry.getKey(), JsonValue.from(extraEntry.getValue()));
		}

		return builder.build();
	}

	private static String extensionOf(String mimeType) {
		if (mimeType == null || !mimeType.startsWith("image/")) {
			return "";
		}

		String subtype = mimeType.substring("image/".length());
		return "." + ("jpeg".equals(subtype) ? "jpg" : subtype);
	}

	/**
	 * 映射 Images API 响应：model 取请求使用的图片模型，id 为 null，MIME 由 output_format 推出
	 */
	static ImageResponse mapResponse(String provider, String model, JsonNode responseNode, String requestId, String traceId, boolean shouldIncludeRaw) {
		String mimeType = ImageRequestValidator.resolveMimeType(JsonSupport.textOrNull(responseNode, "output_format"));
		List<GeneratedImage> images = new ArrayList<>();
		JsonNode dataNode = responseNode.get("data");

		if (dataNode != null && dataNode.isArray()) {
			for (JsonNode imageNode : dataNode) {
				images.add(GeneratedImage.fromBase64(JsonSupport.textOrNull(imageNode, "b64_json"), mimeType, JsonSupport.textOrNull(imageNode, "url"), JsonSupport.textOrNull(imageNode, "revised_prompt")));
			}
		}

		Object rawResponse = shouldIncludeRaw ? JsonSupport.toMap(responseNode) : null;
		return new ImageResponse(provider, model, null, images, "", mapUsage(JsonSupport.fieldOrNull(responseNode, "usage")), traceId, requestId, rawResponse);
	}

	/**
	 * Images API 的用量明细只有 text_tokens 与 image_tokens，缓存与推理用量为 null
	 */
	static Usage mapUsage(JsonNode usageNode) {
		return new Usage(JsonSupport.longOrNull(usageNode, "input_tokens"), JsonSupport.longOrNull(usageNode, "output_tokens"), null, null, JsonSupport.longOrNull(usageNode, "total_tokens"));
	}

	/**
	 * Images API 流式事件来源，生成与编辑的事件类型前缀分别为 image_generation 与 image_edit
	 */
	private static final class ImagesStreamSource implements ImageStreamSource {

		private final OpenAIClient client;
		private final ImageRequest request;
		private final String model;
		private final Map<String, Object> extraBody;
		private final RequestOptions requestOptions;
		private final String provider;
		private final String traceId;
		private final List<GeneratedImage> images = new ArrayList<>();
		private HttpResponseFor<? extends StreamResponse<?>> rawResponse;
		private StreamResponse<?> streamResponse;
		private Iterator<?> vendorEvents;
		private String requestId;
		private Usage usage = new Usage(null, null, null, null, null);
		private boolean hasUsage;

		private ImagesStreamSource(OpenAIClient client, ImageRequest request, String model, Map<String, Object> extraBody, RequestOptions requestOptions, String provider, String traceId) {
			this.client = client;
			this.request = request;
			this.model = model;
			this.extraBody = extraBody;
			this.requestOptions = requestOptions;
			this.provider = provider;
			this.traceId = traceId;
		}

		@Override
		public boolean readNext(ImageEventEmitter emitter) {
			try {
				if (vendorEvents == null) {
					openStream();
					emitter.emitStarted(model, null);
				}

				if (!vendorEvents.hasNext()) {
					ImageResponse response = new ImageResponse(provider, model, null, images, "", usage, traceId, requestId, null);

					// usage 事件只在厂商返回过用量时发出
					if (hasUsage) {
						emitter.emitUsage(usage);
					}

					emitter.emitCompleted(response);
					return false;
				}

				handleEvent(OpenAiSupport.toNode(vendorEvents.next()), emitter);
				return true;
			} catch (RuntimeException exception) {
				throw OpenAiSupport.mapException(exception, provider);
			}
		}

		private void openStream() {
			if (request.getImages().isEmpty()) {
				HttpResponseFor<StreamResponse<ImageGenStreamEvent>> generateResponse = client.images().withRawResponse().generateStreaming(buildGenerateParams(request, model, extraBody), requestOptions);
				rawResponse = generateResponse;
				requestId = OpenAiSupport.readRequestId(generateResponse.headers());
				StreamResponse<ImageGenStreamEvent> parsed = generateResponse.parse();
				streamResponse = parsed;
				// SDK 流式响应只提供 Stream 接口，这里仅取迭代器逐个读取事件
				vendorEvents = parsed.stream().iterator(); // check-ignore
				return;
			}

			HttpResponseFor<StreamResponse<ImageEditStreamEvent>> editResponse = client.images().withRawResponse().editStreaming(buildEditParams(request, model, extraBody), requestOptions);
			rawResponse = editResponse;
			requestId = OpenAiSupport.readRequestId(editResponse.headers());
			StreamResponse<ImageEditStreamEvent> parsed = editResponse.parse();
			streamResponse = parsed;
			// SDK 流式响应只提供 Stream 接口，这里仅取迭代器逐个读取事件
			vendorEvents = parsed.stream().iterator(); // check-ignore
		}

		private void handleEvent(JsonNode eventNode, ImageEventEmitter emitter) {
			String eventType = JsonSupport.textOrNull(eventNode, "type");

			if (eventType == null) {
				return;
			}

			String mimeType = ImageRequestValidator.resolveMimeType(JsonSupport.textOrNull(eventNode, "output_format"));

			if (eventType.endsWith(".partial_image")) {
				Long partialIndex = JsonSupport.longOrNull(eventNode, "partial_image_index");
				emitter.emitPartialImage(images.size(), partialIndex == null ? 0 : partialIndex.intValue(), JsonSupport.textOrNull(eventNode, "b64_json"), mimeType);
			} else if (eventType.endsWith(".completed")) {
				GeneratedImage image = GeneratedImage.fromBase64(JsonSupport.textOrNull(eventNode, "b64_json"), mimeType, null, null);
				emitter.emitImageCompleted(images.size(), image);
				images.add(image);
				JsonNode usageNode = JsonSupport.fieldOrNull(eventNode, "usage");

				if (usageNode != null) {
					usage = mapUsage(usageNode);
					hasUsage = true;
				}
			}
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
