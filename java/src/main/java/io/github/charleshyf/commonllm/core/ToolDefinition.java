package io.github.charleshyf.commonllm.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型可调用的工具定义
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class ToolDefinition {

	private final String name;
	private final String description;
	private final Map<String, Object> parameters;

	public ToolDefinition(String name, String description, Map<String, Object> parameters) {
		this.name = name;
		this.description = description;
		this.parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public Map<String, Object> getParameters() {
		return parameters;
	}
}
