package io.github.charleshyf.commonllm.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 图片生成请求中的输入图片，取值为 base64 加 MIME 类型或 URL
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class ImageInput {

	private final String base64;
	private final String mimeType;
	private final String url;

	private ImageInput(String base64, String mimeType, String url) {
		this.base64 = base64;
		this.mimeType = mimeType;
		this.url = url;
	}

	public static ImageInput fromBase64(String base64, String mimeType) {
		return new ImageInput(base64, mimeType, null);
	}

	public static ImageInput fromBytes(byte[] data, String mimeType) {
		return new ImageInput(Base64.getEncoder().encodeToString(data), mimeType, null);
	}

	/**
	 * 读取图片文件，MIME 类型按扩展名推断，无法识别时报 CONFIG_INVALID
	 */
	public static ImageInput fromFile(Path path) {
		String mimeType = guessMimeType(path.getFileName().toString());

		if (mimeType == null) {
			throw CommonLlmException.of(ErrorCategory.CONFIG_INVALID, "无法从文件扩展名识别图片 MIME 类型：" + path, null);
		}

		try {
			return fromBytes(Files.readAllBytes(path), mimeType);
		} catch (IOException exception) {
			throw new UncheckedIOException(exception);
		}
	}

	/**
	 * URL 输入只有 OpenAI Responses 路径支持，本库不代为下载
	 */
	public static ImageInput fromUrl(String url) {
		return new ImageInput(null, null, url);
	}

	/**
	 * 按文件名扩展名推断图片 MIME 类型，无法识别返回 null
	 */
	public static String guessMimeType(String fileName) {
		String lowerName = fileName.toLowerCase(Locale.ROOT);

		if (lowerName.endsWith(".png")) {
			return "image/png";
		}

		if (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")) {
			return "image/jpeg";
		}

		if (lowerName.endsWith(".webp")) {
			return "image/webp";
		}

		if (lowerName.endsWith(".gif")) {
			return "image/gif";
		}

		return null;
	}

	public String getBase64() {
		return base64;
	}

	public String getMimeType() {
		return mimeType;
	}

	public String getUrl() {
		return url;
	}

	public boolean isUrl() {
		return url != null;
	}

	/**
	 * 解码 base64 得到图片字节，URL 输入返回 null
	 */
	public byte[] toBytes() {
		if (base64 == null) {
			return null;
		}

		return Base64.getDecoder().decode(base64);
	}

	/**
	 * 转换为 data URL，URL 输入原样返回
	 */
	public String toDataUrl() {
		if (url != null) {
			return url;
		}

		return "data:" + mimeType + ";base64," + base64;
	}

	public Map<String, Object> toContractMap() {
		Map<String, Object> contractMap = new LinkedHashMap<>();

		if (url != null) {
			contractMap.put("url", url);
		} else {
			contractMap.put("base64", base64);
			contractMap.put("mimeType", mimeType);
		}

		return contractMap;
	}
}
