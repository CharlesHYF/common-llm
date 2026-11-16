package io.github.charleshyf.commonllm.core;

/**
 * 图片流式生成事件类型
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public enum ImageEventType {
	STARTED("started"),
	PARTIAL_IMAGE("partial_image"),
	IMAGE_COMPLETED("image_completed"),
	USAGE("usage"),
	COMPLETED("completed"),
	ERROR("error");

	private final String value;

	ImageEventType(String value) {
		this.value = value;
	}

	public String getValue() {
		return value;
	}

	public boolean isTerminal() {
		return this == COMPLETED || this == ERROR;
	}
}
