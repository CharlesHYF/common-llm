package io.github.charleshyf.commonllm.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 合并、校验后的完整配置
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class CommonLlmConfig {

	private final CommonConfig common;
	private final Map<String, ProviderConfig> providers;
	private final Map<String, AgentConfig> agents;

	public CommonLlmConfig(CommonConfig common, Map<String, ProviderConfig> providers, Map<String, AgentConfig> agents) {
		this.common = common;
		this.providers = Collections.unmodifiableMap(new LinkedHashMap<>(providers));
		this.agents = Collections.unmodifiableMap(new LinkedHashMap<>(agents));
	}

	public CommonConfig getCommon() {
		return common;
	}

	/**
	 * Provider 实例名到配置的映射，保持配置中的顺序
	 */
	public Map<String, ProviderConfig> getProviders() {
		return providers;
	}

	public Map<String, AgentConfig> getAgents() {
		return agents;
	}

	@Override
	public String toString() {
		return "CommonLlmConfig{common=" + common + ", providers=" + providers.values() + ", agents=" + agents.values() + "}";
	}
}
