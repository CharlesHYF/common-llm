package io.github.charleshyf.commonllm.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型响应中的一次工具调用
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class ToolCall {

	private final String id;
	private final String name;
	private final String arguments;

	public ToolCall(String id, String name, String arguments) {
		this.id = id;
		this.name = name;
		this.arguments = arguments;
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

	public Map<String, Object> toContractMap() {
		Map<String, Object> contractMap = new LinkedHashMap<>();
		contractMap.put("id", id);
		contractMap.put("name", name);
		contractMap.put("arguments", arguments);
		return contractMap;
	}
}
