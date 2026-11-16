package io.github.charleshyf.commonllm.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Coding Agent 的运行时探测结果与能力描述
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class AgentCapability {

	private final String agent;
	private final String implementation;
	private final String executablePath;
	private final String version;
	private final List<String> features;
	private final List<String> eventTypes;
	private final List<String> supportedOptions;

	public AgentCapability(String agent, String implementation, String executablePath, String version, List<String> features, List<String> eventTypes, List<String> supportedOptions) {
		this.agent = agent;
		this.implementation = implementation;
		this.executablePath = executablePath;
		this.version = version;
		this.features = Collections.unmodifiableList(new ArrayList<>(features));
		this.eventTypes = Collections.unmodifiableList(new ArrayList<>(eventTypes));
		this.supportedOptions = supportedOptions == null ? null : Collections.unmodifiableList(new ArrayList<>(supportedOptions));
	}

	public String getAgent() {
		return agent;
	}

	public String getImplementation() {
		return implementation;
	}

	public String getExecutablePath() {
		return executablePath;
	}

	public String getVersion() {
		return version;
	}

	public List<String> getFeatures() {
		return features;
	}

	public List<String> getEventTypes() {
		return eventTypes;
	}

	public List<String> getSupportedOptions() {
		return supportedOptions;
	}

	public boolean supports(String feature) {
		return features.contains(feature);
	}

	public Map<String, Object> toContractMap() {
		Map<String, Object> contractMap = new LinkedHashMap<>();
		contractMap.put("agent", agent);
		contractMap.put("implementation", implementation);
		contractMap.put("executablePath", executablePath);
		contractMap.put("version", version);
		contractMap.put("features", features);
		contractMap.put("eventTypes", eventTypes);
		contractMap.put("supportedOptions", supportedOptions);
		return contractMap;
	}

	@Override
	public String toString() {
		return "AgentCapability" + toContractMap();
	}
}
