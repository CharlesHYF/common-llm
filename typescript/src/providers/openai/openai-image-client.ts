/**
 * 基于 OpenAI SDK 实现图片生成，按配置 image_api 选择 Images API 或 Responses image_generation 工具
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
import { convertOpenAiError } from "./openai-errors.js";
import {
	buildImagesBody,
	buildResponsesImageBody,
	convertImagesResponse,
	convertImagesStream,
	convertResponsesImageResponse,
	convertResponsesImageStream,
	IMAGES_MAPPED_KEYS,
	RESPONSES_IMAGE_MAPPED_KEYS,
} from "./openai-image-mapper.js";

import type OpenAI from "openai";
import type {
	ImageEditParamsNonStreaming,
	ImageEditParamsStreaming,
	ImageGenerateParamsNonStreaming,
	ImageGenerateParamsStreaming,
} from "openai/resources/images";
import type { ResponseCreateParamsNonStreaming, ResponseCreateParamsStreaming } from "openai/resources/responses/responses";
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
import type { ImageConversionContext } from "./openai-image-mapper.js";

interface PreparedImageCall {
	model: string;
	conversationModel: string;
	extraBody: Record<string, unknown>;
	timeoutMs: number;
	callCount: number;
}

const IMAGES_API_FEATURES: readonly ProviderFeature[] = [
	"image_generation",

	"image_edit",

	"image_mask",

	"image_streaming",

	"image_multiple",
];

const RESPONSES_IMAGE_FEATURES: readonly ProviderFeature[] = [
	"image_generation",

	"image_edit",

	"image_mask",

	"image_streaming",
];

// Images API 单次最多 16 张输入图片、原生 n 最多 10
const IMAGES_API_MAX_INPUT_IMAGES = 16;
const IMAGES_API_MAX_N = 10;

export class OpenAiImageClient implements ImageClient {
	readonly provider: string;
	private readonly config: ProviderConfig;
	private readonly logger: CommonLlmLogger;
	private readonly idGenerator: IdGenerator;
	private readonly features: readonly ProviderFeature[];

	/**
	 * 复用 OpenAiModelClient 创建的 SDK 客户端，不另建连接
	 */
	constructor(
		options: ProviderClientOptions,
		private readonly client: OpenAI,
	) {
		this.provider = options.name;
		this.config = options.config;
		this.logger = options.logger;
		this.idGenerator = options.idGenerator;
		this.features = this.config.image_api === "responses" ? RESPONSES_IMAGE_FEATURES : IMAGES_API_FEATURES;
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
			callOnce: (signal) => this.generateOnce(request, prepared, traceId, signal),
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

	/**
	 * 校验请求并确定模型、额外参数与调用次数；Images API 原生支持 n，Responses 路径库内循环
	 */
	private prepareCall(request: ImageRequest, isStream: boolean): PreparedImageCall {
		validateImageRequest(request, this.provider);
		const isResponsesPath = this.config.image_api === "responses";
		const pathName = isResponsesPath ? "OpenAI Responses 图片路径" : "OpenAI Images API";
		assertUnsupportedImageFields(request, [
			"aspectRatio",

			"imageSize",
		], pathName, this.provider);

		if (!isStream) {
			assertUnsupportedImageFields(request, [
				"partialImages",
			], pathName, this.provider);
		}

		const model = resolveImageModel(request, this.config.default_image_model, this.provider);
		const mappedKeys = isResponsesPath ? RESPONSES_IMAGE_MAPPED_KEYS : IMAGES_MAPPED_KEYS;
		const extraBody = mergeProviderOptions({}, request.providerOptions, mappedKeys, this.provider);
		const imageCount = request.n ?? 1;
		const timeoutMs = Math.round((request.timeoutSeconds ?? this.config.image_timeout_seconds) * 1000);

		if (isResponsesPath) {
			if (request.mask?.url) {
				throw createError("SDK_UNSUPPORTED_FEATURE", "OpenAI Responses 图片路径的 mask 只支持 base64", {
					provider: this.provider,
				});
			}

			if (!this.config.default_model) {
				throw createError("CONFIG_INVALID", "image_api=responses 需要配置 default_model 作为对话模型", {
					provider: this.provider,
				});
			}

			return {
				model,
				conversationModel: this.config.default_model,
				extraBody,
				timeoutMs,
				callCount: imageCount,
			};
		}

		assertNoImageUrls(request, pathName, this.provider);

		if ((request.images?.length ?? 0) > IMAGES_API_MAX_INPUT_IMAGES || imageCount > IMAGES_API_MAX_N) {
			throw createError("CONFIG_INVALID", `OpenAI Images API 最多 ${IMAGES_API_MAX_INPUT_IMAGES} 张输入图片、n 最大为 ${IMAGES_API_MAX_N}`, {
				provider: this.provider,
			});
		}

		return {
			model,
			conversationModel: model,
			extraBody,
			timeoutMs,
			callCount: 1,
		};
	}

	private async generateOnce(request: ImageRequest, prepared: PreparedImageCall, traceId: string, signal: AbortSignal): Promise<ImageResponse> {
		const requestOptions = {
			signal,
			timeout: prepared.timeoutMs,
		};

		if (this.config.image_api === "responses") {
			const body = buildResponsesImageBody(request, prepared.conversationModel, prepared.model, prepared.extraBody, false) as unknown as ResponseCreateParamsNonStreaming;
			const result = await this.client.responses.create(body, requestOptions).withResponse();

			return convertResponsesImageResponse(result.data, this.buildContext(request, prepared, traceId, result.request_id));
		}

		const body = await buildImagesBody(request, prepared.model, prepared.extraBody, false);

		if (request.images && request.images.length > 0) {
			const result = await this.client.images.edit(body as unknown as ImageEditParamsNonStreaming, requestOptions).withResponse();

			return convertImagesResponse(result.data, this.buildContext(request, prepared, traceId, result.request_id));
		}

		const result = await this.client.images.generate(body as unknown as ImageGenerateParamsNonStreaming, requestOptions).withResponse();

		return convertImagesResponse(result.data, this.buildContext(request, prepared, traceId, result.request_id));
	}

	private async *streamOnce(request: ImageRequest, prepared: PreparedImageCall, traceId: string, signal: AbortSignal): AsyncGenerator<ImageStreamItem> {
		const requestOptions = {
			signal,
			timeout: prepared.timeoutMs,
		};

		if (this.config.image_api === "responses") {
			const body = buildResponsesImageBody(request, prepared.conversationModel, prepared.model, prepared.extraBody, true) as unknown as ResponseCreateParamsStreaming;
			const result = await this.client.responses.create(body, requestOptions).withResponse();
			yield* convertResponsesImageStream(result.data, this.buildContext(request, prepared, traceId, result.request_id));
			return;
		}

		const body = await buildImagesBody(request, prepared.model, prepared.extraBody, true);

		if (request.images && request.images.length > 0) {
			const result = await this.client.images.edit(body as unknown as ImageEditParamsStreaming, requestOptions).withResponse();
			yield* convertImagesStream(result.data, this.buildContext(request, prepared, traceId, result.request_id));
			return;
		}

		const result = await this.client.images.generate(body as unknown as ImageGenerateParamsStreaming, requestOptions).withResponse();
		yield* convertImagesStream(result.data, this.buildContext(request, prepared, traceId, result.request_id));
	}

	private buildContext(request: ImageRequest, prepared: PreparedImageCall, traceId: string, requestId: string | null): ImageConversionContext {
		return {
			provider: this.provider,
			traceId,
			model: prepared.model,
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
