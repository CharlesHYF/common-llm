/**
 * 基于官方 Anthropic SDK 的 Messages API 实现 ModelClient
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

import Anthropic from "@anthropic-ai/sdk";

import { hasFeature } from "../../core/capabilities.js";
import { createError } from "../../core/errors.js";
import {
	assertRequestFeatures,
	mergeProviderOptions,
	resolveModelName,
	resolveTimeoutMilliseconds,
	runModelCall,
	runModelStream,
	validateModelRequest,
} from "../../core/model-support.js";
import { convertAnthropicError } from "./anthropic-errors.js";
import {
	ANTHROPIC_MAPPED_KEYS,
	buildAnthropicBody,
	convertAnthropicMessage,
	convertAnthropicStream,
} from "./anthropic-mapper.js";

import type { MessageCreateParamsNonStreaming, MessageCreateParamsStreaming } from "@anthropic-ai/sdk/resources/messages/messages";
import type { ModelClient, ProviderClientOptions } from "../../core/clients.js";
import type { CommonLlmError } from "../../core/errors.js";
import type { JsonRecord } from "../../core/json.js";
import type { ModelEventSequencer } from "../../core/model-support.js";
import type {
	CallOptions,
	ModelEvent,
	ModelRequest,
	ModelResponse,
	ProviderCapability,
	ProviderFeature,
} from "../../core/types.js";
import type { ProviderConfig } from "../../config/types.js";
import type { CommonLlmLogger } from "../../observability/logger.js";
import type { IdGenerator } from "../../observability/ids.js";
import type { AnthropicConversionContext } from "./anthropic-mapper.js";

const ANTHROPIC_FEATURES: readonly ProviderFeature[] = [
	"streaming",

	"tool_calls",

	"structured_output",

	"vision",

	"reasoning",
];

export class AnthropicModelClient implements ModelClient {
	readonly provider: string;
	private readonly client: Anthropic;
	private readonly config: ProviderConfig;
	private readonly logger: CommonLlmLogger;
	private readonly idGenerator: IdGenerator;

	constructor(options: ProviderClientOptions) {
		this.provider = options.name;
		this.config = options.config;
		this.logger = options.logger;
		this.idGenerator = options.idGenerator;
		this.client = new Anthropic({
			apiKey: this.config.api_key ?? "",
			authToken: null,
			baseURL: this.config.base_url,
			timeout: Math.round(this.config.timeout_seconds * 1000),
			maxRetries: this.config.max_retries,
		});
	}

	generate(request: ModelRequest, options: CallOptions = {}): Promise<ModelResponse> {
		const traceId = request.traceId ?? this.idGenerator.createTraceId();

		return runModelCall({
			provider: this.provider,
			traceId,
			model: request.model ?? this.config.default_model ?? null,
			signal: options.signal,
			logger: this.logger,
			convertError: (error) => this.convertError(error),
			call: async (signal) => {
				const body = this.buildBody(request) as unknown as MessageCreateParamsNonStreaming;
				const result = await this.client.messages.create(body, {
					signal,
					timeout: resolveTimeoutMilliseconds(request, this.config.timeout_seconds),
				}).withResponse();

				return convertAnthropicMessage(result.data, this.buildContext(traceId, result.request_id, request));
			},
		});
	}

	stream(request: ModelRequest, options: CallOptions = {}): AsyncIterable<ModelEvent> {
		const traceId = request.traceId ?? this.idGenerator.createTraceId();

		return runModelStream({
			provider: this.provider,
			traceId,
			model: request.model ?? this.config.default_model ?? null,
			signal: options.signal,
			logger: this.logger,
			convertError: (error) => this.convertError(error),
			produce: (sequencer, signal) => this.produceStream(request, traceId, sequencer, signal),
		});
	}

	supports(feature: string): boolean {
		return hasFeature(ANTHROPIC_FEATURES, feature);
	}

	capability(): ProviderCapability {
		return {
			provider: this.provider,
			features: [
				...ANTHROPIC_FEATURES,
			],
		};
	}

	nativeClient(): Anthropic {
		return this.client;
	}

	async close(): Promise<void> {
		return Promise.resolve();
	}

	private async *produceStream(request: ModelRequest, traceId: string, sequencer: ModelEventSequencer, signal: AbortSignal): AsyncGenerator<ModelEvent> {
		const body = {
			...this.buildBody(request),
			stream: true,
		} as unknown as MessageCreateParamsStreaming;
		const result = await this.client.messages.create(body, {
			signal,
			timeout: resolveTimeoutMilliseconds(request, this.config.timeout_seconds),
		}).withResponse();
		yield* convertAnthropicStream(result.data, sequencer, this.buildContext(traceId, result.request_id, request));
	}

	private buildBody(request: ModelRequest): JsonRecord {
		validateModelRequest(request, this.provider);
		assertRequestFeatures(request, ANTHROPIC_FEATURES, this.provider);

		// @anthropic-ai/sdk 1.8.0 的 Messages 参数已移除 temperature，需要时通过 providerOptions 透传
		if (request.temperature !== undefined && request.temperature !== null) {
			throw createError("SDK_UNSUPPORTED_FEATURE", "Anthropic Messages API 已弃用 temperature，如确需传递请使用 providerOptions", {
				provider: this.provider,
			});
		}

		const model = resolveModelName(request, this.config.default_model, this.provider);
		const nativeOptions: JsonRecord = {
			...this.config.native_options,
		};
		// native_options.max_tokens 是 max_tokens 的配置级默认值，不作为额外请求体字段
		const nativeMaxTokens = nativeOptions.max_tokens;
		delete nativeOptions.max_tokens;
		let maxTokens = request.maxOutputTokens ?? null;

		if (maxTokens === null && typeof nativeMaxTokens === "number") {
			maxTokens = nativeMaxTokens;
		}

		if (maxTokens === null) {
			throw createError("CONFIG_INVALID", "Anthropic 需要 maxOutputTokens，或在配置 native_options.max_tokens 中提供默认值", {
				provider: this.provider,
			});
		}

		const extraBody = mergeProviderOptions(nativeOptions, request.providerOptions, ANTHROPIC_MAPPED_KEYS, this.provider);

		return buildAnthropicBody(request, model, maxTokens, extraBody);
	}

	private buildContext(traceId: string, requestId: string | null | undefined, request: ModelRequest): AnthropicConversionContext {
		return {
			provider: this.provider,
			traceId,
			requestId: requestId ?? null,
			includeRawResponse: request.includeRawResponse === true,
		};
	}

	private convertError(error: unknown): CommonLlmError {
		return convertAnthropicError(error, this.provider, [
			this.config.api_key,
		]);
	}
}
