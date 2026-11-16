/**
 * 按优先级加载并合并全部配置来源，校验后得到完整配置
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

import { access } from "node:fs/promises";
import path from "node:path";

import { createError, isCommonLlmError } from "../core/errors.js";
import { attachRedactedSerialization } from "../core/redaction.js";
import { FileConfigProvider } from "./config-provider.js";
import { applyConfigDefaults } from "./defaults.js";
import { mergeConfigInputs } from "./merge.js";
import { validateConfigInput, validateResolvedConfig } from "./validation.js";

import type { ConfigProvider } from "./config-provider.js";
import type { MissingVariables } from "./environment.js";
import type { CommonLlmConfig, CommonLlmConfigInput } from "./types.js";

export interface ConfigResolverOptions {
	configPath?: string;
	configProvider?: ConfigProvider;
	overrides?: CommonLlmConfigInput;
	environment?: NodeJS.ProcessEnv;
	workingDirectory?: string;
}

export const CONFIG_PATH_ENVIRONMENT_NAME = "COMMON_LLM_CONFIG";
export const DEFAULT_CONFIG_FILE_NAME = "common-llm.yaml";

/**
 * 合并顺序：默认值 < 配置文件 < ConfigProvider < 运行时覆盖
 */
export class ConfigResolver {
	constructor(private readonly options: ConfigResolverOptions = {}) {}

	async resolve(): Promise<CommonLlmConfig> {
		const environment = this.options.environment ?? process.env;
		const sources: CommonLlmConfigInput[] = [];
		let missing: MissingVariables = new Map();
		const configPath = await this.resolveConfigPath(environment);

		if (configPath !== null) {
			const fileProvider = new FileConfigProvider(configPath, environment);
			sources.push(await fileProvider.load());
			missing = fileProvider.missingVariables;
		}

		if (this.options.configProvider) {
			sources.push(await loadFromProvider(this.options.configProvider));
		}

		if (this.options.overrides) {
			sources.push(validateConfigInput(this.options.overrides, "运行时覆盖"));
		}

		if (sources.length === 0) {
			throw createError("CONFIG_INVALID", `未找到配置：请传入 configPath、设置 ${CONFIG_PATH_ENVIRONMENT_NAME}、放置 ${DEFAULT_CONFIG_FILE_NAME} 或注入 ConfigProvider`);
		}

		const config = applyConfigDefaults(mergeConfigInputs(sources));
		validateResolvedConfig(config, missing);

		return redactConfigSerialization(config);
	}

	/**
	 * 配置文件路径：显式传入 > 环境变量 COMMON_LLM_CONFIG > 当前目录 common-llm.yaml
	 */
	private async resolveConfigPath(environment: NodeJS.ProcessEnv): Promise<string | null> {
		const workingDirectory = this.options.workingDirectory ?? process.cwd();
		const explicitPath = this.options.configPath ?? environment[CONFIG_PATH_ENVIRONMENT_NAME];

		if (explicitPath) {
			return path.resolve(workingDirectory, explicitPath);
		}

		const defaultPath = path.join(workingDirectory, DEFAULT_CONFIG_FILE_NAME);

		try {
			await access(defaultPath);
			return defaultPath;
		} catch {
			return null;
		}
	}
}

async function loadFromProvider(provider: ConfigProvider): Promise<CommonLlmConfigInput> {
	try {
		return validateConfigInput(await provider.load(), "ConfigProvider");
	} catch (error) {
		if (isCommonLlmError(error)) {
			throw error;
		}

		throw createError("CONFIG_INVALID", "ConfigProvider 加载配置失败", {
			cause: error,
		});
	}
}

/**
 * 让配置对象及其 Provider / Agent 子对象在序列化时输出脱敏副本
 */
export function redactConfigSerialization(config: CommonLlmConfig): CommonLlmConfig {
	for (const provider of Object.values(config.providers)) {
		attachRedactedSerialization(provider);
	}

	attachRedactedSerialization(config.agents.claude_code);
	attachRedactedSerialization(config.agents.codex);
	attachRedactedSerialization(config.agents);
	attachRedactedSerialization(config.providers);

	return attachRedactedSerialization(config);
}
