package io.github.charleshyf.commonllm.core;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 图片生成结果中的单张图片
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class GeneratedImage {

	private final byte[] data;
	private final String mimeType;
	private final String url;
	private final String revisedPrompt;

	public GeneratedImage(byte[] data, String mimeType, String url, String revisedPrompt) {
		this.data = data == null ? new byte[0] : data.clone();
		this.mimeType = mimeType;
		this.url = url;
		this.revisedPrompt = revisedPrompt;
	}

	/**
	 * 由 base64 文本组装图片，空值视为没有字节
	 */
	public static GeneratedImage fromBase64(String base64, String mimeType, String url, String revisedPrompt) {
		byte[] data = base64 == null || base64.isEmpty() ? new byte[0] : Base64.getDecoder().decode(base64);
		return new GeneratedImage(data, mimeType, url, revisedPrompt);
	}

	public byte[] getData() {
		return data.clone();
	}

	public String getMimeType() {
		return mimeType;
	}

	public String getUrl() {
		return url;
	}

	public String getRevisedPrompt() {
		return revisedPrompt;
	}

	/**
	 * 转换为契约 JSON 结构，data 为 base64 文本
	 */
	public Map<String, Object> toContractMap() {
		Map<String, Object> contractMap = new LinkedHashMap<>();
		contractMap.put("data", Base64.getEncoder().encodeToString(data));
		contractMap.put("mimeType", mimeType);
		contractMap.put("url", url);
		contractMap.put("revisedPrompt", revisedPrompt);
		return contractMap;
	}
}
