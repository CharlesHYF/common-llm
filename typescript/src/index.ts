/**
 * common-llm 的包入口，导出 Facade、契约类型、配置、Provider 与 Agent 客户端
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

export { ClaudeCodeAgentClient, MIN_CLAUDE_CODE_VERSION } from "./agents/claude-code/index.js";
export { CodexAgentClient, MIN_CODEX_VERSION } from "./agents/codex/index.js";
export { CommonLlm } from "./common-llm.js";
export {
	applyConfigDefaults,
	CONFIG_PATH_ENVIRONMENT_NAME,
	ConfigResolver,
	DEFAULT_CONFIG_FILE_NAME,
	FileConfigProvider,
	mergeConfigInputs,
	substituteEnvironmentVariables,
	validateConfigInput,
	validateResolvedConfig,
} from "./config/index.js";
export {
	AGENT_FEATURES,
	classifyHttpStatus,
	CommonLlmError,
	createError,
	ERROR_CATEGORIES,
	hasFeature,
	isCommonLlmError,
	PROVIDER_FEATURES,
	redactObject,
	redactText,
	REDACTED_VALUE,
} from "./core/index.js";
export {
	createConsoleLogger,
	createLeveledLogger,
	SILENT_LOGGER,
	UUID_ID_GENERATOR,
} from "./observability/index.js";
export { AnthropicModelClient } from "./providers/anthropic/index.js";
export { GeminiModelClient } from "./providers/gemini/index.js";
export { OpenAiModelClient } from "./providers/openai/index.js";
export { VERSION } from "./version.js";

export type { CommonLlmOptions } from "./common-llm.js";
export type * from "./config/index.js";
export type * from "./core/index.js";
export type * from "./observability/index.js";
export type { AgentClientOptions, ProviderClientOptions } from "./core/clients.js";
