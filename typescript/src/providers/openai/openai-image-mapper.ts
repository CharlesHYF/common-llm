/**
 * 在统一图片契约与 OpenAI Images API、Responses image_generation 工具之间转换请求、响应与流式事件
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

import { toFile } from "openai";

import {
	buildImageDataUrl,
	decodeBase64Image,
	resolveImageExtension,
	resolveImageMimeType,
} from "../../core/image-support.js";
import { createError } from "../../core/errors.js";
import {
	isRecord,
	readArray,
	readNumber,
	readRecord,
	readString,
	toPlainRecord,
} from "../../core/json.js";
import { convertResponsesUsage } from "./responses-mapper.js";

import type { ImageStreamItem } from "../../core/image-support.js";
import type { JsonRecord } from "../../core/json.js";
import type {
	GeneratedImage,
	ImageInput,
	ImageRequest,
	ImageResponse,
	Usage,
} from "../../core/types.js";

export interface ImageConversionContext {
	provider: string;
	traceId: string;
	model: string;
	requestId: string | null;
	includeRawResponse: boolean;
}

// Images API 中由本库映射的字段，providerOptions 不能重复指定
export const IMAGES_MAPPED_KEYS: ReadonlySet<string> = new Set([
	"model",

	"prompt",

	"n",

	"size",

	"quality",

	"output_format",

	"output_compression",

	"background",

	"partial_images",

	"stream",

	"image",

	"mask",
]);

// Responses 图片路径中由本库映射的字段，providerOptions 不能重复指定
export const RESPONSES_IMAGE_MAPPED_KEYS: ReadonlySet<string> = new Set([
	"model",

	"input",

	"tools",

	"tool_choice",

	"stream",
]);

/**
 * 组装 images.generate / images.edit 的参数，有输入图片时为 edit，图片以 toFile 生成的 File 上传
 */
export async function buildImagesBody(request: ImageRequest, model: string, extraBody: JsonRecord, isStream: boolean): Promise<JsonRecord> {
	const body: JsonRecord = {
		...extraBody,
		model,
		prompt: request.prompt,
	};

	if (request.n !== undefined && request.n !== null) {
		body.n = request.n;
	}

	copyImageOptions(request, body, isStream);

	const images = request.images ?? [];

	if (images.length === 1 && images[0]) {
		body.image = await toUpload(images[0], "image-0");
	} else if (images.length > 1) {
		const uploads: unknown[] = [];
		let index = 0;

		for (const input of images) {
			uploads.push(await toUpload(input, `image-${index}`));
			index += 1;
		}

		body.image = uploads;
	}

	if (request.mask) {
		body.mask = await toUpload(request.mask, "mask");
	}

	if (isStream) {
		body.stream = true;
	}

	return body;
}

/**
 * 组装 responses.create 的参数：model 为对话模型，图片模型与图片参数放在 image_generation 工具中并强制使用该工具
 */
export function buildResponsesImageBody(request: ImageRequest, conversationModel: string, imageModel: string, extraBody: JsonRecord, isStream: boolean): JsonRecord {
	const tool: JsonRecord = {
		type: "image_generation",
		model: imageModel,
	};
	copyImageOptions(request, tool, isStream);

	if (request.mask) {
		tool.input_image_mask = {
			image_url: buildImageDataUrl(request.mask),
		};
	}

	const content: JsonRecord[] = [
		{
			type: "input_text",
			text: request.prompt,
		},
	];

	for (const input of request.images ?? []) {
		content.push({
			type: "input_image",
			image_url: buildImageDataUrl(input),
			detail: "auto",
		});
	}

	const body: JsonRecord = {
		...extraBody,
		model: conversationModel,
		input: [
			{
				role: "user",
				content,
			},
		],
		tools: [
			tool,
		],
		tool_choice: {
			type: "image_generation",
		},
	};

	if (isStream) {
		body.stream = true;
	}

	return body;
}

function copyImageOptions(request: ImageRequest, target: JsonRecord, isStream: boolean): void {
	if (request.size) {
		target.size = request.size;
	}

	if (request.quality) {
		target.quality = request.quality;
	}

	if (request.outputFormat) {
		target.output_format = request.outputFormat;
	}

	if (request.outputCompression !== undefined && request.outputCompression !== null) {
		target.output_compression = request.outputCompression;
	}

	if (request.background) {
		target.background = request.background;
	}

	if (isStream && request.partialImages !== undefined && request.partialImages !== null) {
		target.partial_images = request.partialImages;
	}
}

async function toUpload(input: ImageInput, baseName: string): Promise<unknown> {
	const mimeType = input.mimeType ?? "image/png";

	return toFile(Buffer.from(input.base64 ?? "", "base64"), `${baseName}.${resolveImageExtension(mimeType)}`, {
		type: mimeType,
	});
}

/**
 * Images API 用量只有输入、输出与总量，缓存与推理用量为 null
 */
export function convertImagesUsage(rawUsage: unknown): Usage {
	return {
		inputTokens: readNumber(rawUsage, "input_tokens"),
		outputTokens: readNumber(rawUsage, "output_tokens"),
		cachedInputTokens: null,
		reasoningTokens: null,
		totalTokens: readNumber(rawUsage, "total_tokens"),
	};
}

function buildGeneratedImage(base64: string | null, mimeType: string, url: string | null, revisedPrompt: string | null): GeneratedImage {
	return {
		data: decodeBase64Image(base64 ?? ""),
		mimeType,
		url,
		revisedPrompt,
	};
}

/**
 * 把 ImagesResponse 转换为 ImageResponse，MIME 类型由 output_format 推出
 */
export function convertImagesResponse(response: unknown, context: ImageConversionContext): ImageResponse {
	const mimeType = resolveImageMimeType(readString(response, "output_format"));
	const images: GeneratedImage[] = [];

	for (const item of readArray(response, "data")) {
		images.push(buildGeneratedImage(readString(item, "b64_json"), mimeType, readString(item, "url"), readString(item, "revised_prompt")));
	}

	const imageResponse: ImageResponse = {
		provider: context.provider,
		model: context.model,
		id: null,
		images,
		text: "",
		usage: convertImagesUsage(readRecord(response, "usage")),
		traceId: context.traceId,
		requestId: context.requestId,
	};

	if (context.includeRawResponse) {
		imageResponse.rawResponse = toPlainRecord(response);
	}

	return imageResponse;
}

/**
 * 转换 Images API 流式事件：*.partial_image 为部分图片，*.completed 为完成的图片并携带最终用量
 */
export async function* convertImagesStream(events: AsyncIterable<unknown>, context: ImageConversionContext): AsyncGenerator<ImageStreamItem> {
	const images: GeneratedImage[] = [];
	let usage = convertImagesUsage(null);
	let hasUsage = false;

	yield {
		kind: "started",
		model: context.model,
		id: null,
	};

	for await (const event of events) {
		const eventType = readString(event, "type") ?? "";
		const mimeType = resolveImageMimeType(readString(event, "output_format"));

		if (eventType.endsWith(".partial_image")) {
			yield {
				kind: "partial_image",
				imageIndex: images.length,
				partialIndex: readNumber(event, "partial_image_index") ?? 0,
				data: decodeBase64Image(readString(event, "b64_json") ?? ""),
				mimeType,
			};
			continue;
		}

		if (eventType.endsWith(".completed")) {
			const image = buildGeneratedImage(readString(event, "b64_json"), mimeType, null, null);
			yield {
				kind: "image_completed",
				imageIndex: images.length,
				image,
			};
			images.push(image);

			const rawUsage = readRecord(event, "usage");

			if (Object.keys(rawUsage).length > 0) {
				usage = convertImagesUsage(rawUsage);
				hasUsage = true;
			}

			continue;
		}

		if (eventType === "error") {
			throw buildStreamError(event, context.provider);
		}
	}

	yield {
		kind: "response",
		hasUsage,
		response: {
			provider: context.provider,
			model: context.model,
			id: null,
			images,
			text: "",
			usage,
			traceId: context.traceId,
			requestId: context.requestId,
		},
	};
}

/**
 * 把 Responses 的 Response 对象转换为 ImageResponse：image_generation_call 为图片，message 文本为 text
 */
export function convertResponsesImageResponse(response: unknown, context: ImageConversionContext): ImageResponse {
	const images: GeneratedImage[] = [];
	const textParts: string[] = [];

	for (const outputItem of readArray(response, "output")) {
		const itemType = readString(outputItem, "type");

		if (itemType === "image_generation_call" && readString(outputItem, "result")) {
			images.push(convertImageGenerationCall(outputItem));
			continue;
		}

		if (itemType !== "message") {
			continue;
		}

		for (const content of readArray(outputItem, "content")) {
			if (readString(content, "type") === "output_text") {
				textParts.push(readString(content, "text") ?? "");
			}
		}
	}

	const imageResponse: ImageResponse = {
		provider: context.provider,
		model: context.model,
		id: readString(response, "id"),
		images,
		text: textParts.join(""),
		usage: convertResponsesUsage(readRecord(response, "usage")),
		traceId: context.traceId,
		requestId: context.requestId,
	};

	if (context.includeRawResponse) {
		imageResponse.rawResponse = toPlainRecord(response);
	}

	return imageResponse;
}

function convertImageGenerationCall(item: unknown): GeneratedImage {
	return buildGeneratedImage(readString(item, "result"), resolveImageMimeType(readString(item, "output_format")), null, readString(item, "revised_prompt"));
}

/**
 * 转换 Responses 图片流：按 image_generation_call 出现顺序编号，response.completed 给出最终响应
 */
export async function* convertResponsesImageStream(events: AsyncIterable<unknown>, context: ImageConversionContext): AsyncGenerator<ImageStreamItem> {
	const imageIndexes: Map<string, number> = new Map();

	for await (const event of events) {
		const eventType = readString(event, "type");
		const item = readRecord(event, "item");

		if (eventType === "response.created") {
			yield {
				kind: "started",
				model: context.model,
				id: readString(readRecord(event, "response"), "id"),
			};
			continue;
		}

		if (eventType === "response.output_item.added" && readString(item, "type") === "image_generation_call") {
			imageIndexes.set(readString(item, "id") ?? "", imageIndexes.size);
			continue;
		}

		if (eventType === "response.image_generation_call.partial_image") {
			yield {
				kind: "partial_image",
				imageIndex: imageIndexes.get(readString(event, "item_id") ?? "") ?? 0,
				partialIndex: readNumber(event, "partial_image_index") ?? 0,
				data: decodeBase64Image(readString(event, "partial_image_b64") ?? ""),
				mimeType: resolveImageMimeType(readString(event, "output_format")),
			};
			continue;
		}

		if (eventType === "response.output_item.done" && readString(item, "type") === "image_generation_call") {
			yield {
				kind: "image_completed",
				imageIndex: imageIndexes.get(readString(item, "id") ?? "") ?? imageIndexes.size,
				image: convertImageGenerationCall(item),
			};
			continue;
		}

		if (eventType === "response.completed" || eventType === "response.incomplete") {
			const rawResponse = readRecord(event, "response");
			const response = convertResponsesImageResponse(rawResponse, {
				...context,
				includeRawResponse: false,
			});

			yield {
				kind: "response",
				hasUsage: isRecord(rawResponse.usage),
				response,
			};
			return;
		}

		if (eventType === "response.failed" || eventType === "error") {
			throw buildStreamError(event, context.provider);
		}
	}
}

function buildStreamError(event: unknown, provider: string): Error {
	const responseError = readRecord(readRecord(event, "response"), "error");
	const nestedError = readRecord(event, "error");
	const code = readString(responseError, "code") ?? readString(nestedError, "code") ?? readString(event, "code");
	const message = readString(responseError, "message") ?? readString(nestedError, "message") ?? readString(event, "message") ?? "OpenAI 图片流式响应失败";

	return createError("PROVIDER_ERROR", message, {
		provider,
		vendorCode: code,
	});
}
