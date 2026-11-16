/**
 * 定义配置文件结构、合并输入与解析后的完整配置类型
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

import type { SandboxMode } from "../core/types.js";
import type { LogLevel } from "../observability/logger.js";

export type ProviderType = "openai" | "anthropic" | "gemini";

export type OpenAiApi = "responses" | "chat_completions";

export type AuthMode = "api_key" | "cli_existing_session" | "vendor_supported";

export interface CommonConfig {
	timeout_seconds: number;
	connect_timeout_seconds: number;
	max_retries: number;
	log_level: LogLevel;
}

export interface ProviderConfig {
	type: ProviderType;
	api: OpenAiApi;
	enabled: boolean;
	api_key?: string;
	base_url?: string;
	default_model?: string;
	timeout_seconds: number;
	max_retries: number;
	native_options: Record<string, unknown>;
}

export interface AgentConfig {
	enabled: boolean;
	auth_mode: AuthMode;
	api_key?: string;
	executable_path: string;
	home_directory?: string;
	working_directory?: string;
	timeout_seconds: number;
	permission_mode: string;
	allowed_tools: string[];
	sandbox_mode: SandboxMode;
	env_allowlist: string[];
	max_concurrent_runs: number;
	max_output_bytes: number;
	max_line_bytes: number;
	max_stderr_bytes: number;
	max_buffered_events: number;
	max_tool_output_chars: number;
	kill_grace_seconds: number;
	collect_events: boolean;
	native_options: Record<string, unknown>;
}

export interface AgentsConfig {
	claude_code: AgentConfig;
	codex: AgentConfig;
}

export interface CommonLlmConfig {
	common: CommonConfig;
	providers: Record<string, ProviderConfig>;
	agents: AgentsConfig;
}

export type CommonConfigInput = Partial<CommonConfig>;

export type ProviderConfigInput = Partial<ProviderConfig>;

export type AgentConfigInput = Partial<AgentConfig>;

export interface AgentsConfigInput {
	claude_code?: AgentConfigInput;
	codex?: AgentConfigInput;
}

export interface CommonLlmConfigInput {
	common?: CommonConfigInput;
	providers?: Record<string, ProviderConfigInput>;
	agents?: AgentsConfigInput;
}
