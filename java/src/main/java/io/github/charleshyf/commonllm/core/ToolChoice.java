package io.github.charleshyf.commonllm.core;

/**
 * 工具选择策略，TOOL 类型需指定工具名
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class ToolChoice {

	private final ToolChoiceType type;
	private final String name;

	private ToolChoice(ToolChoiceType type, String name) {
		this.type = type;
		this.name = name;
	}

	public static ToolChoice auto() {
		return new ToolChoice(ToolChoiceType.AUTO, null);
	}

	public static ToolChoice none() {
		return new ToolChoice(ToolChoiceType.NONE, null);
	}

	public static ToolChoice required() {
		return new ToolChoice(ToolChoiceType.REQUIRED, null);
	}

	public static ToolChoice tool(String name) {
		return new ToolChoice(ToolChoiceType.TOOL, name);
	}

	public ToolChoiceType getType() {
		return type;
	}

	public String getName() {
		return name;
	}
}
