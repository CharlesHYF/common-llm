package io.github.charleshyf.commonllm.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 消息中的单个内容片段
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class ContentPart {

	private final ContentPartType type;
	private final String text;
	private final String url;
	private final String base64;
	private final String mimeType;
	private final String id;
	private final String name;
	private final String arguments;
	private final String toolCallId;
	private final String content;
	private final boolean isError;

	private ContentPart(ContentPartType type, String text, String url, String base64, String mimeType, String id, String name, String arguments, String toolCallId, String content, boolean isError) {
		this.type = type;
		this.text = text;
		this.url = url;
		this.base64 = base64;
		this.mimeType = mimeType;
		this.id = id;
		this.name = name;
		this.arguments = arguments;
		this.toolCallId = toolCallId;
		this.content = content;
		this.isError = isError;
	}

	public static ContentPart text(String text) {
		return new ContentPart(ContentPartType.TEXT, text, null, null, null, null, null, null, null, null, false);
	}

	public static ContentPart imageUrl(String url) {
		return new ContentPart(ContentPartType.IMAGE, null, url, null, null, null, null, null, null, null, false);
	}

	public static ContentPart imageBase64(String base64, String mimeType) {
		return new ContentPart(ContentPartType.IMAGE, null, null, base64, mimeType, null, null, null, null, null, false);
	}

	/**
	 * 组装工具调用片段，arguments 为 JSON 字符串
	 */
	public static ContentPart toolCall(String id, String name, String arguments) {
		return new ContentPart(ContentPartType.TOOL_CALL, null, null, null, null, id, name, arguments, null, null, false);
	}

	public static ContentPart toolResult(String toolCallId, String content, boolean isError) {
		return new ContentPart(ContentPartType.TOOL_RESULT, null, null, null, null, null, null, null, toolCallId, content, isError);
	}

	public ContentPartType getType() {
		return type;
	}

	public String getText() {
		return text;
	}

	public String getUrl() {
		return url;
	}

	public String getBase64() {
		return base64;
	}

	public String getMimeType() {
		return mimeType;
	}

	public String getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getArguments() {
		return arguments;
	}

	public String getToolCallId() {
		return toolCallId;
	}

	public String getContent() {
		return content;
	}

	public boolean isError() {
		return isError;
	}

	/**
	 * 转换为契约 JSON 结构，只输出该类型定义的字段
	 */
	public Map<String, Object> toContractMap() {
		Map<String, Object> contractMap = new LinkedHashMap<>();
		contractMap.put("type", type.getValue());

		if (type == ContentPartType.TEXT) {
			contractMap.put("text", text);
		} else if (type == ContentPartType.IMAGE) {
			putIfPresent(contractMap, "url", url);
			putIfPresent(contractMap, "base64", base64);
			putIfPresent(contractMap, "mimeType", mimeType);
		} else if (type == ContentPartType.TOOL_CALL) {
			contractMap.put("id", id);
			contractMap.put("name", name);
			contractMap.put("arguments", arguments);
		} else {
			contractMap.put("toolCallId", toolCallId);
			contractMap.put("content", content);
			contractMap.put("isError", isError);
		}

		return contractMap;
	}

	private static void putIfPresent(Map<String, Object> contractMap, String key, String value) {
		if (value != null) {
			contractMap.put(key, value);
		}
	}
}
