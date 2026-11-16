package io.github.charleshyf.commonllm.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一图片生成请求，有输入图片时为图生图
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class ImageRequest {

	private final String provider;
	private final String model;
	private final String prompt;
	private final List<ImageInput> images;
	private final ImageInput mask;
	private final Integer imageCount;
	private final String size;
	private final String aspectRatio;
	private final String imageSize;
	private final String quality;
	private final String outputFormat;
	private final Integer outputCompression;
	private final String background;
	private final Integer partialImages;
	private final Map<String, Object> providerOptions;
	private final Double timeoutSeconds;
	private final boolean shouldIncludeRawResponse;
	private final String traceId;
	private final String requestId;
	private final Map<String, String> metadata;

	private ImageRequest(Builder builder) {
		this.provider = builder.provider;
		this.model = builder.model;
		this.prompt = builder.prompt;
		this.images = Collections.unmodifiableList(new ArrayList<>(builder.images));
		this.mask = builder.mask;
		this.imageCount = builder.imageCount;
		this.size = builder.size;
		this.aspectRatio = builder.aspectRatio;
		this.imageSize = builder.imageSize;
		this.quality = builder.quality;
		this.outputFormat = builder.outputFormat;
		this.outputCompression = builder.outputCompression;
		this.background = builder.background;
		this.partialImages = builder.partialImages;
		this.providerOptions = Collections.unmodifiableMap(new LinkedHashMap<>(builder.providerOptions));
		this.timeoutSeconds = builder.timeoutSeconds;
		this.shouldIncludeRawResponse = builder.shouldIncludeRawResponse;
		this.traceId = builder.traceId;
		this.requestId = builder.requestId;
		this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(builder.metadata));
	}

	public static Builder builder() {
		return new Builder();
	}

	public String getProvider() {
		return provider;
	}

	public String getModel() {
		return model;
	}

	public String getPrompt() {
		return prompt;
	}

	public List<ImageInput> getImages() {
		return images;
	}

	public ImageInput getMask() {
		return mask;
	}

	/**
	 * 生成张数，对应契约字段 n，缺省为 1
	 */
	public Integer getImageCount() {
		return imageCount;
	}

	public String getSize() {
		return size;
	}

	public String getAspectRatio() {
		return aspectRatio;
	}

	public String getImageSize() {
		return imageSize;
	}

	public String getQuality() {
		return quality;
	}

	public String getOutputFormat() {
		return outputFormat;
	}

	public Integer getOutputCompression() {
		return outputCompression;
	}

	public String getBackground() {
		return background;
	}

	public Integer getPartialImages() {
		return partialImages;
	}

	public Map<String, Object> getProviderOptions() {
		return providerOptions;
	}

	public Double getTimeoutSeconds() {
		return timeoutSeconds;
	}

	public boolean shouldIncludeRawResponse() {
		return shouldIncludeRawResponse;
	}

	public String getTraceId() {
		return traceId;
	}

	public String getRequestId() {
		return requestId;
	}

	public Map<String, String> getMetadata() {
		return metadata;
	}

	/**
	 * 基于当前请求组装新的构造器
	 */
	public Builder toBuilder() {
		Builder builder = new Builder();
		builder.provider = provider;
		builder.model = model;
		builder.prompt = prompt;
		builder.images = new ArrayList<>(images);
		builder.mask = mask;
		builder.imageCount = imageCount;
		builder.size = size;
		builder.aspectRatio = aspectRatio;
		builder.imageSize = imageSize;
		builder.quality = quality;
		builder.outputFormat = outputFormat;
		builder.outputCompression = outputCompression;
		builder.background = background;
		builder.partialImages = partialImages;
		builder.providerOptions = new LinkedHashMap<>(providerOptions);
		builder.timeoutSeconds = timeoutSeconds;
		builder.shouldIncludeRawResponse = shouldIncludeRawResponse;
		builder.traceId = traceId;
		builder.requestId = requestId;
		builder.metadata = new LinkedHashMap<>(metadata);
		return builder;
	}

	@Override
	public String toString() {
		return "ImageRequest{provider=" + provider + ", model=" + model + ", imageCount=" + images.size() + ", imageCount=" + imageCount + ", traceId=" + traceId + "}";
	}

	/**
	 * ImageRequest 构造器
	 */
	public static final class Builder {

		private String provider;
		private String model;
		private String prompt;
		private List<ImageInput> images = new ArrayList<>();
		private ImageInput mask;
		private Integer imageCount;
		private String size;
		private String aspectRatio;
		private String imageSize;
		private String quality;
		private String outputFormat;
		private Integer outputCompression;
		private String background;
		private Integer partialImages;
		private Map<String, Object> providerOptions = new LinkedHashMap<>();
		private Double timeoutSeconds;
		private boolean shouldIncludeRawResponse;
		private String traceId;
		private String requestId;
		private Map<String, String> metadata = new LinkedHashMap<>();

		private Builder() {
		}

		public Builder provider(String provider) {
			this.provider = provider;
			return this;
		}

		public Builder model(String model) {
			this.model = model;
			return this;
		}

		public Builder prompt(String prompt) {
			this.prompt = prompt;
			return this;
		}

		public Builder images(List<ImageInput> images) {
			this.images = new ArrayList<>(images);
			return this;
		}

		public Builder addImage(ImageInput image) {
			this.images.add(image);
			return this;
		}

		public Builder mask(ImageInput mask) {
			this.mask = mask;
			return this;
		}

		public Builder imageCount(Integer imageCount) {
			this.imageCount = imageCount;
			return this;
		}

		public Builder size(String size) {
			this.size = size;
			return this;
		}

		public Builder aspectRatio(String aspectRatio) {
			this.aspectRatio = aspectRatio;
			return this;
		}

		public Builder imageSize(String imageSize) {
			this.imageSize = imageSize;
			return this;
		}

		public Builder quality(String quality) {
			this.quality = quality;
			return this;
		}

		public Builder outputFormat(String outputFormat) {
			this.outputFormat = outputFormat;
			return this;
		}

		public Builder outputCompression(Integer outputCompression) {
			this.outputCompression = outputCompression;
			return this;
		}

		public Builder background(String background) {
			this.background = background;
			return this;
		}

		public Builder partialImages(Integer partialImages) {
			this.partialImages = partialImages;
			return this;
		}

		public Builder providerOptions(Map<String, Object> providerOptions) {
			this.providerOptions = new LinkedHashMap<>(providerOptions);
			return this;
		}

		public Builder putProviderOption(String key, Object value) {
			this.providerOptions.put(key, value);
			return this;
		}

		public Builder timeoutSeconds(Double timeoutSeconds) {
			this.timeoutSeconds = timeoutSeconds;
			return this;
		}

		public Builder includeRawResponse(boolean shouldIncludeRawResponse) {
			this.shouldIncludeRawResponse = shouldIncludeRawResponse;
			return this;
		}

		public Builder traceId(String traceId) {
			this.traceId = traceId;
			return this;
		}

		public Builder requestId(String requestId) {
			this.requestId = requestId;
			return this;
		}

		public Builder metadata(Map<String, String> metadata) {
			this.metadata = new LinkedHashMap<>(metadata);
			return this;
		}

		public ImageRequest build() {
			return new ImageRequest(this);
		}
	}
}
