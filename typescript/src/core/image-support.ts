/**
 * 提供图片生成共用的请求校验、图片编码、库内循环与流式事件编排
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

import { readFile } from "node:fs/promises";
import path from "node:path";

import { createError, isCommonLlmError } from "./errors.js";
import { createLinkedAbortController, sumNullableCounts } from "./model-support.js";

import type { CommonLlmLogger } from "../observability/logger.js";
import type { CommonLlmError } from "./errors.js";
import type {
	GeneratedImage,
	ImageEvent,
	ImageInput,
	ImageRequest,
	ImageResponse,
	Usage,
} from "./types.js";

/**
 * 单次厂商调用产出的流式条目，imageIndex 为该次调用内的序号，由编排层换算为全局序号
 */
export type ImageStreamItem =
	| {
		kind: "started";
		model: string | null;
		id: string | null;
	}
	| {
		kind: "partial_image";
		imageIndex: number;
		partialIndex: number;
		data: Uint8Array;
		mimeType: string;
	}
	| {
		kind: "image_completed";
		imageIndex: number;
		image: GeneratedImage;
	}
	| {
		kind: "response";
		response: ImageResponse;
		hasUsage: boolean;
	};

export interface ImageRunBase {
	provider: string;
	traceId: string;
	model: string;
	signal?: AbortSignal | undefined;
	logger: CommonLlmLogger;
	convertError: (error: unknown) => CommonLlmError;
	callCount: number;
	concurrency: number;
}

export interface ImageGenerationRun extends ImageRunBase {
	callOnce: (signal: AbortSignal) => Promise<ImageResponse>;
}

export interface ImageStreamRun extends ImageRunBase {
	streamOnce: (signal: AbortSignal) => AsyncIterable<ImageStreamItem>;
}

// 按事件类型分别去掉公共字段，便于编排层只填写附加字段
type OmitCommonFields<TEvent> = TEvent extends unknown ? Omit<TEvent, "traceId" | "sequence" | "provider"> : never;

type ImageEventFields = OmitCommonFields<ImageEvent>;

interface StreamCallState {
	items: ImageStreamItem[];
	isDone: boolean;
	failure: unknown;
	notify: (() => void) | null;
}

const IMAGE_MIME_TYPES: Record<string, string> = {
	png: "image/png",
	jpeg: "image/jpeg",
	jpg: "image/jpeg",
	webp: "image/webp",
	gif: "image/gif",
};

/**
 * 按 output_format 推出 MIME 类型，缺省为 PNG
 */
export function resolveImageMimeType(outputFormat: string | null | undefined): string {
	if (!outputFormat) {
		return "image/png";
	}

	return IMAGE_MIME_TYPES[outputFormat] ?? `image/${outputFormat}`;
}

/**
 * 由 MIME 类型得到上传文件扩展名
 */
export function resolveImageExtension(mimeType: string): string {
	for (const [extension, knownMimeType] of Object.entries(IMAGE_MIME_TYPES)) {
		if (knownMimeType === mimeType) {
			return extension;
		}
	}

	return mimeType.split("/")[1] ?? "bin";
}

/**
 * 从图片字节创建 ImageInput
 */
export function createImageInputFromBytes(data: Uint8Array, mimeType: string): ImageInput {
	return {
		base64: Buffer.from(data).toString("base64"),
		mimeType,
	};
}

/**
 * 读取图片文件创建 ImageInput，未指定 MIME 类型时按扩展名识别，无法识别报 CONFIG_INVALID
 */
export async function createImageInputFromFile(filePath: string, mimeType?: string): Promise<ImageInput> {
	const extension = path.extname(filePath).slice(1).toLowerCase();
	const resolvedMimeType = mimeType ?? IMAGE_MIME_TYPES[extension];

	if (!resolvedMimeType) {
		throw createError("CONFIG_INVALID", `无法识别图片文件的 MIME 类型：${filePath}`);
	}

	return createImageInputFromBytes(await readFile(filePath), resolvedMimeType);
}

export function decodeBase64Image(base64: string): Uint8Array {
	return Buffer.from(base64, "base64");
}

/**
 * 把图片转为 data URL，用于 Responses 的 input_image
 */
export function buildImageDataUrl(input: ImageInput): string {
	if (input.url) {
		return input.url;
	}

	return `data:${input.mimeType ?? ""};base64,${input.base64 ?? ""}`;
}

/**
 * 校验 ImageRequest 的通用约束，路径相关的字段由各 Provider 另行检查
 */
export function validateImageRequest(request: ImageRequest, provider: string): void {
	if (!request.prompt) {
		throw createError("CONFIG_INVALID", "ImageRequest.prompt 不能为空", {
			provider,
		});
	}

	for (const input of request.images ?? []) {
		validateImageInput(input, provider);
	}

	if (request.mask) {
		validateImageInput(request.mask, provider);

		if (!request.images || request.images.length === 0) {
			throw createError("CONFIG_INVALID", "使用 mask 时需要同时提供 images", {
				provider,
			});
		}
	}

	if (request.n !== undefined && request.n !== null && (!Number.isInteger(request.n) || request.n < 1)) {
		throw createError("CONFIG_INVALID", "ImageRequest.n 必须是不小于 1 的整数", {
			provider,
		});
	}

	if (request.outputCompression !== undefined && request.outputCompression !== null && (request.outputCompression < 0 || request.outputCompression > 100)) {
		throw createError("CONFIG_INVALID", "ImageRequest.outputCompression 必须在 0 到 100 之间", {
			provider,
		});
	}

	if (request.partialImages !== undefined && request.partialImages !== null && (!Number.isInteger(request.partialImages) || request.partialImages < 0)) {
		throw createError("CONFIG_INVALID", "ImageRequest.partialImages 必须是不小于 0 的整数", {
			provider,
		});
	}
}

function validateImageInput(input: ImageInput, provider: string): void {
	const hasUrl = typeof input.url === "string" && input.url !== "";
	const hasBase64 = typeof input.base64 === "string" && input.base64 !== "";

	if (hasUrl === hasBase64) {
		throw createError("CONFIG_INVALID", "ImageInput 需要 url，或 base64 加 mimeType，二者只能选一种", {
			provider,
		});
	}

	if (hasBase64 && !input.mimeType) {
		throw createError("CONFIG_INVALID", "无法识别输入图片的 MIME 类型，base64 图片需要 mimeType", {
			provider,
		});
	}
}

/**
 * 请求了所选路径不支持的字段时报 SDK_UNSUPPORTED_FEATURE
 */
export function assertUnsupportedImageFields(request: ImageRequest, fieldNames: readonly (keyof ImageRequest)[], pathName: string, provider: string): void {
	for (const fieldName of fieldNames) {
		const value = request[fieldName];

		if (value === undefined || value === null) {
			continue;
		}

		throw createError("SDK_UNSUPPORTED_FEATURE", `${pathName} 不支持 ImageRequest.${String(fieldName)}`, {
			provider,
		});
	}
}

/**
 * 输入图片含 URL 时报 SDK_UNSUPPORTED_FEATURE，本库不代为下载
 */
export function assertNoImageUrls(request: ImageRequest, pathName: string, provider: string): void {
	const inputs: ImageInput[] = [
		...(request.images ?? []),
	];

	if (request.mask) {
		inputs.push(request.mask);
	}

	if (inputs.some((input) => Boolean(input.url))) {
		throw createError("SDK_UNSUPPORTED_FEATURE", `${pathName} 不支持 URL 形式的输入图片`, {
			provider,
		});
	}
}

export function resolveImageModel(request: ImageRequest, defaultImageModel: string | undefined, provider: string): string {
	const model = request.model ?? defaultImageModel;

	if (!model) {
		throw createError("CONFIG_INVALID", "请求未指定 model，且配置中没有 default_image_model", {
			provider,
		});
	}

	return model;
}

/**
 * 合并库内循环的结果：图片按调用顺序拼接，用量逐项求和，文字按换行连接，id、model、requestId 取最后一次
 */
export function combineImageResponses(responses: readonly ImageResponse[]): ImageResponse {
	const images: GeneratedImage[] = [];
	const texts: string[] = [];
	const rawResponses: Record<string, unknown>[] = [];
	let usage: Usage = {
		inputTokens: 0,
		outputTokens: 0,
		cachedInputTokens: 0,
		reasoningTokens: 0,
		totalTokens: 0,
	};

	for (const response of responses) {
		for (const image of response.images) {
			images.push(image);
		}

		if (response.text !== "") {
			texts.push(response.text);
		}

		usage = {
			inputTokens: sumNullableCounts(usage.inputTokens, response.usage.inputTokens),
			outputTokens: sumNullableCounts(usage.outputTokens, response.usage.outputTokens),
			cachedInputTokens: sumNullableCounts(usage.cachedInputTokens, response.usage.cachedInputTokens),
			reasoningTokens: sumNullableCounts(usage.reasoningTokens, response.usage.reasoningTokens),
			totalTokens: sumNullableCounts(usage.totalTokens, response.usage.totalTokens),
		};

		if (response.rawResponse && !Array.isArray(response.rawResponse)) {
			rawResponses.push(response.rawResponse);
		}
	}

	const last = responses[responses.length - 1];

	if (!last) {
		throw createError("INTERNAL_ERROR", "库内循环没有任何结果");
	}

	const combined: ImageResponse = {
		provider: last.provider,
		model: last.model,
		id: last.id,
		images,
		text: texts.join("\n"),
		usage,
		traceId: last.traceId,
		requestId: last.requestId,
	};

	if (rawResponses.length > 0) {
		combined.rawResponse = rawResponses;
	}

	return combined;
}

/**
 * 按并发上限执行 callCount 次调用：任一次失败即取消其余调用并停止发起新调用，结果按调用顺序返回
 */
async function runLoopedCalls<TResult>(
	callCount: number,
	concurrency: number,
	signal: AbortSignal,
	call: (index: number, signal: AbortSignal) => Promise<TResult>,
): Promise<TResult[]> {
	const controller = createLinkedAbortController(signal);
	const results: TResult[] = [];
	let nextIndex = 0;
	let failure: unknown = null;
	let hasFailure = false;

	const runWorker = async (): Promise<void> => {
		while (!hasFailure && nextIndex < callCount) {
			const index = nextIndex;
			nextIndex += 1;

			try {
				results[index] = await call(index, controller.signal);
			} catch (error) {
				if (!hasFailure) {
					hasFailure = true;
					failure = error;
					controller.abort();
				}

				return;
			}
		}
	};

	const workers: Promise<void>[] = [];
	const workerCount = Math.min(concurrency, callCount);

	for (let workerIndex = 0; workerIndex < workerCount; workerIndex += 1) {
		workers.push(runWorker());
	}

	await Promise.all(workers);

	if (hasFailure) {
		throw failure;
	}

	return results;
}

/**
 * 执行一次图片生成（含库内循环），统一处理取消、错误转换与调用日志
 */
export async function runImageGeneration(run: ImageGenerationRun): Promise<ImageResponse> {
	const controller = createLinkedAbortController(run.signal);
	const startedAt = Date.now();

	try {
		const responses = await runLoopedCalls(run.callCount, run.concurrency, controller.signal, (_index, signal) => run.callOnce(signal));
		const response = responses.length === 1 && responses[0] ? responses[0] : combineImageResponses(responses);
		run.logger.info("图片生成完成", {
			provider: run.provider,
			model: run.model,
			durationMs: Date.now() - startedAt,
			traceId: run.traceId,
			imageCount: response.images.length,
			usage: response.usage,
		});

		return response;
	} catch (caught) {
		const error = resolveImageError(run, caught);
		run.logger.warn("图片生成失败", {
			provider: run.provider,
			model: run.model,
			durationMs: Date.now() - startedAt,
			traceId: run.traceId,
			category: error.category,
		});
		throw error;
	} finally {
		controller.abort();
	}
}

function resolveImageError(run: ImageRunBase, caught: unknown): CommonLlmError {
	if (run.signal?.aborted === true) {
		return createError("CANCELLED", "调用方取消了图片生成请求", {
			provider: run.provider,
		});
	}

	if (isCommonLlmError(caught)) {
		return caught;
	}

	return run.convertError(caught);
}

/**
 * 为单个图片流生成带递增 sequence 的 ImageEvent
 */
class ImageEventSequencer {
	private nextSequence = 0;

	constructor(
		private readonly provider: string,
		private readonly traceId: string,
	) {}

	build(fields: ImageEventFields): ImageEvent {
		const event = {
			...fields,
			traceId: this.traceId,
			sequence: this.nextSequence,
			provider: this.provider,
		} as ImageEvent;
		this.nextSequence += 1;

		return event;
	}
}

/**
 * 编排图片流：循环调用时按调用顺序输出事件并换算全局 imageIndex，保证只有一个终止事件
 */
export async function* runImageStream(run: ImageStreamRun): AsyncGenerator<ImageEvent> {
	const sequencer = new ImageEventSequencer(run.provider, run.traceId);
	const controller = createLinkedAbortController(run.signal);
	const calls: StreamCallState[] = [];
	const responses: ImageResponse[] = [];
	let hasStarted = false;
	let hasUsage = false;
	let imageOffset = 0;

	for (let callIndex = 0; callIndex < run.callCount; callIndex += 1) {
		calls.push({
			items: [],
			isDone: false,
			failure: null,
			notify: null,
		});
	}

	const loopPromise = runLoopedCalls(run.callCount, run.concurrency, controller.signal, async (index, signal) => {
		const state = calls[index] as StreamCallState;

		try {
			for await (const item of run.streamOnce(signal)) {
				state.items.push(item);
				wakeReader(state);
			}
		} catch (error) {
			state.failure = error;
			throw error;
		} finally {
			state.isDone = true;
			wakeReader(state);
		}
	});
	loopPromise.catch(() => undefined);

	try {
		for (const state of calls) {
			let callImageCount = 0;

			while (true) {
				const item = state.items.shift();

				if (item === undefined) {
					if (state.isDone) {
						break;
					}

					await new Promise<void>((resolve) => {
						state.notify = resolve;
					});
					continue;
				}

				if (item.kind === "started") {
					if (!hasStarted) {
						hasStarted = true;
						yield sequencer.build({
							type: "started",
							model: item.model,
							id: item.id,
						});
					}

					continue;
				}

				if (item.kind === "partial_image") {
					yield sequencer.build({
						type: "partial_image",
						imageIndex: imageOffset + item.imageIndex,
						partialIndex: item.partialIndex,
						data: item.data,
						mimeType: item.mimeType,
					});
					continue;
				}

				if (item.kind === "image_completed") {
					callImageCount = Math.max(callImageCount, item.imageIndex + 1);
					yield sequencer.build({
						type: "image_completed",
						imageIndex: imageOffset + item.imageIndex,
						image: item.image,
					});
					continue;
				}

				responses.push(item.response);
				hasUsage = hasUsage || item.hasUsage;
				callImageCount = Math.max(callImageCount, item.response.images.length);
			}

			// 并发时其他调用被取消也会让本次调用失败，因此以循环记录的首个失败为准
			if (state.failure !== null) {
				await loopPromise;
				throw state.failure;
			}

			imageOffset += callImageCount;
		}

		await loopPromise;
		const response = responses.length === 1 && responses[0] ? responses[0] : combineImageResponses(responses);

		if (hasUsage) {
			yield sequencer.build({
				type: "usage",
				usage: response.usage,
			});
		}

		run.logger.info("图片流式生成完成", {
			provider: run.provider,
			model: run.model,
			traceId: run.traceId,
			imageCount: response.images.length,
			usage: response.usage,
		});
		yield sequencer.build({
			type: "completed",
			response,
		});
	} catch (caught) {
		const error = resolveImageError(run, caught);
		run.logger.warn("图片流式生成失败", {
			provider: run.provider,
			model: run.model,
			traceId: run.traceId,
			category: error.category,
		});
		yield sequencer.build({
			type: "error",
			error: error.info,
		});
	} finally {
		controller.abort();
	}
}

function wakeReader(state: StreamCallState): void {
	const notify = state.notify;
	state.notify = null;

	if (notify) {
		notify();
	}
}

/**
 * 把图片字节转为契约 JSON 的 base64 字符串，用于序列化 GeneratedImage
 */
export function serializeGeneratedImage(image: GeneratedImage): Record<string, unknown> {
	return {
		data: Buffer.from(image.data).toString("base64"),
		mimeType: image.mimeType,
		url: image.url,
		revisedPrompt: image.revisedPrompt,
	};
}

/**
 * 把 ImageResponse 转为契约 JSON 结构，图片字节输出为 base64
 */
export function serializeImageResponse(response: ImageResponse): Record<string, unknown> {
	const images: Record<string, unknown>[] = [];

	for (const image of response.images) {
		images.push(serializeGeneratedImage(image));
	}

	return {
		...response,
		images,
	};
}

/**
 * 把 ImageEvent 转为契约 JSON 结构，图片字节输出为 base64
 */
export function serializeImageEvent(event: ImageEvent): Record<string, unknown> {
	if (event.type === "partial_image") {
		return {
			...event,
			data: Buffer.from(event.data).toString("base64"),
		};
	}

	if (event.type === "image_completed") {
		return {
			...event,
			image: serializeGeneratedImage(event.image),
		};
	}

	if (event.type === "completed") {
		return {
			...event,
			response: serializeImageResponse(event.response),
		};
	}

	return {
		...event,
	};
}

/**
 * 把预检错误包装为只会抛出该错误的流，使 stream() 以 error 事件结束而不是同步抛出
 */
export async function* createFailedImageStream(error: unknown): AsyncGenerator<ImageStreamItem> {
	throw error;
}

export function buildNoImageError(provider: string, finishReason: string | null): CommonLlmError {
	return createError("PROVIDER_ERROR", `Gemini 没有返回图片，finishReason=${finishReason ?? "unknown"}`, {
		provider,
		vendorCode: finishReason,
		retriable: false,
	});
}
