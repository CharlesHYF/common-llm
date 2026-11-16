/**
 * 在统一图片契约与 Gemini generateContent 原生出图之间转换请求、响应与流式分块
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

import { buildNoImageError, decodeBase64Image } from "../../core/image-support.js";
import {
	isRecord,
	readArray,
	readRecord,
	readString,
	toPlainRecord,
} from "../../core/json.js";
import { convertGeminiUsage } from "./gemini-mapper.js";

import type { ImageStreamItem } from "../../core/image-support.js";
import type { JsonRecord } from "../../core/json.js";
import type { GeneratedImage, ImageRequest, ImageResponse } from "../../core/types.js";

export interface GeminiImageContext {
	provider: string;
	traceId: string;
	includeRawResponse: boolean;
}

interface CandidateImages {
	images: GeneratedImage[];
	text: string;
	finishReason: string | null;
}

// providerOptions 经 httpOptions.extraBody 合并进请求体时不能覆盖这些字段
export const GEMINI_IMAGE_MAPPED_KEYS: ReadonlySet<string> = new Set([
	"contents",

	"generationConfig",
]);

/**
 * 组装 contents 与 config：prompt 为文本 part，输入图片为 inlineData part，要求同时返回文本与图片
 */
export function buildGeminiImageRequest(request: ImageRequest): {
	contents: JsonRecord[];
	config: JsonRecord;
} {
	const parts: JsonRecord[] = [
		{
			text: request.prompt,
		},
	];

	for (const input of request.images ?? []) {
		parts.push({
			inlineData: {
				mimeType: input.mimeType,
				data: input.base64,
			},
		});
	}

	const config: JsonRecord = {
		responseModalities: [
			"TEXT",

			"IMAGE",
		],
	};
	const imageConfig: JsonRecord = {};

	if (request.aspectRatio) {
		imageConfig.aspectRatio = request.aspectRatio;
	}

	if (request.imageSize) {
		imageConfig.imageSize = request.imageSize;
	}

	if (Object.keys(imageConfig).length > 0) {
		config.imageConfig = imageConfig;
	}

	return {
		contents: [
			{
				role: "user",
				parts,
			},
		],
		config,
	};
}

/**
 * 提取首个候选中的图片与文本，跳过 thought 部分
 */
function collectCandidateImages(response: unknown): CandidateImages {
	const images: GeneratedImage[] = [];
	const textParts: string[] = [];
	const candidate = readArray(response, "candidates")[0];

	if (!isRecord(candidate)) {
		return {
			images,
			text: "",
			finishReason: null,
		};
	}

	for (const part of readArray(readRecord(candidate, "content"), "parts")) {
		if (!isRecord(part) || part.thought === true) {
			continue;
		}

		if (isRecord(part.inlineData)) {
			images.push({
				data: decodeBase64Image(readString(part.inlineData, "data") ?? ""),
				mimeType: readString(part.inlineData, "mimeType") ?? "image/png",
				url: null,
				revisedPrompt: null,
			});
			continue;
		}

		if (typeof part.text === "string") {
			textParts.push(part.text);
		}
	}

	return {
		images,
		text: textParts.join(""),
		finishReason: readString(candidate, "finishReason"),
	};
}

/**
 * 转换 GenerateContentResponse；响应中没有图片时报 PROVIDER_ERROR，vendorCode 为 finishReason
 */
export function convertGeminiImageResponse(response: unknown, context: GeminiImageContext): ImageResponse {
	const candidate = collectCandidateImages(response);

	if (candidate.images.length === 0) {
		throw buildNoImageError(context.provider, candidate.finishReason);
	}

	const imageResponse: ImageResponse = {
		provider: context.provider,
		model: readString(response, "modelVersion"),
		id: readString(response, "responseId"),
		images: candidate.images,
		text: candidate.text,
		usage: convertGeminiUsage(readRecord(response, "usageMetadata")),
		traceId: context.traceId,
		requestId: null,
	};

	if (context.includeRawResponse) {
		imageResponse.rawResponse = toPlainRecord(response);
	}

	return imageResponse;
}

/**
 * 转换 generateContentStream 分块：含 inlineData 的分块产生 image_completed，不产生部分图片
 */
export async function* convertGeminiImageStream(chunks: AsyncIterable<unknown>, context: GeminiImageContext): AsyncGenerator<ImageStreamItem> {
	const images: GeneratedImage[] = [];
	const textParts: string[] = [];
	let model: string | null = null;
	let responseId: string | null = null;
	let finishReason: string | null = null;
	let rawUsage: JsonRecord | null = null;
	let hasStarted = false;

	for await (const chunk of chunks) {
		if (!hasStarted) {
			hasStarted = true;
			model = readString(chunk, "modelVersion");
			responseId = readString(chunk, "responseId");
			yield {
				kind: "started",
				model,
				id: responseId,
			};
		}

		const candidate = collectCandidateImages(chunk);
		textParts.push(candidate.text);

		for (const image of candidate.images) {
			yield {
				kind: "image_completed",
				imageIndex: images.length,
				image,
			};
			images.push(image);
		}

		if (candidate.finishReason) {
			finishReason = candidate.finishReason;
		}

		const chunkUsage = readRecord(chunk, "usageMetadata");

		if (Object.keys(chunkUsage).length > 0) {
			rawUsage = chunkUsage;
		}
	}

	if (images.length === 0) {
		throw buildNoImageError(context.provider, finishReason);
	}

	yield {
		kind: "response",
		hasUsage: rawUsage !== null,
		response: {
			provider: context.provider,
			model,
			id: responseId,
			images,
			text: textParts.join(""),
			usage: convertGeminiUsage(rawUsage),
			traceId: context.traceId,
			requestId: null,
		},
	};
}
