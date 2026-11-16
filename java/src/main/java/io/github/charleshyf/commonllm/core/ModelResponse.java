package io.github.charleshyf.commonllm.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一模型调用响应
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class ModelResponse {

	private final String provider;
	private final String model;
	private final String id;
	private final List<ContentPart> contentParts;
	private final String text;
	private final List<ToolCall> toolCalls;
	private final FinishReason finishReason;
	private final String vendorFinishReason;
	private final Usage usage;
	private final String traceId;
	private final String requestId;
	private final Map<String, Object> rawResponse;

	/**
	 * 由内容片段推导 text 与 toolCalls，保证三者一致
	 */
	public ModelResponse(String provider, String model, String id, List<ContentPart> contentParts, FinishReason finishReason, String vendorFinishReason, Usage usage, String traceId, String requestId, Map<String, Object> rawResponse) {
		this.provider = provider;
		this.model = model;
		this.id = id;
		this.contentParts = Collections.unmodifiableList(new ArrayList<>(contentParts));
		this.finishReason = finishReason;
		this.vendorFinishReason = vendorFinishReason;
		this.usage = usage;
		this.traceId = traceId;
		this.requestId = requestId;
		this.rawResponse = rawResponse;

		StringBuilder textBuilder = new StringBuilder();
		List<ToolCall> collectedToolCalls = new ArrayList<>();

		for (ContentPart contentPart : contentParts) {
			if (contentPart.getType() == ContentPartType.TEXT) {
				textBuilder.append(contentPart.getText());
				continue;
			}

			if (contentPart.getType() == ContentPartType.TOOL_CALL) {
				collectedToolCalls.add(new ToolCall(contentPart.getId(), contentPart.getName(), contentPart.getArguments()));
			}
		}

		this.text = textBuilder.toString();
		this.toolCalls = Collections.unmodifiableList(collectedToolCalls);
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

	public List<ContentPart> getContentParts() {
		return contentParts;
	}

	public String getText() {
		return text;
	}

	public List<ToolCall> getToolCalls() {
		return toolCalls;
	}

	public FinishReason getFinishReason() {
		return finishReason;
	}

	public String getVendorFinishReason() {
		return vendorFinishReason;
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

	public Map<String, Object> getRawResponse() {
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
		List<Map<String, Object>> contentPartMaps = new ArrayList<>();

		for (ContentPart contentPart : contentParts) {
			contentPartMaps.add(contentPart.toContractMap());
		}

		contractMap.put("contentParts", contentPartMaps);
		contractMap.put("text", text);
		List<Map<String, Object>> toolCallMaps = new ArrayList<>();

		for (ToolCall toolCall : toolCalls) {
			toolCallMaps.add(toolCall.toContractMap());
		}

		contractMap.put("toolCalls", toolCallMaps);
		contractMap.put("finishReason", finishReason.getValue());
		contractMap.put("vendorFinishReason", vendorFinishReason);
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
		return "ModelResponse{provider=" + provider + ", model=" + model + ", id=" + id + ", finishReason=" + finishReason + ", usage=" + usage + "}";
	}
}
