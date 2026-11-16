/**
 * 校验配置来源的字段结构与取值，以及合并后配置的必填项与认证条件
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

import { createError } from "../core/errors.js";

import type { MissingVariables } from "./environment.js";
import type {
	AgentsConfigInput,
	CommonConfigInput,
	CommonLlmConfig,
	CommonLlmConfigInput,
	ProviderConfigInput,
} from "./types.js";

type FieldKind =
	| "positive_number"
	| "non_negative_number"
	| "non_negative_integer"
	| "positive_integer"
	| "boolean"
	| "string"
	| "string_list"
	| "object"
	| "enum";

interface FieldRule {
	kind: FieldKind;
	values?: readonly string[];
}

const PROVIDER_TYPES: readonly string[] = [
	"openai",

	"anthropic",

	"gemini",
];

const COMMON_RULES: Record<string, FieldRule> = {
	timeout_seconds: {
		kind: "positive_number",
	},
	connect_timeout_seconds: {
		kind: "positive_number",
	},
	max_retries: {
		kind: "non_negative_integer",
	},
	log_level: {
		kind: "enum",
		values: [
			"DEBUG",

			"INFO",

			"WARN",

			"ERROR",
		],
	},
};

const PROVIDER_RULES: Record<string, FieldRule> = {
	type: {
		kind: "enum",
		values: PROVIDER_TYPES,
	},
	api: {
		kind: "enum",
		values: [
			"responses",

			"chat_completions",
		],
	},
	enabled: {
		kind: "boolean",
	},
	api_key: {
		kind: "string",
	},
	base_url: {
		kind: "string",
	},
	default_model: {
		kind: "string",
	},
	timeout_seconds: {
		kind: "positive_number",
	},
	max_retries: {
		kind: "non_negative_integer",
	},
	native_options: {
		kind: "object",
	},
};

const AGENT_RULES: Record<string, FieldRule> = {
	enabled: {
		kind: "boolean",
	},
	auth_mode: {
		kind: "enum",
		values: [
			"api_key",

			"cli_existing_session",

			"vendor_supported",
		],
	},
	api_key: {
		kind: "string",
	},
	executable_path: {
		kind: "string",
	},
	home_directory: {
		kind: "string",
	},
	working_directory: {
		kind: "string",
	},
	timeout_seconds: {
		kind: "positive_number",
	},
	permission_mode: {
		kind: "string",
	},
	allowed_tools: {
		kind: "string_list",
	},
	sandbox_mode: {
		kind: "enum",
		values: [
			"read-only",

			"workspace-write",

			"danger-full-access",
		],
	},
	env_allowlist: {
		kind: "string_list",
	},
	max_concurrent_runs: {
		kind: "positive_integer",
	},
	max_output_bytes: {
		kind: "positive_integer",
	},
	max_line_bytes: {
		kind: "positive_integer",
	},
	max_stderr_bytes: {
		kind: "non_negative_integer",
	},
	max_buffered_events: {
		kind: "positive_integer",
	},
	max_tool_output_chars: {
		kind: "non_negative_integer",
	},
	kill_grace_seconds: {
		kind: "non_negative_number",
	},
	collect_events: {
		kind: "boolean",
	},
	native_options: {
		kind: "object",
	},
};

const AGENT_NAMES: readonly string[] = [
	"claude_code",

	"codex",
];

/**
 * 校验单个配置来源的结构，返回去掉 null 值并完成数值与布尔字符串转换的副本
 */
export function validateConfigInput(raw: unknown, source: string): CommonLlmConfigInput {
	if (raw === null || raw === undefined) {
		return {};
	}

	const root = requireObject(raw, source, "");
	const result: CommonLlmConfigInput = {};

	for (const [key, value] of Object.entries(root)) {
		if (value === null || value === undefined) {
			continue;
		}

		if (key === "common") {
			result.common = validateSection(value, COMMON_RULES, source, "common") as CommonConfigInput;
			continue;
		}

		if (key === "providers") {
			result.providers = validateProviders(value, source) as Record<string, ProviderConfigInput>;
			continue;
		}

		if (key === "agents") {
			result.agents = validateAgents(value, source) as AgentsConfigInput;
			continue;
		}

		throw invalidField(source, key, "未知的配置项");
	}

	return result;
}

function validateProviders(value: unknown, source: string): Record<string, Record<string, unknown>> {
	const providers = requireObject(value, source, "providers");
	const result: Record<string, Record<string, unknown>> = {};

	for (const [instanceName, providerValue] of Object.entries(providers)) {
		if (providerValue === null || providerValue === undefined) {
			continue;
		}

		result[instanceName] = validateSection(providerValue, PROVIDER_RULES, source, `providers.${instanceName}`);
	}

	return result;
}

function validateAgents(value: unknown, source: string): Record<string, Record<string, unknown>> {
	const agents = requireObject(value, source, "agents");
	const result: Record<string, Record<string, unknown>> = {};

	for (const [agentName, agentValue] of Object.entries(agents)) {
		if (!AGENT_NAMES.includes(agentName)) {
			throw invalidField(source, `agents.${agentName}`, "未知的 Agent，只支持 claude_code 与 codex");
		}

		if (agentValue === null || agentValue === undefined) {
			continue;
		}

		result[agentName] = validateSection(agentValue, AGENT_RULES, source, `agents.${agentName}`);
	}

	return result;
}

function validateSection(value: unknown, rules: Record<string, FieldRule>, source: string, sectionPath: string): Record<string, unknown> {
	const section = requireObject(value, source, sectionPath);
	const result: Record<string, unknown> = {};

	for (const [key, fieldValue] of Object.entries(section)) {
		const fieldPath = `${sectionPath}.${key}`;
		const rule = rules[key];

		if (!rule) {
			throw invalidField(source, fieldPath, "未知的配置项");
		}

		if (fieldValue === null || fieldValue === undefined) {
			continue;
		}

		result[key] = convertField(fieldValue, rule, source, fieldPath);
	}

	return result;
}

function convertField(value: unknown, rule: FieldRule, source: string, fieldPath: string): unknown {
	if (rule.kind === "string") {
		if (typeof value !== "string") {
			throw invalidField(source, fieldPath, "必须是字符串");
		}

		return value;
	}

	if (rule.kind === "enum") {
		if (typeof value !== "string" || !(rule.values ?? []).includes(value)) {
			throw invalidField(source, fieldPath, `取值必须是 ${(rule.values ?? []).join(" / ")}`);
		}

		return value;
	}

	if (rule.kind === "boolean") {
		return convertBoolean(value, source, fieldPath);
	}

	if (rule.kind === "string_list") {
		return convertStringList(value, source, fieldPath);
	}

	if (rule.kind === "object") {
		return {
			...requireObject(value, source, fieldPath),
		};
	}

	return convertNumber(value, rule.kind, source, fieldPath);
}

function convertBoolean(value: unknown, source: string, fieldPath: string): boolean {
	if (typeof value === "boolean") {
		return value;
	}

	if (value === "true") {
		return true;
	}

	if (value === "false") {
		return false;
	}

	throw invalidField(source, fieldPath, "必须是布尔值");
}

function convertStringList(value: unknown, source: string, fieldPath: string): string[] {
	if (!Array.isArray(value)) {
		throw invalidField(source, fieldPath, "必须是字符串数组");
	}

	const items: string[] = [];

	for (const element of value) {
		if (typeof element !== "string") {
			throw invalidField(source, fieldPath, "必须是字符串数组");
		}

		items.push(element);
	}

	return items;
}

function convertNumber(value: unknown, kind: FieldKind, source: string, fieldPath: string): number {
	let numeric = Number.NaN;

	if (typeof value === "number") {
		numeric = value;
	} else if (typeof value === "string" && value.trim() !== "") {
		numeric = Number(value);
	}

	if (!Number.isFinite(numeric)) {
		throw invalidField(source, fieldPath, "必须是数字");
	}

	const isInteger = Number.isInteger(numeric);

	if (kind === "positive_number" && numeric <= 0) {
		throw invalidField(source, fieldPath, "必须大于 0");
	}

	if (kind === "non_negative_number" && numeric < 0) {
		throw invalidField(source, fieldPath, "不能小于 0");
	}

	if (kind === "non_negative_integer" && (!isInteger || numeric < 0)) {
		throw invalidField(source, fieldPath, "必须是不小于 0 的整数");
	}

	if (kind === "positive_integer" && (!isInteger || numeric < 1)) {
		throw invalidField(source, fieldPath, "必须是不小于 1 的整数");
	}

	return numeric;
}

function requireObject(value: unknown, source: string, fieldPath: string): Record<string, unknown> {
	if (value === null || typeof value !== "object" || Array.isArray(value)) {
		throw invalidField(source, fieldPath === "" ? "<root>" : fieldPath, "必须是对象");
	}

	return value as Record<string, unknown>;
}

function invalidField(source: string, fieldPath: string, reason: string): Error {
	return createError("CONFIG_INVALID", `配置 ${source} 的 ${fieldPath} 无效：${reason}`);
}

/**
 * 校验合并后的配置：Provider 类型可推断、启用项的密钥与 Codex home_directory 等必填条件
 */
export function validateResolvedConfig(config: CommonLlmConfig, missing: MissingVariables): void {
	for (const [instanceName, provider] of Object.entries(config.providers)) {
		if (!PROVIDER_TYPES.includes(provider.type)) {
			throw createError("CONFIG_INVALID", `providers.${instanceName} 需要指定 type（openai / anthropic / gemini）`, {
				provider: instanceName,
			});
		}

		if (provider.type !== "openai" && provider.api !== "responses") {
			throw createError("CONFIG_INVALID", `providers.${instanceName}.api 只适用于 type=openai`, {
				provider: instanceName,
			});
		}

		if (provider.enabled && !provider.api_key) {
			throw createError("AUTH_MISSING", describeMissingSecret(`providers.${instanceName}.api_key`, missing), {
				provider: instanceName,
			});
		}
	}

	for (const agentName of AGENT_NAMES) {
		const agent = config.agents[agentName as keyof CommonLlmConfig["agents"]];

		if (!agent.enabled) {
			continue;
		}

		if (agent.auth_mode === "api_key" && !agent.api_key) {
			throw createError("AUTH_MISSING", describeMissingSecret(`agents.${agentName}.api_key`, missing), {
				provider: agentName,
			});
		}

		if (agentName === "codex" && agent.auth_mode === "api_key" && !agent.home_directory) {
			throw createError("CONFIG_INVALID", "agents.codex 使用 auth_mode=api_key 时必须配置 home_directory，避免改写用户登录状态", {
				provider: agentName,
			});
		}
	}
}

function describeMissingSecret(fieldPath: string, missing: MissingVariables): string {
	const variableNames = missing.get(fieldPath);

	if (variableNames && variableNames.length > 0) {
		return `${fieldPath} 为空：环境变量 ${variableNames.join(", ")} 未设置`;
	}

	return `${fieldPath} 未配置`;
}
