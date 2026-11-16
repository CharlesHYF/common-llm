package io.github.charleshyf.commonllm.core;

/**
 * 按 Unicode 码点截断文本
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class TextTruncator {

	private TextTruncator() {
	}

	/**
	 * 超过 maxCodePoints 个码点时截取前 maxCodePoints 个，null 原样返回
	 */
	public static String truncateCodePoints(String text, int maxCodePoints) {
		if (text == null) {
			return null;
		}

		int codePointCount = text.codePointCount(0, text.length());

		if (codePointCount <= maxCodePoints) {
			return text;
		}

		int endIndex = text.offsetByCodePoints(0, maxCodePoints);
		return text.substring(0, endIndex);
	}
}
