/**
 * 定义 ConfigProvider 接口与读取 YAML / JSON 文件的 FileConfigProvider
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

import { readFile } from "node:fs/promises";
import path from "node:path";
import { parse as parseYaml } from "yaml";

import { createError } from "../core/errors.js";
import { substituteEnvironmentVariables } from "./environment.js";
import { validateConfigInput } from "./validation.js";

import type { MissingVariables } from "./environment.js";
import type { CommonLlmConfigInput } from "./types.js";

export interface ConfigProvider {
	load(): CommonLlmConfigInput | Promise<CommonLlmConfigInput>;
}

/**
 * 读取本地配置文件，按扩展名识别格式并替换 ${ENV} 引用
 */
export class FileConfigProvider implements ConfigProvider {
	private lastMissingVariables: MissingVariables = new Map();

	constructor(
		readonly filePath: string,
		private readonly environment: NodeJS.ProcessEnv = process.env,
	) {}

	get missingVariables(): MissingVariables {
		return this.lastMissingVariables;
	}

	async load(): Promise<CommonLlmConfigInput> {
		let content: string;

		try {
			content = await readFile(this.filePath, "utf8");
		} catch (error) {
			throw createError("CONFIG_INVALID", `无法读取配置文件：${this.filePath}`, {
				cause: error,
			});
		}

		const parsed = parseConfigText(content, this.filePath);
		const missing: MissingVariables = new Map();
		const substituted = substituteEnvironmentVariables(parsed, this.environment, "", missing);
		const validated = validateConfigInput(substituted, this.filePath);
		this.lastMissingVariables = missing;

		return validated;
	}
}

function parseConfigText(content: string, filePath: string): unknown {
	const extension = path.extname(filePath).toLowerCase();

	try {
		if (extension === ".json") {
			return JSON.parse(content);
		}

		if (extension === ".yaml" || extension === ".yml") {
			return parseYaml(content);
		}
	} catch (error) {
		throw createError("CONFIG_INVALID", `配置文件格式错误：${filePath}`, {
			cause: error,
		});
	}

	throw createError("CONFIG_INVALID", `不支持的配置文件扩展名：${extension}，只支持 .yaml / .yml / .json`);
}
