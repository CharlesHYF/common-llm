/**
 * 在统一契约与 Gemini generateContent 的请求、响应、流式分片之间转换
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

import {
	compactJson,
	isRecord,
	readArray,
	readNumber,
	readRecord,
	readString,
	toPlainRecord,
} from "../../core/json.js";
import { buildModelResponse, collectSystemText, parseToolArguments } from "../../core/model-support.js";

import type { JsonRecord } from "../../core/json.js";
import type { ModelEventSequencer } from "../../core/model-support.js";
import type {
	FinishReason,
	Message,
	ModelEvent,
	ModelRequest,
	ModelResponse,
	ResponseContentPart,
	ToolCallPart,
	Usage,
} from "../../core/types.js";

export interface GeminiConversionContext {
	provider: string;
	traceId: string;
	includeRawResponse: boolean;
}

export interface GeminiRequestParts {
	contents: JsonRecord[];
	config: JsonRecord;
}

interface CandidateParts {
	contentParts: ResponseContentPart[];
	finishReason: string | null;
}

// 这些字段由本库映射，providerOptions 经 httpOptions.extraBody 透传时不能重复指定
export const GEMINI_MAPPED_KEYS: ReadonlySet<string> = new Set([
	"contents",

	"systemInstruction",

	"generationConfig",

	"tools",

	"toolConfig",
]);

const GEMINI_GENERATED_CALL_ID_PREFIX = "gemini_call_";

const GEMINI_CONTENT_FILTER_REASONS: ReadonlySet<string> = new Set([
	"SAFETY",

	"RECITATION",

	"BLOCKLIST",

	"PROHIBITED_CONTENT",

	"SPII",

	"IMAGE_SAFETY",
]);

/**
 * 组装 models.generateContent 的 contents 与 config，system 消息映射为 systemInstruction
 */
export function buildGeminiRequest(request: ModelRequest, provider: string): GeminiRequestParts {
	const config: JsonRecord = {};
	const systemText = collectSystemText(request.messages);

	if (systemText !== null) {
		config.systemInstruction = systemText;
	}

	if (request.temperature !== undefined && request.temperature !== null) {
		config.temperature = request.temperature;
	}

	if (request.maxOutputTokens !== undefined && request.maxOutputTokens !== null) {
		config.maxOutputTokens = request.maxOutputTokens;
	}

	if (request.tools && request.tools.length > 0) {
		const functionDeclarations: JsonRecord[] = [];

		for (const tool of request.tools) {
			functionDeclarations.push({
				name: tool.name,
				description: tool.description ?? undefined,
				parametersJsonSchema: tool.parameters,
			});
		}

		config.tools = [
			{
				functionDeclarations,
			},
		];
	}

	if (request.toolChoice) {
		config.toolConfig = {
			functionCallingConfig: buildFunctionCallingConfig(request.toolChoice),
		};
	}

	if (request.responseFormat && request.responseFormat.type === "json_schema") {
		config.responseMimeType = "application/json";
		config.responseJsonSchema = request.responseFormat.schema;
	} else if (request.responseFormat && request.responseFormat.type === "text") {
		config.responseMimeType = "text/plain";
	}

	return {
		contents: buildGeminiContents(request.messages, provider),
		config,
	};
}

function buildFunctionCallingConfig(toolChoice: NonNullable<ModelRequest["toolChoice"]>): JsonRecord {
	if (toolChoice.type === "tool") {
		return {
			mode: "ANY",
			allowedFunctionNames: [
				toolChoice.name,
			],
		};
	}

	if (toolChoice.type === "required") {
		return {
			mode: "ANY",
		};
	}

	if (toolChoice.type === "none") {
		return {
			mode: "NONE",
		};
	}

	return {
		mode: "AUTO",
	};
}

function buildGeminiContents(messages: readonly Message[], provider: string): JsonRecord[] {
	const contents: JsonRecord[] = [];
	const toolNames: Map<string, string> = new Map();

	for (const message of messages) {
		if (message.role === "system") {
			continue;
		}

		const parts: JsonRecord[] = [];

		for (const part of message.content) {
			if (part.type === "text") {
				parts.push({
					text: part.text,
				});
				continue;
			}

			if (part.type === "image") {
				parts.push(buildImagePart(part.url, part.base64, part.mimeType));
				continue;
			}

			if (part.type === "tool_call") {
				toolNames.set(part.id, part.name);
				parts.push({
					functionCall: buildFunctionCall(part, provider),
				});
				continue;
			}

			parts.push({
				functionResponse: {
					id: part.toolCallId,
					name: toolNames.get(part.toolCallId) ?? part.toolCallId,
					response: part.isError === true
						? {
							error: part.content,
						}
						: {
							output: part.content,
						},
				},
			});
		}

		contents.push({
			role: message.role === "assistant" ? "model" : "user",
			parts,
		});
	}

	return contents;
}

function buildFunctionCall(part: ToolCallPart, provider: string): JsonRecord {
	const functionCall: JsonRecord = {
		name: part.name,
		args: parseToolArguments(part.arguments, provider),
	};

	if (!part.id.startsWith(GEMINI_GENERATED_CALL_ID_PREFIX)) {
		functionCall.id = part.id;
	}

	return functionCall;
}

function buildImagePart(url: string | undefined, base64: string | undefined, mimeType: string | undefined): JsonRecord {
	if (url) {
		return {
			fileData: {
				fileUri: url,
				mimeType,
			},
		};
	}

	return {
		inlineData: {
			mimeType,
			data: base64,
		},
	};
}

export function convertGeminiUsage(rawUsage: unknown): Usage {
	return {
		inputTokens: readNumber(rawUsage, "promptTokenCount"),
		outputTokens: readNumber(rawUsage, "candidatesTokenCount"),
		cachedInputTokens: readNumber(rawUsage, "cachedContentTokenCount"),
		reasoningTokens: readNumber(rawUsage, "thoughtsTokenCount"),
		totalTokens: readNumber(rawUsage, "totalTokenCount"),
	};
}

export function convertGeminiFinishReason(finishReason: string | null, hasToolCall: boolean): FinishReason {
	if (hasToolCall) {
		return "tool_calls";
	}

	if (finishReason === "STOP") {
		return "stop";
	}

	if (finishReason === "MAX_TOKENS") {
		return "length";
	}

	if (finishReason !== null && GEMINI_CONTENT_FILTER_REASONS.has(finishReason)) {
		return "content_filter";
	}

	return "other";
}

/**
 * 提取首个候选的文本与函数调用，跳过 thought 部分；缺少 id 的函数调用按出现顺序生成 id
 */
function collectCandidateParts(response: unknown, toolOffset: number): CandidateParts {
	const contentParts: ResponseContentPart[] = [];
	const candidate = readArray(response, "candidates")[0];

	if (!isRecord(candidate)) {
		return {
			contentParts,
			finishReason: null,
		};
	}

	let toolCount = toolOffset;

	for (const part of readArray(readRecord(candidate, "content"), "parts")) {
		if (!isRecord(part) || part.thought === true) {
			continue;
		}

		if (typeof part.text === "string") {
			contentParts.push({
				type: "text",
				text: part.text,
			});
			continue;
		}

		if (isRecord(part.functionCall)) {
			const functionCall = part.functionCall;
			const callId = readString(functionCall, "id") ?? `${GEMINI_GENERATED_CALL_ID_PREFIX}${toolCount}`;
			toolCount += 1;
			contentParts.push({
				type: "tool_call",
				id: callId,
				name: readString(functionCall, "name") ?? "",
				arguments: compactJson(readRecord(functionCall, "args")),
			});
		}
	}

	return {
		contentParts,
		finishReason: readString(candidate, "finishReason"),
	};
}

function hasToolCallPart(parts: readonly ResponseContentPart[]): boolean {
	return parts.some((part) => part.type === "tool_call");
}

/**
 * 把 GenerateContentResponse 转换为 ModelResponse，id 取 responseId，model 取 modelVersion
 */
export function convertGeminiResponse(response: unknown, context: GeminiConversionContext): ModelResponse {
	const candidateParts = collectCandidateParts(response, 0);
	let rawResponse: JsonRecord | null = null;

	if (context.includeRawResponse) {
		rawResponse = toPlainRecord(response);
	}

	return buildModelResponse({
		provider: context.provider,
		model: readString(response, "modelVersion"),
		id: readString(response, "responseId"),
		contentParts: candidateParts.contentParts,
		finishReason: convertGeminiFinishReason(candidateParts.finishReason, hasToolCallPart(candidateParts.contentParts)),
		vendorFinishReason: candidateParts.finishReason,
		usage: convertGeminiUsage(readRecord(response, "usageMetadata")),
		traceId: context.traceId,
		requestId: null,
		rawResponse,
	});
}

/**
 * 把 generateContentStream 的分片转换为 ModelEvent，函数调用以完整参数作为单个增量发出
 */
export async function* convertGeminiStream(
	chunks: AsyncIterable<unknown>,
	sequencer: ModelEventSequencer,
	context: GeminiConversionContext,
): AsyncGenerator<ModelEvent> {
	let model: string | null = null;
	let responseId: string | null = null;
	let finishReason: string | null = null;
	let rawUsage: JsonRecord | null = null;
	const textParts: string[] = [];
	const toolParts: ResponseContentPart[] = [];

	for await (const chunk of chunks) {
		if (!sequencer.hasStarted) {
			model = readString(chunk, "modelVersion");
			responseId = readString(chunk, "responseId");
			yield sequencer.started(model, responseId);
		}

		const candidateParts = collectCandidateParts(chunk, toolParts.length);

		for (const part of candidateParts.contentParts) {
			if (part.type === "text") {
				textParts.push(part.text);

				if (part.text !== "") {
					yield sequencer.textDelta(part.text);
				}

				continue;
			}

			yield sequencer.toolCallDelta(toolParts.length, part.id, part.name, part.arguments);
			toolParts.push(part);
		}

		if (candidateParts.finishReason) {
			finishReason = candidateParts.finishReason;
		}

		const chunkUsage = readRecord(chunk, "usageMetadata");

		if (Object.keys(chunkUsage).length > 0) {
			rawUsage = chunkUsage;
		}
	}

	if (!sequencer.hasStarted) {
		return;
	}

	const finalParts: ResponseContentPart[] = [];
	const fullText = textParts.join("");

	if (fullText !== "") {
		finalParts.push({
			type: "text",
			text: fullText,
		});
	}

	for (const toolPart of toolParts) {
		finalParts.push(toolPart);
	}

	const usage = convertGeminiUsage(rawUsage);

	if (rawUsage !== null) {
		yield sequencer.usage(usage);
	}

	yield sequencer.completed(buildModelResponse({
		provider: context.provider,
		model,
		id: responseId,
		contentParts: finalParts,
		finishReason: convertGeminiFinishReason(finishReason, toolParts.length > 0),
		vendorFinishReason: finishReason,
		usage,
		traceId: context.traceId,
		requestId: null,
	}));
}
