/**
 * 基于官方 OpenAI SDK 实现 Responses 与 Chat Completions 两种协议的 ModelClient
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

import OpenAI from "openai";

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
import {
	buildChatBody,
	CHAT_MAPPED_KEYS,
	convertChatCompletion,
	convertChatStream,
} from "./chat-mapper.js";
import { convertOpenAiError } from "./openai-errors.js";
import {
	buildResponsesBody,
	convertResponsesResponse,
	convertResponsesStream,
	RESPONSES_MAPPED_KEYS,
} from "./responses-mapper.js";

import type { ChatCompletionCreateParamsNonStreaming, ChatCompletionCreateParamsStreaming } from "openai/resources/chat/completions";
import type { ResponseCreateParamsNonStreaming, ResponseCreateParamsStreaming } from "openai/resources/responses/responses";
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
import type { ResponseConversionContext } from "./responses-mapper.js";

const RESPONSES_FEATURES: readonly ProviderFeature[] = [
	"streaming",

	"tool_calls",

	"structured_output",

	"vision",

	"reasoning",
];

const CHAT_FEATURES: readonly ProviderFeature[] = [
	"streaming",

	"tool_calls",

	"structured_output",

	"vision",
];

export class OpenAiModelClient implements ModelClient {
	readonly provider: string;
	private readonly client: OpenAI;
	private readonly config: ProviderConfig;
	private readonly logger: CommonLlmLogger;
	private readonly idGenerator: IdGenerator;
	private readonly features: readonly ProviderFeature[];

	constructor(options: ProviderClientOptions) {
		this.provider = options.name;
		this.config = options.config;
		this.logger = options.logger;
		this.idGenerator = options.idGenerator;
		this.features = this.config.api === "chat_completions" ? CHAT_FEATURES : RESPONSES_FEATURES;
		this.client = new OpenAI({
			apiKey: this.config.api_key ?? "",
			baseURL: this.config.base_url,
			timeout: Math.round(this.config.timeout_seconds * 1000),
			maxRetries: this.config.max_retries,
			organization: null,
			project: null,
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
				const prepared = this.prepareRequest(request);
				const requestOptions = {
					signal,
					timeout: resolveTimeoutMilliseconds(request, this.config.timeout_seconds),
				};

				if (this.config.api === "chat_completions") {
					const body = buildChatBody(request, prepared.model, prepared.extraBody) as unknown as ChatCompletionCreateParamsNonStreaming;
					const result = await this.client.chat.completions.create(body, requestOptions).withResponse();

					return convertChatCompletion(result.data, this.buildContext(traceId, result.request_id, request));
				}

				const body = buildResponsesBody(request, prepared.model, prepared.extraBody) as unknown as ResponseCreateParamsNonStreaming;
				const result = await this.client.responses.create(body, requestOptions).withResponse();

				return convertResponsesResponse(result.data, this.buildContext(traceId, result.request_id, request));
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
		return hasFeature(this.features, feature);
	}

	capability(): ProviderCapability {
		return {
			provider: this.provider,
			features: [
				...this.features,
			],
		};
	}

	nativeClient(): OpenAI {
		return this.client;
	}

	async close(): Promise<void> {
		return Promise.resolve();
	}

	private async *produceStream(request: ModelRequest, traceId: string, sequencer: ModelEventSequencer, signal: AbortSignal): AsyncGenerator<ModelEvent> {
		const prepared = this.prepareRequest(request);
		const requestOptions = {
			signal,
			timeout: resolveTimeoutMilliseconds(request, this.config.timeout_seconds),
		};

		if (this.config.api === "chat_completions") {
			const body = {
				...buildChatBody(request, prepared.model, prepared.extraBody),
				stream: true,
				stream_options: {
					include_usage: true,
				},
			} as unknown as ChatCompletionCreateParamsStreaming;
			const result = await this.client.chat.completions.create(body, requestOptions).withResponse();
			yield* convertChatStream(result.data, sequencer, this.buildContext(traceId, result.request_id, request));
			return;
		}

		const body = {
			...buildResponsesBody(request, prepared.model, prepared.extraBody),
			stream: true,
		} as unknown as ResponseCreateParamsStreaming;
		const result = await this.client.responses.create(body, requestOptions).withResponse();
		yield* convertResponsesStream(result.data, sequencer, this.buildContext(traceId, result.request_id, request));
	}

	private prepareRequest(request: ModelRequest): {
		model: string;
		extraBody: JsonRecord;
	} {
		validateModelRequest(request, this.provider);
		assertRequestFeatures(request, this.features, this.provider);
		const mappedKeys = this.config.api === "chat_completions" ? CHAT_MAPPED_KEYS : RESPONSES_MAPPED_KEYS;

		return {
			model: resolveModelName(request, this.config.default_model, this.provider),
			extraBody: mergeProviderOptions(this.config.native_options, request.providerOptions, mappedKeys, this.provider),
		};
	}

	private buildContext(traceId: string, requestId: string | null, request: ModelRequest): ResponseConversionContext {
		return {
			provider: this.provider,
			traceId,
			requestId,
			includeRawResponse: request.includeRawResponse === true,
		};
	}

	private convertError(error: unknown): CommonLlmError {
		return convertOpenAiError(error, this.provider, [
			this.config.api_key,
		]);
	}
}
