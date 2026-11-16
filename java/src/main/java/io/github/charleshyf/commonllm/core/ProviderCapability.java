package io.github.charleshyf.commonllm.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型 Provider 实例的能力描述
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class ProviderCapability {

	private final String provider;
	private final List<String> features;

	public ProviderCapability(String provider, List<String> features) {
		this.provider = provider;
		this.features = Collections.unmodifiableList(new ArrayList<>(features));
	}

	public String getProvider() {
		return provider;
	}

	public List<String> getFeatures() {
		return features;
	}

	public Map<String, Object> toContractMap() {
		Map<String, Object> contractMap = new LinkedHashMap<>();
		contractMap.put("provider", provider);
		contractMap.put("features", features);
		return contractMap;
	}

	@Override
	public String toString() {
		return "ProviderCapability" + toContractMap();
	}
}
