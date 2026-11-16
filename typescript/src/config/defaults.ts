/**
 * 定义配置字段默认值并把合并后的输入补全为完整配置
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

import type { LogLevel } from "../observability/logger.js";
import type {
	AgentConfig,
	AgentConfigInput,
	CommonConfig,
	CommonLlmConfig,
	CommonLlmConfigInput,
	ProviderConfig,
	ProviderConfigInput,
	ProviderType,
} from "./types.js";

export const DEFAULT_TIMEOUT_SECONDS = 120;
export const DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;
export const DEFAULT_MAX_RETRIES = 2;
export const DEFAULT_LOG_LEVEL: LogLevel = "INFO";
export const DEFAULT_AGENT_TIMEOUT_SECONDS = 600;
export const DEFAULT_PERMISSION_MODE = "default";
export const DEFAULT_MAX_CONCURRENT_RUNS = 4;
export const DEFAULT_MAX_OUTPUT_BYTES = 10 * 1024 * 1024;
export const DEFAULT_MAX_LINE_BYTES = 8 * 1024 * 1024;
export const DEFAULT_MAX_STDERR_BYTES = 64 * 1024;
export const DEFAULT_MAX_BUFFERED_EVENTS = 10000;
export const DEFAULT_MAX_TOOL_OUTPUT_CHARS = 4000;
export const DEFAULT_KILL_GRACE_SECONDS = 5;

const KNOWN_PROVIDER_TYPES: readonly ProviderType[] = [
	"openai",

	"anthropic",

	"gemini",
];

/**
 * 补全默认值：Provider 的超时与重试继承 common，实例名是厂商名时 type 可省略
 */
export function applyConfigDefaults(input: CommonLlmConfigInput): CommonLlmConfig {
	const common: CommonConfig = {
		timeout_seconds: input.common?.timeout_seconds ?? DEFAULT_TIMEOUT_SECONDS,
		connect_timeout_seconds: input.common?.connect_timeout_seconds ?? DEFAULT_CONNECT_TIMEOUT_SECONDS,
		max_retries: input.common?.max_retries ?? DEFAULT_MAX_RETRIES,
		log_level: input.common?.log_level ?? DEFAULT_LOG_LEVEL,
	};
	const providers: Record<string, ProviderConfig> = {};

	for (const [instanceName, providerInput] of Object.entries(input.providers ?? {})) {
		providers[instanceName] = applyProviderDefaults(instanceName, providerInput, common);
	}

	return {
		common,
		providers,
		agents: {
			claude_code: applyAgentDefaults(input.agents?.claude_code ?? {}, "claude"),
			codex: applyAgentDefaults(input.agents?.codex ?? {}, "codex"),
		},
	};
}

function applyProviderDefaults(instanceName: string, input: ProviderConfigInput, common: CommonConfig): ProviderConfig {
	let providerType = input.type;

	if (providerType === undefined && KNOWN_PROVIDER_TYPES.includes(instanceName as ProviderType)) {
		providerType = instanceName as ProviderType;
	}

	const provider: ProviderConfig = {
		type: providerType ?? ("" as ProviderType),
		api: input.api ?? "responses",
		enabled: input.enabled ?? false,
		timeout_seconds: input.timeout_seconds ?? common.timeout_seconds,
		max_retries: input.max_retries ?? common.max_retries,
		native_options: {
			...input.native_options,
		},
	};

	if (input.api_key !== undefined) {
		provider.api_key = input.api_key;
	}

	if (input.base_url !== undefined && input.base_url !== "") {
		provider.base_url = input.base_url;
	}

	if (input.default_model !== undefined && input.default_model !== "") {
		provider.default_model = input.default_model;
	}

	return provider;
}

function applyAgentDefaults(input: AgentConfigInput, defaultExecutable: string): AgentConfig {
	const agent: AgentConfig = {
		enabled: input.enabled ?? false,
		auth_mode: input.auth_mode ?? "cli_existing_session",
		executable_path: input.executable_path ?? defaultExecutable,
		timeout_seconds: input.timeout_seconds ?? DEFAULT_AGENT_TIMEOUT_SECONDS,
		permission_mode: input.permission_mode ?? DEFAULT_PERMISSION_MODE,
		allowed_tools: [
			...(input.allowed_tools ?? []),
		],
		sandbox_mode: input.sandbox_mode ?? "workspace-write",
		env_allowlist: [
			...(input.env_allowlist ?? []),
		],
		max_concurrent_runs: input.max_concurrent_runs ?? DEFAULT_MAX_CONCURRENT_RUNS,
		max_output_bytes: input.max_output_bytes ?? DEFAULT_MAX_OUTPUT_BYTES,
		max_line_bytes: input.max_line_bytes ?? DEFAULT_MAX_LINE_BYTES,
		max_stderr_bytes: input.max_stderr_bytes ?? DEFAULT_MAX_STDERR_BYTES,
		max_buffered_events: input.max_buffered_events ?? DEFAULT_MAX_BUFFERED_EVENTS,
		max_tool_output_chars: input.max_tool_output_chars ?? DEFAULT_MAX_TOOL_OUTPUT_CHARS,
		kill_grace_seconds: input.kill_grace_seconds ?? DEFAULT_KILL_GRACE_SECONDS,
		collect_events: input.collect_events ?? false,
		native_options: {
			...input.native_options,
		},
	};

	if (input.api_key !== undefined) {
		agent.api_key = input.api_key;
	}

	if (input.home_directory !== undefined && input.home_directory !== "") {
		agent.home_directory = input.home_directory;
	}

	if (input.working_directory !== undefined && input.working_directory !== "") {
		agent.working_directory = input.working_directory;
	}

	return agent;
}
