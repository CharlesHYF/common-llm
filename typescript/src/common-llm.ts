/**
 * CommonLlm Facade：按配置装配 Provider 与 Agent 客户端并路由调用
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

import { ClaudeCodeAgentClient } from "./agents/claude-code/claude-code-client.js";
import { CodexAgentClient } from "./agents/codex/codex-client.js";
import { ConfigResolver } from "./config/resolver.js";
import { createError, isCommonLlmError } from "./core/errors.js";
import { createLeveledLogger, SILENT_LOGGER } from "./observability/logger.js";
import { UUID_ID_GENERATOR } from "./observability/ids.js";
import { AnthropicModelClient } from "./providers/anthropic/anthropic-client.js";
import { GeminiModelClient } from "./providers/gemini/gemini-client.js";
import { GeminiImageClient } from "./providers/gemini/gemini-image-client.js";
import { OpenAiModelClient } from "./providers/openai/openai-client.js";
import { OpenAiImageClient } from "./providers/openai/openai-image-client.js";

import type { ConfigProvider } from "./config/config-provider.js";
import type { CommonLlmConfig, CommonLlmConfigInput } from "./config/types.js";
import type { AgentClient, ImageClient, ModelClient } from "./core/clients.js";
import type {
	AgentCapability,
	AgentName,
	CallOptions,
	ImageEvent,
	ImageRequest,
	ImageResponse,
	ModelEvent,
	ModelRequest,
	ModelResponse,
	ProviderCapability,
} from "./core/types.js";
import type { CommonLlmLogger } from "./observability/logger.js";
import type { IdGenerator } from "./observability/ids.js";

export interface CommonLlmOptions {
	configPath?: string;
	configProvider?: ConfigProvider;
	overrides?: CommonLlmConfigInput;
	logger?: CommonLlmLogger;
	idGenerator?: IdGenerator;
	environment?: NodeJS.ProcessEnv;
	workingDirectory?: string;
}

/**
 * 一次配置对应的一组客户端；reload 后旧的一组在进行中的调用结束后关闭
 */
interface ClientGeneration {
	config: CommonLlmConfig;
	models: Map<string, ModelClient>;
	images: Map<string, ImageClient>;
	agents: Map<AgentName, AgentClient>;
	activeCalls: number;
	isRetired: boolean;
}

export class CommonLlm {
	private generation: ClientGeneration;

	private constructor(
		private readonly resolver: ConfigResolver,
		private readonly options: CommonLlmOptions,
		config: CommonLlmConfig,
	) {
		this.generation = this.buildGeneration(config);
	}

	/**
	 * 按配置装配客户端；未传 configProvider 与 configPath 时从默认路径加载文件配置
	 */
	static async create(options: CommonLlmOptions = {}): Promise<CommonLlm> {
		const resolver = new ConfigResolver({
			configPath: options.configPath,
			configProvider: options.configProvider,
			overrides: options.overrides,
			environment: options.environment,
			workingDirectory: options.workingDirectory,
		});
		const config = await resolver.resolve();

		return new CommonLlm(resolver, options, config);
	}

	get config(): CommonLlmConfig {
		return this.generation.config;
	}

	async generate(request: ModelRequest, options?: CallOptions): Promise<ModelResponse> {
		const generation = this.generation;
		const client = this.findModel(generation, request.provider);
		generation.activeCalls += 1;

		try {
			return await client.generate(request, options);
		} finally {
			this.finishCall(generation);
		}
	}

	async *stream(request: ModelRequest, options?: CallOptions): AsyncGenerator<ModelEvent> {
		const generation = this.generation;
		const client = this.findModel(generation, request.provider);
		generation.activeCalls += 1;

		try {
			yield* client.stream(request, options);
		} finally {
			this.finishCall(generation);
		}
	}

	async generateImage(request: ImageRequest, options?: CallOptions): Promise<ImageResponse> {
		const generation = this.generation;
		const client = this.findImage(generation, request.provider);
		generation.activeCalls += 1;

		try {
			return await client.generate(request, options);
		} finally {
			this.finishCall(generation);
		}
	}

	async *streamImage(request: ImageRequest, options?: CallOptions): AsyncGenerator<ImageEvent> {
		const generation = this.generation;
		const client = this.findImage(generation, request.provider);
		generation.activeCalls += 1;

		try {
			yield* client.stream(request, options);
		} finally {
			this.finishCall(generation);
		}
	}

	image(provider: string): ImageClient {
		return this.findImage(this.generation, provider);
	}

	model(provider: string): ModelClient {
		return this.findModel(this.generation, provider);
	}

	agent(name: AgentName): AgentClient {
		const client = this.generation.agents.get(name);

		if (!client) {
			throw createError("CONFIG_INVALID", `Agent ${name} 未启用或未配置`, {
				provider: name,
			});
		}

		return client;
	}

	listProviders(): ProviderCapability[] {
		const capabilities: ProviderCapability[] = [];

		for (const [name, client] of this.generation.models) {
			const features = [
				...client.capability().features,
			];
			const imageClient = this.generation.images.get(name);

			for (const feature of imageClient?.capability().features ?? []) {
				features.push(feature);
			}

			capabilities.push({
				provider: name,
				features,
			});
		}

		return capabilities;
	}

	async listAgents(): Promise<AgentCapability[]> {
		const capabilities: AgentCapability[] = [];

		for (const client of this.generation.agents.values()) {
			capabilities.push(await client.capabilities());
		}

		return capabilities;
	}

	/**
	 * 重新加载全部配置来源；失败时保留旧配置并抛出 CONFIG_INVALID
	 */
	async reload(): Promise<void> {
		let config: CommonLlmConfig;

		try {
			config = await this.resolver.resolve();
		} catch (error) {
			const reason = isCommonLlmError(error) ? error.message : "配置加载失败";

			throw createError("CONFIG_INVALID", `reload 失败，继续使用旧配置：${reason}`, {
				cause: error,
			});
		}

		const previous = this.generation;
		this.generation = this.buildGeneration(config);
		previous.isRetired = true;

		if (previous.activeCalls === 0) {
			await this.closeGeneration(previous);
		}
	}

	async close(): Promise<void> {
		this.generation.isRetired = true;
		await this.closeGeneration(this.generation);
	}

	private buildGeneration(config: CommonLlmConfig): ClientGeneration {
		const baseLogger = this.options.logger ?? SILENT_LOGGER;
		const logger = createLeveledLogger(baseLogger, config.common.log_level);
		const idGenerator = this.options.idGenerator ?? UUID_ID_GENERATOR;
		const models: Map<string, ModelClient> = new Map();
		const images: Map<string, ImageClient> = new Map();
		const agents: Map<AgentName, AgentClient> = new Map();

		for (const [name, providerConfig] of Object.entries(config.providers)) {
			if (!providerConfig.enabled) {
				continue;
			}

			const clientOptions = {
				name,
				config: providerConfig,
				logger,
				idGenerator,
			};

			// 图片客户端复用同一实例的 SDK 客户端，Anthropic 没有图片生成能力
			if (providerConfig.type === "openai") {
				const modelClient = new OpenAiModelClient(clientOptions);
				models.set(name, modelClient);
				images.set(name, new OpenAiImageClient(clientOptions, modelClient.nativeClient()));
			} else if (providerConfig.type === "anthropic") {
				models.set(name, new AnthropicModelClient(clientOptions));
			} else {
				const modelClient = new GeminiModelClient(clientOptions);
				models.set(name, modelClient);
				images.set(name, new GeminiImageClient(clientOptions, modelClient.nativeClient()));
			}
		}

		if (config.agents.claude_code.enabled) {
			agents.set("claude_code", new ClaudeCodeAgentClient({
				config: config.agents.claude_code,
				logger,
				idGenerator,
				environment: this.options.environment,
			}));
		}

		if (config.agents.codex.enabled) {
			agents.set("codex", new CodexAgentClient({
				config: config.agents.codex,
				logger,
				idGenerator,
				environment: this.options.environment,
			}));
		}

		return {
			config,
			models,
			images,
			agents,
			activeCalls: 0,
			isRetired: false,
		};
	}

	private findModel(generation: ClientGeneration, provider: string): ModelClient {
		const client = generation.models.get(provider);

		if (!client) {
			throw createError("CONFIG_INVALID", `Provider ${provider} 未启用或未配置`, {
				provider,
			});
		}

		return client;
	}

	/**
	 * 未启用的实例报 CONFIG_INVALID，已启用但厂商不支持图片生成时报 SDK_UNSUPPORTED_FEATURE
	 */
	private findImage(generation: ClientGeneration, provider: string): ImageClient {
		const client = generation.images.get(provider);

		if (client) {
			return client;
		}

		if (generation.models.has(provider)) {
			throw createError("SDK_UNSUPPORTED_FEATURE", `Provider ${provider} 不支持图片生成`, {
				provider,
			});
		}

		throw createError("CONFIG_INVALID", `Provider ${provider} 未启用或未配置`, {
			provider,
		});
	}

	private finishCall(generation: ClientGeneration): void {
		generation.activeCalls -= 1;

		if (generation.isRetired && generation.activeCalls === 0) {
			void this.closeGeneration(generation);
		}
	}

	private async closeGeneration(generation: ClientGeneration): Promise<void> {
		const closing: Promise<void>[] = [];

		for (const client of generation.models.values()) {
			closing.push(client.close());
		}

		for (const client of generation.images.values()) {
			closing.push(client.close());
		}

		for (const client of generation.agents.values()) {
			closing.push(client.close());
		}

		await Promise.all(closing);
	}
}
