/**
 * 在统一契约与 OpenAI Chat Completions 的请求、响应、流式分片之间转换
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

import {
	readArray,
	readNumber,
	readRecord,
	readString,
	toPlainRecord,
} from "../../core/json.js";
import { buildModelResponse } from "../../core/model-support.js";
import { buildImageUrl } from "./responses-mapper.js";

import type { JsonRecord } from "../../core/json.js";
import type { ModelEventSequencer } from "../../core/model-support.js";
import type {
	FinishReason,
	Message,
	ModelEvent,
	ModelRequest,
	ModelResponse,
	ResponseContentPart,
	Usage,
} from "../../core/types.js";
import type { ResponseConversionContext } from "./responses-mapper.js";

interface ToolCallAccumulator {
	id: string;
	name: string;
	arguments: string;
}

// 这些字段由本库映射，providerOptions 不能重复指定
export const CHAT_MAPPED_KEYS: ReadonlySet<string> = new Set([
	"model",

	"messages",

	"temperature",

	"max_completion_tokens",

	"tools",

	"tool_choice",

	"response_format",

	"stream",

	"stream_options",
]);

/**
 * 组装 chat.completions.create 的请求体，system 消息保留为 system 角色
 */
export function buildChatBody(request: ModelRequest, model: string, extraBody: JsonRecord): JsonRecord {
	const body: JsonRecord = {
		...extraBody,
		model,
		messages: buildChatMessages(request.messages),
	};

	if (request.temperature !== undefined && request.temperature !== null) {
		body.temperature = request.temperature;
	}

	if (request.maxOutputTokens !== undefined && request.maxOutputTokens !== null) {
		body.max_completion_tokens = request.maxOutputTokens;
	}

	if (request.tools && request.tools.length > 0) {
		const tools: JsonRecord[] = [];

		for (const tool of request.tools) {
			tools.push({
				type: "function",
				function: {
					name: tool.name,
					description: tool.description ?? undefined,
					parameters: tool.parameters,
				},
			});
		}

		body.tools = tools;
	}

	if (request.toolChoice && request.toolChoice.type === "tool") {
		body.tool_choice = {
			type: "function",
			function: {
				name: request.toolChoice.name,
			},
		};
	} else if (request.toolChoice) {
		body.tool_choice = request.toolChoice.type;
	}

	if (request.responseFormat && request.responseFormat.type === "json_schema") {
		body.response_format = {
			type: "json_schema",
			json_schema: {
				name: request.responseFormat.name,
				schema: request.responseFormat.schema,
				strict: request.responseFormat.strict ?? undefined,
			},
		};
	} else if (request.responseFormat && request.responseFormat.type === "text") {
		body.response_format = {
			type: "text",
		};
	}

	return body;
}

function buildChatMessages(messages: readonly Message[]): JsonRecord[] {
	const chatMessages: JsonRecord[] = [];

	for (const message of messages) {
		if (message.role === "tool") {
			for (const part of message.content) {
				if (part.type !== "tool_result") {
					continue;
				}

				chatMessages.push({
					role: "tool",
					tool_call_id: part.toolCallId,
					content: part.content,
				});
			}

			continue;
		}

		if (message.role === "assistant") {
			chatMessages.push(buildAssistantMessage(message));
			continue;
		}

		chatMessages.push({
			role: message.role,
			content: buildChatContent(message),
		});
	}

	return chatMessages;
}

function buildChatContent(message: Message): string | JsonRecord[] {
	const hasImage = message.content.some((part) => part.type === "image");

	if (!hasImage) {
		const texts: string[] = [];

		for (const part of message.content) {
			if (part.type === "text") {
				texts.push(part.text);
			}
		}

		return texts.join("");
	}

	const content: JsonRecord[] = [];

	for (const part of message.content) {
		if (part.type === "text") {
			content.push({
				type: "text",
				text: part.text,
			});
			continue;
		}

		if (part.type === "image") {
			content.push({
				type: "image_url",
				image_url: {
					url: buildImageUrl(part.url, part.base64, part.mimeType),
				},
			});
		}
	}

	return content;
}

function buildAssistantMessage(message: Message): JsonRecord {
	const texts: string[] = [];
	const toolCalls: JsonRecord[] = [];

	for (const part of message.content) {
		if (part.type === "text") {
			texts.push(part.text);
			continue;
		}

		if (part.type === "tool_call") {
			toolCalls.push({
				id: part.id,
				type: "function",
				function: {
					name: part.name,
					arguments: part.arguments,
				},
			});
		}
	}

	const assistantMessage: JsonRecord = {
		role: "assistant",
		content: texts.length > 0 ? texts.join("") : null,
	};

	if (toolCalls.length > 0) {
		assistantMessage.tool_calls = toolCalls;
	}

	return assistantMessage;
}

export function convertChatFinishReason(finishReason: string | null): FinishReason {
	if (finishReason === "stop") {
		return "stop";
	}

	if (finishReason === "length") {
		return "length";
	}

	if (finishReason === "tool_calls" || finishReason === "function_call") {
		return "tool_calls";
	}

	if (finishReason === "content_filter") {
		return "content_filter";
	}

	return "other";
}

export function convertChatUsage(rawUsage: unknown): Usage {
	const promptDetails = readRecord(rawUsage, "prompt_tokens_details");
	const completionDetails = readRecord(rawUsage, "completion_tokens_details");

	return {
		inputTokens: readNumber(rawUsage, "prompt_tokens"),
		outputTokens: readNumber(rawUsage, "completion_tokens"),
		cachedInputTokens: readNumber(promptDetails, "cached_tokens"),
		reasoningTokens: readNumber(completionDetails, "reasoning_tokens"),
		totalTokens: readNumber(rawUsage, "total_tokens"),
	};
}

/**
 * 把 ChatCompletion 对象转换为 ModelResponse，只取第一个 choice
 */
export function convertChatCompletion(completion: unknown, context: ResponseConversionContext): ModelResponse {
	const choice = readArray(completion, "choices")[0];
	const message = readRecord(choice, "message");
	const contentParts: ResponseContentPart[] = [];
	const content = readString(message, "content");

	if (content) {
		contentParts.push({
			type: "text",
			text: content,
		});
	}

	for (const toolCall of readArray(message, "tool_calls")) {
		const toolFunction = readRecord(toolCall, "function");
		contentParts.push({
			type: "tool_call",
			id: readString(toolCall, "id") ?? "",
			name: readString(toolFunction, "name") ?? "",
			arguments: readString(toolFunction, "arguments") ?? "",
		});
	}

	const finishReason = readString(choice, "finish_reason");
	let rawResponse: JsonRecord | null = null;

	if (context.includeRawResponse) {
		rawResponse = toPlainRecord(completion);
	}

	return buildModelResponse({
		provider: context.provider,
		model: readString(completion, "model"),
		id: readString(completion, "id"),
		contentParts,
		finishReason: convertChatFinishReason(finishReason),
		vendorFinishReason: finishReason,
		usage: convertChatUsage(readRecord(completion, "usage")),
		traceId: context.traceId,
		requestId: context.requestId,
		rawResponse,
	});
}

/**
 * 把 ChatCompletionChunk 流转换为 ModelEvent，第一个分片发出 started，末尾 usage 分片的 choices 为空
 */
export async function* convertChatStream(
	chunks: AsyncIterable<unknown>,
	sequencer: ModelEventSequencer,
	context: ResponseConversionContext,
): AsyncGenerator<ModelEvent> {
	let model: string | null = null;
	let responseId: string | null = null;
	let finishReason: string | null = null;
	let usage: Usage | null = null;
	const textParts: string[] = [];
	const toolCalls: Map<number, ToolCallAccumulator> = new Map();
	const toolIndexes: Map<number, number> = new Map();

	for await (const chunk of chunks) {
		if (!sequencer.hasStarted) {
			model = readString(chunk, "model");
			responseId = readString(chunk, "id");
			yield sequencer.started(model, responseId);
		}

		const rawUsage = readRecord(chunk, "usage");

		if (Object.keys(rawUsage).length > 0) {
			usage = convertChatUsage(rawUsage);
		}

		for (const choice of readArray(chunk, "choices")) {
			const delta = readRecord(choice, "delta");
			const content = readString(delta, "content");

			if (content) {
				textParts.push(content);
				yield sequencer.textDelta(content);
			}

			for (const toolDelta of readArray(delta, "tool_calls")) {
				const vendorIndex = readNumber(toolDelta, "index") ?? 0;
				const toolFunction = readRecord(toolDelta, "function");
				const argumentsDelta = readString(toolFunction, "arguments") ?? "";
				const callId = readString(toolDelta, "id");
				const name = readString(toolFunction, "name");
				let toolIndex = toolIndexes.get(vendorIndex);

				if (toolIndex === undefined) {
					toolIndex = toolIndexes.size;
					toolIndexes.set(vendorIndex, toolIndex);
					toolCalls.set(toolIndex, {
						id: callId ?? "",
						name: name ?? "",
						arguments: "",
					});
				}

				const accumulator = toolCalls.get(toolIndex);

				if (accumulator) {
					accumulator.arguments += argumentsDelta;
				}

				if (callId !== null || name !== null || argumentsDelta !== "") {
					yield sequencer.toolCallDelta(toolIndex, callId, name, argumentsDelta);
				}
			}

			const choiceFinishReason = readString(choice, "finish_reason");

			if (choiceFinishReason) {
				finishReason = choiceFinishReason;
			}
		}
	}

	if (!sequencer.hasStarted) {
		return;
	}

	const contentParts: ResponseContentPart[] = [];
	const fullText = textParts.join("");

	if (fullText !== "") {
		contentParts.push({
			type: "text",
			text: fullText,
		});
	}

	const sortedIndexes = [
		...toolCalls.keys(),
	].sort((left, right) => left - right);

	for (const toolIndex of sortedIndexes) {
		const accumulator = toolCalls.get(toolIndex);

		if (!accumulator) {
			continue;
		}

		contentParts.push({
			type: "tool_call",
			id: accumulator.id,
			name: accumulator.name,
			arguments: accumulator.arguments,
		});
	}

	if (usage !== null) {
		yield sequencer.usage(usage);
	}

	yield sequencer.completed(buildModelResponse({
		provider: context.provider,
		model,
		id: responseId,
		contentParts,
		finishReason: convertChatFinishReason(finishReason),
		vendorFinishReason: finishReason,
		usage: usage ?? convertChatUsage(null),
		traceId: context.traceId,
		requestId: context.requestId,
	}));
}
