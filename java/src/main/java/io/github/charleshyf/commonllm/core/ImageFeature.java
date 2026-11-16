package io.github.charleshyf.commonllm.core;

/**
 * 图片生成能力标识
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public enum ImageFeature {
	IMAGE_GENERATION("image_generation"),
	IMAGE_EDIT("image_edit"),
	IMAGE_MASK("image_mask"),
	IMAGE_STREAMING("image_streaming"),
	IMAGE_MULTIPLE("image_multiple");

	private final String value;

	ImageFeature(String value) {
		this.value = value;
	}

	public String getValue() {
		return value;
	}
}
