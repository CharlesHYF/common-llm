/**
 * 基于官方 Google GenAI SDK 的 generateContent 实现 ModelClient
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

import { GoogleGenAI } from "@google/genai";

import { hasFeature } from "../../core/capabilities.js";
import {
	assertRequestFeatures,
	mergeProviderOptions,
	resolveModelName,
	resolveTimeoutMilliseconds,
	runModelCall,
	runModelStream,
	validateModelRequest,
} from "../../core/model-support.js";
import { convertGeminiError } from "./gemini-errors.js";
import {
	buildGeminiRequest,
	convertGeminiResponse,
	convertGeminiStream,
	GEMINI_MAPPED_KEYS,
} from "./gemini-mapper.js";

import type { GenerateContentParameters } from "@google/genai";
import type { ModelClient, ProviderClientOptions } from "../../core/clients.js";
import type { CommonLlmError } from "../../core/errors.js";
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
import type { GeminiConversionContext } from "./gemini-mapper.js";

const GEMINI_FEATURES: readonly ProviderFeature[] = [
	"streaming",

	"tool_calls",

	"structured_output",

	"vision",

	"reasoning",
];

export class GeminiModelClient implements ModelClient {
	readonly provider: string;
	private readonly client: GoogleGenAI;
	private readonly config: ProviderConfig;
	private readonly logger: CommonLlmLogger;
	private readonly idGenerator: IdGenerator;

	constructor(options: ProviderClientOptions) {
		this.provider = options.name;
		this.config = options.config;
		this.logger = options.logger;
		this.idGenerator = options.idGenerator;
		this.client = new GoogleGenAI({
			apiKey: this.config.api_key ?? "",
			vertexai: false,
			httpOptions: {
				baseUrl: this.config.base_url,
				timeout: Math.round(this.config.timeout_seconds * 1000),
				// Gemini 的 retryOptions.attempts 含首次请求，因此等于 max_retries + 1
				retryOptions: {
					attempts: this.config.max_retries + 1,
				},
			},
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
				const response = await this.client.models.generateContent(this.buildParameters(request, signal));

				return convertGeminiResponse(response, this.buildContext(traceId, request));
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
		return hasFeature(GEMINI_FEATURES, feature);
	}

	capability(): ProviderCapability {
		return {
			provider: this.provider,
			features: [
				...GEMINI_FEATURES,
			],
		};
	}

	nativeClient(): GoogleGenAI {
		return this.client;
	}

	async close(): Promise<void> {
		return Promise.resolve();
	}

	private async *produceStream(request: ModelRequest, traceId: string, sequencer: ModelEventSequencer, signal: AbortSignal): AsyncGenerator<ModelEvent> {
		const chunks = await this.client.models.generateContentStream(this.buildParameters(request, signal));
		yield* convertGeminiStream(chunks, sequencer, this.buildContext(traceId, request));
	}

	/**
	 * providerOptions 经 httpOptions.extraBody 合并进请求体，超时按请求级覆盖
	 */
	private buildParameters(request: ModelRequest, signal: AbortSignal): GenerateContentParameters {
		validateModelRequest(request, this.provider);
		assertRequestFeatures(request, GEMINI_FEATURES, this.provider);
		const model = resolveModelName(request, this.config.default_model, this.provider);
		const extraBody = mergeProviderOptions(this.config.native_options, request.providerOptions, GEMINI_MAPPED_KEYS, this.provider);
		const geminiRequest = buildGeminiRequest(request, this.provider);
		const httpOptions: Record<string, unknown> = {
			timeout: resolveTimeoutMilliseconds(request, this.config.timeout_seconds),
		};

		if (Object.keys(extraBody).length > 0) {
			httpOptions.extraBody = extraBody;
		}

		const parameters = {
			model,
			contents: geminiRequest.contents,
			config: {
				...geminiRequest.config,
				abortSignal: signal,
				httpOptions,
			},
		};

		return parameters as unknown as GenerateContentParameters;
	}

	private buildContext(traceId: string, request: ModelRequest): GeminiConversionContext {
		return {
			provider: this.provider,
			traceId,
			includeRawResponse: request.includeRawResponse === true,
		};
	}

	private convertError(error: unknown): CommonLlmError {
		return convertGeminiError(error, this.provider, [
			this.config.api_key,
		]);
	}
}
