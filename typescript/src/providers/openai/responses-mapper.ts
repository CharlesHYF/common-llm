/**
 * 在统一契约与 OpenAI Responses API 的请求、响应、流式事件之间转换
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

import { createError } from "../../core/errors.js";
import {
	isRecord,
	readArray,
	readNumber,
	readRecord,
	readString,
	toPlainRecord,
} from "../../core/json.js";
import { buildModelResponse, collectSystemText } from "../../core/model-support.js";

import type { JsonRecord } from "../../core/json.js";
import type { ModelEventSequencer } from "../../core/model-support.js";
import type {
	ContentPart,
	FinishReason,
	ModelEvent,
	ModelRequest,
	ModelResponse,
	ResponseContentPart,
	Usage,
} from "../../core/types.js";

export interface ResponseConversionContext {
	provider: string;
	traceId: string;
	requestId: string | null;
	includeRawResponse: boolean;
}

// 这些字段由本库映射，providerOptions 不能重复指定
export const RESPONSES_MAPPED_KEYS: ReadonlySet<string> = new Set([
	"model",

	"input",

	"instructions",

	"temperature",

	"max_output_tokens",

	"tools",

	"tool_choice",

	"text",

	"stream",
]);

/**
 * 组装 responses.create 的请求体，system 消息映射为 instructions
 */
export function buildResponsesBody(request: ModelRequest, model: string, extraBody: JsonRecord): JsonRecord {
	const body: JsonRecord = {
		...extraBody,
		model,
		input: buildResponsesInput(request),
	};
	const instructions = collectSystemText(request.messages);

	if (instructions !== null) {
		body.instructions = instructions;
	}

	if (request.temperature !== undefined && request.temperature !== null) {
		body.temperature = request.temperature;
	}

	if (request.maxOutputTokens !== undefined && request.maxOutputTokens !== null) {
		body.max_output_tokens = request.maxOutputTokens;
	}

	if (request.tools && request.tools.length > 0) {
		const tools: JsonRecord[] = [];

		for (const tool of request.tools) {
			tools.push({
				type: "function",
				name: tool.name,
				description: tool.description ?? undefined,
				parameters: tool.parameters,
				strict: false,
			});
		}

		body.tools = tools;
	}

	if (request.toolChoice) {
		body.tool_choice = buildResponsesToolChoice(request.toolChoice);
	}

	if (request.responseFormat && request.responseFormat.type === "json_schema") {
		body.text = {
			format: {
				type: "json_schema",
				name: request.responseFormat.name,
				schema: request.responseFormat.schema,
				strict: request.responseFormat.strict ?? undefined,
			},
		};
	} else if (request.responseFormat && request.responseFormat.type === "text") {
		body.text = {
			format: {
				type: "text",
			},
		};
	}

	return body;
}

function buildResponsesToolChoice(toolChoice: NonNullable<ModelRequest["toolChoice"]>): unknown {
	if (toolChoice.type === "tool") {
		return {
			type: "function",
			name: toolChoice.name,
		};
	}

	return toolChoice.type;
}

function buildResponsesInput(request: ModelRequest): JsonRecord[] {
	const items: JsonRecord[] = [];

	for (const message of request.messages) {
		if (message.role === "system") {
			continue;
		}

		if (message.role === "user") {
			items.push({
				role: "user",
				content: buildUserContent(message.content),
			});
			continue;
		}

		if (message.role === "assistant") {
			appendAssistantItems(message.content, items);
			continue;
		}

		for (const part of message.content) {
			if (part.type !== "tool_result") {
				continue;
			}

			items.push({
				type: "function_call_output",
				call_id: part.toolCallId,
				output: part.content,
			});
		}
	}

	return items;
}

function buildUserContent(parts: readonly ContentPart[]): JsonRecord[] {
	const content: JsonRecord[] = [];

	for (const part of parts) {
		if (part.type === "text") {
			content.push({
				type: "input_text",
				text: part.text,
			});
			continue;
		}

		if (part.type === "image") {
			content.push({
				type: "input_image",
				image_url: buildImageUrl(part.url, part.base64, part.mimeType),
				detail: "auto",
			});
		}
	}

	return content;
}

function appendAssistantItems(parts: readonly ContentPart[], items: JsonRecord[]): void {
	const texts: string[] = [];

	for (const part of parts) {
		if (part.type === "text") {
			texts.push(part.text);
		}
	}

	if (texts.length > 0) {
		items.push({
			role: "assistant",
			content: texts.join(""),
		});
	}

	for (const part of parts) {
		if (part.type !== "tool_call") {
			continue;
		}

		items.push({
			type: "function_call",
			call_id: part.id,
			name: part.name,
			arguments: part.arguments,
		});
	}
}

export function buildImageUrl(url: string | undefined, base64: string | undefined, mimeType: string | undefined): string {
	if (url) {
		return url;
	}

	return `data:${mimeType ?? ""};base64,${base64 ?? ""}`;
}

export function convertResponsesUsage(rawUsage: unknown): Usage {
	const inputDetails = readRecord(rawUsage, "input_tokens_details");
	const outputDetails = readRecord(rawUsage, "output_tokens_details");

	return {
		inputTokens: readNumber(rawUsage, "input_tokens"),
		outputTokens: readNumber(rawUsage, "output_tokens"),
		cachedInputTokens: readNumber(inputDetails, "cached_tokens"),
		reasoningTokens: readNumber(outputDetails, "reasoning_tokens"),
		totalTokens: readNumber(rawUsage, "total_tokens"),
	};
}

/**
 * 把 Responses API 的 Response 对象转换为 ModelResponse
 */
export function convertResponsesResponse(response: unknown, context: ResponseConversionContext): ModelResponse {
	const contentParts: ResponseContentPart[] = [];
	let hasToolCall = false;

	for (const outputItem of readArray(response, "output")) {
		const itemType = readString(outputItem, "type");

		if (itemType === "message") {
			for (const content of readArray(outputItem, "content")) {
				if (readString(content, "type") === "output_text") {
					contentParts.push({
						type: "text",
						text: readString(content, "text") ?? "",
					});
				}
			}

			continue;
		}

		if (itemType === "function_call") {
			hasToolCall = true;
			contentParts.push({
				type: "tool_call",
				id: readString(outputItem, "call_id") ?? "",
				name: readString(outputItem, "name") ?? "",
				arguments: readString(outputItem, "arguments") ?? "",
			});
		}
	}

	const status = readString(response, "status");
	const incompleteReason = readString(readRecord(response, "incomplete_details"), "reason");
	let vendorFinishReason = status;
	let finishReason: FinishReason = "other";

	if (hasToolCall) {
		finishReason = "tool_calls";
	} else if (status === "completed") {
		finishReason = "stop";
	} else if (status === "incomplete") {
		vendorFinishReason = incompleteReason;
		finishReason = convertIncompleteReason(incompleteReason);
	}

	let rawResponse: JsonRecord | null = null;

	if (context.includeRawResponse) {
		rawResponse = toPlainRecord(response);
	}

	return buildModelResponse({
		provider: context.provider,
		model: readString(response, "model"),
		id: readString(response, "id"),
		contentParts,
		finishReason,
		vendorFinishReason,
		usage: convertResponsesUsage(readRecord(response, "usage")),
		traceId: context.traceId,
		requestId: context.requestId,
		rawResponse,
	});
}

function convertIncompleteReason(reason: string | null): FinishReason {
	if (reason === "max_output_tokens") {
		return "length";
	}

	if (reason === "content_filter") {
		return "content_filter";
	}

	return "other";
}

/**
 * 把 Responses API 流式事件转换为 ModelEvent，response.completed 中的完整响应作为 completed.response
 */
export async function* convertResponsesStream(
	events: AsyncIterable<unknown>,
	sequencer: ModelEventSequencer,
	context: ResponseConversionContext,
): AsyncGenerator<ModelEvent> {
	const toolIndexes: Map<string, number> = new Map();

	for await (const event of events) {
		const eventType = readString(event, "type");

		if (eventType === "response.created") {
			const response = readRecord(event, "response");
			yield sequencer.started(readString(response, "model"), readString(response, "id"));
			continue;
		}

		if (!sequencer.hasStarted) {
			yield sequencer.started(null, null);
		}

		if (eventType === "response.output_text.delta") {
			const delta = readString(event, "delta") ?? "";

			if (delta !== "") {
				yield sequencer.textDelta(delta);
			}

			continue;
		}

		if (eventType === "response.output_item.added") {
			const item = readRecord(event, "item");

			if (readString(item, "type") !== "function_call") {
				continue;
			}

			const toolIndex = toolIndexes.size;
			toolIndexes.set(readString(item, "id") ?? String(toolIndex), toolIndex);
			yield sequencer.toolCallDelta(toolIndex, readString(item, "call_id"), readString(item, "name"), readString(item, "arguments") ?? "");
			continue;
		}

		if (eventType === "response.function_call_arguments.delta") {
			const delta = readString(event, "delta") ?? "";
			const toolIndex = toolIndexes.get(readString(event, "item_id") ?? "");

			if (delta !== "" && toolIndex !== undefined) {
				yield sequencer.toolCallDelta(toolIndex, null, null, delta);
			}

			continue;
		}

		if (eventType === "response.completed" || eventType === "response.incomplete") {
			const rawResponse = readRecord(event, "response");
			const response = convertResponsesResponse(rawResponse, context);

			if (isRecord(rawResponse.usage)) {
				yield sequencer.usage(response.usage);
			}

			yield sequencer.completed(response);
			return;
		}

		if (eventType === "response.failed" || eventType === "error") {
			throw buildStreamFailure(event, context.provider);
		}
	}
}

function buildStreamFailure(event: unknown, provider: string): Error {
	const responseError = readRecord(readRecord(event, "response"), "error");
	const code = readString(responseError, "code") ?? readString(event, "code");
	const message = readString(responseError, "message") ?? readString(event, "message") ?? "OpenAI 流式响应失败";

	return createError("PROVIDER_ERROR", message, {
		provider,
		vendorCode: code,
	});
}
