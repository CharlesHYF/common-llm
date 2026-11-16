package io.github.charleshyf.commonllm.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型调用的 token 用量，无法提供的值为 null
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class Usage {

	private final Long inputTokens;
	private final Long outputTokens;
	private final Long cachedInputTokens;
	private final Long reasoningTokens;
	private final Long totalTokens;

	public Usage(Long inputTokens, Long outputTokens, Long cachedInputTokens, Long reasoningTokens, Long totalTokens) {
		this.inputTokens = inputTokens;
		this.outputTokens = outputTokens;
		this.cachedInputTokens = cachedInputTokens;
		this.reasoningTokens = reasoningTokens;
		this.totalTokens = totalTokens;
	}

	public static Usage empty() {
		return new Usage(null, null, null, null, null);
	}

	public Long getInputTokens() {
		return inputTokens;
	}

	public Long getOutputTokens() {
		return outputTokens;
	}

	public Long getCachedInputTokens() {
		return cachedInputTokens;
	}

	public Long getReasoningTokens() {
		return reasoningTokens;
	}

	public Long getTotalTokens() {
		return totalTokens;
	}

	public Map<String, Object> toContractMap() {
		Map<String, Object> contractMap = new LinkedHashMap<>();
		contractMap.put("inputTokens", inputTokens);
		contractMap.put("outputTokens", outputTokens);
		contractMap.put("cachedInputTokens", cachedInputTokens);
		contractMap.put("reasoningTokens", reasoningTokens);
		contractMap.put("totalTokens", totalTokens);
		return contractMap;
	}

	@Override
	public String toString() {
		return "Usage" + toContractMap();
	}
}
