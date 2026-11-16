package io.github.charleshyf.commonllm.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 单次运行的用量与成本，无法提供的值为 null
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class AgentUsage {

	private final Long inputTokens;
	private final Long outputTokens;
	private final Long cachedInputTokens;
	private final Long reasoningTokens;
	private final Long totalTokens;
	private final Double costUsd;
	private final Long numTurns;

	public AgentUsage(Long inputTokens, Long outputTokens, Long cachedInputTokens, Long reasoningTokens, Long totalTokens, Double costUsd, Long numTurns) {
		this.inputTokens = inputTokens;
		this.outputTokens = outputTokens;
		this.cachedInputTokens = cachedInputTokens;
		this.reasoningTokens = reasoningTokens;
		this.totalTokens = totalTokens;
		this.costUsd = costUsd;
		this.numTurns = numTurns;
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

	public Double getCostUsd() {
		return costUsd;
	}

	public Long getNumTurns() {
		return numTurns;
	}

	public Map<String, Object> toContractMap() {
		Map<String, Object> contractMap = new LinkedHashMap<>();
		contractMap.put("inputTokens", inputTokens);
		contractMap.put("outputTokens", outputTokens);
		contractMap.put("cachedInputTokens", cachedInputTokens);
		contractMap.put("reasoningTokens", reasoningTokens);
		contractMap.put("totalTokens", totalTokens);
		contractMap.put("costUsd", costUsd);
		contractMap.put("numTurns", numTurns);
		return contractMap;
	}

	@Override
	public String toString() {
		return "AgentUsage" + toContractMap();
	}
}
