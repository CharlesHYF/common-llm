/**
 * 定义 Provider 与 Agent 能力枚举及能力查询函数
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

import type { AgentFeature, ProviderFeature } from "./types.js";

export const PROVIDER_FEATURES: readonly ProviderFeature[] = [
	"streaming",

	"tool_calls",

	"structured_output",

	"vision",

	"reasoning",
];

export const AGENT_FEATURES: readonly AgentFeature[] = [
	"streaming",

	"session_resume",

	"approvals",

	"interrupt",

	"cost",
];

/**
 * 判断能力集合是否包含指定 feature，未知 feature 一律返回 false
 */
export function hasFeature(features: readonly string[], feature: string): boolean {
	return features.includes(feature);
}
