/**
 * 基于 Google GenAI SDK 的 generateContent 原生出图实现图片生成
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

import { hasFeature } from "../../core/capabilities.js";
import { createError } from "../../core/errors.js";
import {
	assertNoImageUrls,
	assertUnsupportedImageFields,
	createFailedImageStream,
	resolveImageModel,
	runImageGeneration,
	runImageStream,
	validateImageRequest,
} from "../../core/image-support.js";
import { mergeProviderOptions } from "../../core/model-support.js";
import { convertGeminiError } from "./gemini-errors.js";
import {
	buildGeminiImageRequest,
	convertGeminiImageResponse,
	convertGeminiImageStream,
	GEMINI_IMAGE_MAPPED_KEYS,
} from "./gemini-image-mapper.js";

import type { GenerateContentParameters, GoogleGenAI } from "@google/genai";
import type { ImageClient, ProviderClientOptions } from "../../core/clients.js";
import type { CommonLlmError } from "../../core/errors.js";
import type { ImageStreamItem } from "../../core/image-support.js";
import type {
	CallOptions,
	ImageEvent,
	ImageRequest,
	ImageResponse,
	ProviderCapability,
	ProviderFeature,
} from "../../core/types.js";
import type { ProviderConfig } from "../../config/types.js";
import type { CommonLlmLogger } from "../../observability/logger.js";
import type { IdGenerator } from "../../observability/ids.js";

interface PreparedImageCall {
	model: string;
	extraBody: Record<string, unknown>;
	timeoutMs: number;
	callCount: number;
}

const GEMINI_IMAGE_FEATURES: readonly ProviderFeature[] = [
	"image_generation",

	"image_edit",
];

export class GeminiImageClient implements ImageClient {
	readonly provider: string;
	private readonly config: ProviderConfig;
	private readonly logger: CommonLlmLogger;
	private readonly idGenerator: IdGenerator;

	/**
	 * 复用 GeminiModelClient 创建的 SDK 客户端，不另建连接
	 */
	constructor(
		options: ProviderClientOptions,
		private readonly client: GoogleGenAI,
	) {
		this.provider = options.name;
		this.config = options.config;
		this.logger = options.logger;
		this.idGenerator = options.idGenerator;
	}

	async generate(request: ImageRequest, options: CallOptions = {}): Promise<ImageResponse> {
		const traceId = request.traceId ?? this.idGenerator.createTraceId();
		const prepared = this.prepareCall(request, false);

		return runImageGeneration({
			provider: this.provider,
			traceId,
			model: prepared.model,
			signal: options.signal,
			logger: this.logger,
			convertError: (error) => this.convertError(error),
			callCount: prepared.callCount,
			concurrency: this.config.image_concurrency,
			callOnce: async (signal) => {
				const response = await this.client.models.generateContent(this.buildParameters(request, prepared, signal));

				return convertGeminiImageResponse(response, {
					provider: this.provider,
					traceId,
					includeRawResponse: request.includeRawResponse === true,
				});
			},
		});
	}

	stream(request: ImageRequest, options: CallOptions = {}): AsyncIterable<ImageEvent> {
		const traceId = request.traceId ?? this.idGenerator.createTraceId();
		let prepared: PreparedImageCall;

		try {
			prepared = this.prepareCall(request, true);
		} catch (error) {
			return runImageStream({
				provider: this.provider,
				traceId,
				model: request.model ?? "",
				signal: options.signal,
				logger: this.logger,
				convertError: (caught) => this.convertError(caught),
				callCount: 1,
				concurrency: 1,
				streamOnce: () => createFailedImageStream(error),
			});
		}

		return runImageStream({
			provider: this.provider,
			traceId,
			model: prepared.model,
			signal: options.signal,
			logger: this.logger,
			convertError: (error) => this.convertError(error),
			callCount: prepared.callCount,
			concurrency: this.config.image_concurrency,
			streamOnce: (signal) => this.streamOnce(request, prepared, traceId, signal),
		});
	}

	supports(feature: string): boolean {
		return hasFeature(GEMINI_IMAGE_FEATURES, feature);
	}

	capability(): ProviderCapability {
		return {
			provider: this.provider,
			features: [
				...GEMINI_IMAGE_FEATURES,
			],
		};
	}

	nativeClient(): GoogleGenAI {
		return this.client;
	}

	async close(): Promise<void> {
		return Promise.resolve();
	}

	/**
	 * Gemini 不支持蒙版、部分图片与 OpenAI 专属参数，n 大于 1 时库内循环
	 */
	private prepareCall(request: ImageRequest, isStream: boolean): PreparedImageCall {
		validateImageRequest(request, this.provider);
		assertUnsupportedImageFields(request, [
			"mask",

			"size",

			"quality",

			"outputFormat",

			"outputCompression",

			"background",
		], "Gemini", this.provider);
		assertNoImageUrls(request, "Gemini", this.provider);

		if (!isStream && request.partialImages !== undefined && request.partialImages !== null) {
			throw createError("SDK_UNSUPPORTED_FEATURE", "partialImages 只用于流式图片生成", {
				provider: this.provider,
			});
		}

		if (request.partialImages !== undefined && request.partialImages !== null && request.partialImages > 0) {
			throw createError("SDK_UNSUPPORTED_FEATURE", "Gemini 不返回部分图片，partialImages 必须为 0", {
				provider: this.provider,
			});
		}

		return {
			model: resolveImageModel(request, this.config.default_image_model, this.provider),
			extraBody: mergeProviderOptions({}, request.providerOptions, GEMINI_IMAGE_MAPPED_KEYS, this.provider),
			timeoutMs: Math.round((request.timeoutSeconds ?? this.config.image_timeout_seconds) * 1000),
			callCount: request.n ?? 1,
		};
	}

	private async *streamOnce(request: ImageRequest, prepared: PreparedImageCall, traceId: string, signal: AbortSignal): AsyncGenerator<ImageStreamItem> {
		const chunks = await this.client.models.generateContentStream(this.buildParameters(request, prepared, signal));
		yield* convertGeminiImageStream(chunks, {
			provider: this.provider,
			traceId,
			includeRawResponse: false,
		});
	}

	private buildParameters(request: ImageRequest, prepared: PreparedImageCall, signal: AbortSignal): GenerateContentParameters {
		const geminiRequest = buildGeminiImageRequest(request);
		const httpOptions: Record<string, unknown> = {
			timeout: prepared.timeoutMs,
		};

		if (Object.keys(prepared.extraBody).length > 0) {
			httpOptions.extraBody = prepared.extraBody;
		}

		const parameters = {
			model: prepared.model,
			contents: geminiRequest.contents,
			config: {
				...geminiRequest.config,
				abortSignal: signal,
				httpOptions,
			},
		};

		return parameters as unknown as GenerateContentParameters;
	}

	private convertError(error: unknown): CommonLlmError {
		return convertGeminiError(error, this.provider, [
			this.config.api_key,
		]);
	}
}
