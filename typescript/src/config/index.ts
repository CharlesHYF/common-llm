/**
 * 导出配置加载、合并、校验与 ConfigProvider 相关的公开 API
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

export { FileConfigProvider } from "./config-provider.js";
export { applyConfigDefaults } from "./defaults.js";
export { substituteEnvironmentVariables } from "./environment.js";
export { mergeConfigInputs } from "./merge.js";
export { CONFIG_PATH_ENVIRONMENT_NAME, ConfigResolver, DEFAULT_CONFIG_FILE_NAME } from "./resolver.js";
export { validateConfigInput, validateResolvedConfig } from "./validation.js";

export type { ConfigProvider } from "./config-provider.js";
export type { MissingVariables } from "./environment.js";
export type { ConfigResolverOptions } from "./resolver.js";
export type {
	AgentConfig,
	AgentConfigInput,
	AgentsConfig,
	AgentsConfigInput,
	AuthMode,
	CommonConfig,
	CommonConfigInput,
	CommonLlmConfig,
	CommonLlmConfigInput,
	OpenAiApi,
	OpenAiImageApi,
	ProviderConfig,
	ProviderConfigInput,
	ProviderType,
} from "./types.js";
