package io.github.charleshyf.commonllm.core;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 图片流式生成产生的单个事件
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class ImageEvent {

	private final ImageEventType type;
	private final String traceId;
	private final long sequence;
	private final String provider;
	private final String model;
	private final String responseId;
	private final Integer imageIndex;
	private final Integer partialIndex;
	private final byte[] partialData;
	private final String mimeType;
	private final GeneratedImage image;
	private final Usage usage;
	private final ImageResponse response;
	private final ErrorInfo error;

	ImageEvent(ImageEventType type, String traceId, long sequence, String provider, Map<String, Object> fields) {
		this.type = type;
		this.traceId = traceId;
		this.sequence = sequence;
		this.provider = provider;
		this.model = (String) fields.get("model");
		this.responseId = (String) fields.get("id");
		this.imageIndex = (Integer) fields.get("imageIndex");
		this.partialIndex = (Integer) fields.get("partialIndex");
		this.partialData = (byte[]) fields.get("data");
		this.mimeType = (String) fields.get("mimeType");
		this.image = (GeneratedImage) fields.get("image");
		this.usage = (Usage) fields.get("usage");
		this.response = (ImageResponse) fields.get("response");
		this.error = (ErrorInfo) fields.get("error");
	}

	public ImageEventType getType() {
		return type;
	}

	public String getTraceId() {
		return traceId;
	}

	public long getSequence() {
		return sequence;
	}

	public String getProvider() {
		return provider;
	}

	public String getModel() {
		return model;
	}

	public String getResponseId() {
		return responseId;
	}

	public Integer getImageIndex() {
		return imageIndex;
	}

	public Integer getPartialIndex() {
		return partialIndex;
	}

	/**
	 * partial_image 事件的部分图片字节
	 */
	public byte[] getPartialData() {
		return partialData == null ? null : partialData.clone();
	}

	public String getMimeType() {
		return mimeType;
	}

	public GeneratedImage getImage() {
		return image;
	}

	public Usage getUsage() {
		return usage;
	}

	public ImageResponse getResponse() {
		return response;
	}

	public ErrorInfo getError() {
		return error;
	}

	/**
	 * 转换为契约 JSON 结构，按事件类型输出附加字段
	 */
	public Map<String, Object> toContractMap() {
		Map<String, Object> contractMap = new LinkedHashMap<>();
		contractMap.put("type", type.getValue());
		contractMap.put("traceId", traceId);
		contractMap.put("sequence", sequence);
		contractMap.put("provider", provider);

		if (type == ImageEventType.STARTED) {
			contractMap.put("model", model);
			contractMap.put("id", responseId);
		} else if (type == ImageEventType.PARTIAL_IMAGE) {
			contractMap.put("imageIndex", imageIndex);
			contractMap.put("partialIndex", partialIndex);
			contractMap.put("data", Base64.getEncoder().encodeToString(partialData));
			contractMap.put("mimeType", mimeType);
		} else if (type == ImageEventType.IMAGE_COMPLETED) {
			contractMap.put("imageIndex", imageIndex);
			contractMap.put("image", image.toContractMap());
		} else if (type == ImageEventType.USAGE) {
			contractMap.put("usage", usage.toContractMap());
		} else if (type == ImageEventType.COMPLETED) {
			contractMap.put("response", response.toContractMap());
		} else {
			contractMap.put("error", error.toContractMap());
		}

		return contractMap;
	}

	@Override
	public String toString() {
		return "ImageEvent{type=" + type.getValue() + ", traceId=" + traceId + ", sequence=" + sequence + ", imageIndex=" + imageIndex + "}";
	}
}
