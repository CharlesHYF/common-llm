package io.github.charleshyf.commonllm.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一图片生成响应
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class ImageResponse {

	private final String provider;
	private final String model;
	private final String id;
	private final List<GeneratedImage> images;
	private final String text;
	private final Usage usage;
	private final String traceId;
	private final String requestId;
	private final Object rawResponse;

	public ImageResponse(String provider, String model, String id, List<GeneratedImage> images, String text, Usage usage, String traceId, String requestId, Object rawResponse) {
		this.provider = provider;
		this.model = model;
		this.id = id;
		this.images = Collections.unmodifiableList(new ArrayList<>(images));
		this.text = text == null ? "" : text;
		this.usage = usage;
		this.traceId = traceId;
		this.requestId = requestId;
		this.rawResponse = rawResponse;
	}

	/**
	 * 合并库内循环的多次结果：图片按调用顺序拼接，用量逐项求和，文字以换行连接，id 与 requestId 取最后一次
	 */
	public static ImageResponse combine(List<ImageResponse> responses) {
		List<GeneratedImage> images = new ArrayList<>();
		List<String> texts = new ArrayList<>();
		List<Object> rawResponses = new ArrayList<>();
		Usage usage = new Usage(0L, 0L, 0L, 0L, 0L);

		for (ImageResponse response : responses) {
			images.addAll(response.images);

			if (!response.text.isEmpty()) {
				texts.add(response.text);
			}

			if (response.rawResponse != null) {
				rawResponses.add(response.rawResponse);
			}

			usage = sumUsage(usage, response.usage);
		}

		ImageResponse last = responses.get(responses.size() - 1);
		Object rawResponse = rawResponses.isEmpty() ? null : rawResponses;
		return new ImageResponse(last.provider, last.model, last.id, images, String.join("\n", texts), usage, last.traceId, last.requestId, rawResponse);
	}

	private static Usage sumUsage(Usage first, Usage second) {
		return new Usage(
			sumCount(first.getInputTokens(), second.getInputTokens()),
			sumCount(first.getOutputTokens(), second.getOutputTokens()),
			sumCount(first.getCachedInputTokens(), second.getCachedInputTokens()),
			sumCount(first.getReasoningTokens(), second.getReasoningTokens()),
			sumCount(first.getTotalTokens(), second.getTotalTokens())
		);
	}

	/**
	 * 任一方为 null 则结果为 null
	 */
	private static Long sumCount(Long first, Long second) {
		if (first == null || second == null) {
			return null;
		}

		return first + second;
	}

	public String getProvider() {
		return provider;
	}

	public String getModel() {
		return model;
	}

	public String getId() {
		return id;
	}

	public List<GeneratedImage> getImages() {
		return images;
	}

	public String getText() {
		return text;
	}

	public Usage getUsage() {
		return usage;
	}

	public String getTraceId() {
		return traceId;
	}

	public String getRequestId() {
		return requestId;
	}

	/**
	 * 仅 includeRawResponse=true 时返回；库内循环时为各次原始响应组成的列表
	 */
	public Object getRawResponse() {
		return rawResponse;
	}

	/**
	 * 转换为契约 JSON 结构，rawResponse 仅在存在时输出
	 */
	public Map<String, Object> toContractMap() {
		Map<String, Object> contractMap = new LinkedHashMap<>();
		contractMap.put("provider", provider);
		contractMap.put("model", model);
		contractMap.put("id", id);
		List<Map<String, Object>> imageMaps = new ArrayList<>();

		for (GeneratedImage image : images) {
			imageMaps.add(image.toContractMap());
		}

		contractMap.put("images", imageMaps);
		contractMap.put("text", text);
		contractMap.put("usage", usage.toContractMap());
		contractMap.put("traceId", traceId);
		contractMap.put("requestId", requestId);

		if (rawResponse != null) {
			contractMap.put("rawResponse", rawResponse);
		}

		return contractMap;
	}

	@Override
	public String toString() {
		return "ImageResponse{provider=" + provider + ", model=" + model + ", id=" + id + ", imageCount=" + images.size() + ", usage=" + usage + "}";
	}
}
