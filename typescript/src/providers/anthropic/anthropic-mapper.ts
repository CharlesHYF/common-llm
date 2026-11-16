/**
 * 在统一契约与 Anthropic Messages API 的请求、响应、流式事件之间转换
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

import {
	compactJson,
	readArray,
	readNumber,
	readRecord,
	readString,
	toPlainRecord,
} from "../../core/json.js";
import {
	buildModelResponse,
	collectSystemText,
	parseToolArguments,
	sumNullableCounts,
} from "../../core/model-support.js";

import type { JsonRecord } from "../../core/json.js";
import type { ModelEventSequencer } from "../../core/model-support.js";
import type {
	ContentPart,
	FinishReason,
	Message,
	ModelEvent,
	ModelRequest,
	ModelResponse,
	ResponseContentPart,
	Usage,
} from "../../core/types.js";

export interface AnthropicConversionContext {
	provider: string;
	traceId: string;
	requestId: string | null;
	includeRawResponse: boolean;
}

interface StreamBlock {
	part: ResponseContentPart;
	toolIndex: number | null;
}

// 这些字段由本库映射，providerOptions 不能重复指定
export const ANTHROPIC_MAPPED_KEYS: ReadonlySet<string> = new Set([
	"model",

	"messages",

	"system",

	"max_tokens",

	"tools",

	"tool_choice",

	"output_config",

	"stream",
]);

/**
 * 组装 messages.create 的请求体，system 消息映射为顶层 system，tool 消息映射为 user 角色的 tool_result
 */
export function buildAnthropicBody(request: ModelRequest, model: string, maxTokens: number, extraBody: JsonRecord): JsonRecord {
	const body: JsonRecord = {
		...extraBody,
		model,
		max_tokens: maxTokens,
		messages: buildAnthropicMessages(request.messages, request.provider),
	};
	const system = collectSystemText(request.messages);

	if (system !== null) {
		body.system = system;
	}

	if (request.tools && request.tools.length > 0) {
		const tools: JsonRecord[] = [];

		for (const tool of request.tools) {
			tools.push({
				name: tool.name,
				description: tool.description ?? undefined,
				input_schema: tool.parameters,
			});
		}

		body.tools = tools;
	}

	if (request.toolChoice) {
		body.tool_choice = buildAnthropicToolChoice(request.toolChoice);
	}

	if (request.responseFormat && request.responseFormat.type === "json_schema") {
		body.output_config = {
			format: {
				type: "json_schema",
				schema: request.responseFormat.schema,
			},
		};
	}

	return body;
}

function buildAnthropicToolChoice(toolChoice: NonNullable<ModelRequest["toolChoice"]>): JsonRecord {
	if (toolChoice.type === "tool") {
		return {
			type: "tool",
			name: toolChoice.name,
		};
	}

	if (toolChoice.type === "required") {
		return {
			type: "any",
		};
	}

	return {
		type: toolChoice.type,
	};
}

function buildAnthropicMessages(messages: readonly Message[], provider: string): JsonRecord[] {
	const anthropicMessages: JsonRecord[] = [];

	for (const message of messages) {
		if (message.role === "system") {
			continue;
		}

		const role = message.role === "assistant" ? "assistant" : "user";
		anthropicMessages.push({
			role,
			content: buildAnthropicContent(message.content, provider),
		});
	}

	return anthropicMessages;
}

function buildAnthropicContent(parts: readonly ContentPart[], provider: string): JsonRecord[] {
	const blocks: JsonRecord[] = [];

	for (const part of parts) {
		if (part.type === "text") {
			blocks.push({
				type: "text",
				text: part.text,
			});
			continue;
		}

		if (part.type === "image") {
			blocks.push({
				type: "image",
				source: buildImageSource(part.url, part.base64, part.mimeType),
			});
			continue;
		}

		if (part.type === "tool_call") {
			blocks.push({
				type: "tool_use",
				id: part.id,
				name: part.name,
				input: parseToolArguments(part.arguments, provider),
			});
			continue;
		}

		blocks.push({
			type: "tool_result",
			tool_use_id: part.toolCallId,
			content: part.content,
			is_error: part.isError === true,
		});
	}

	return blocks;
}

function buildImageSource(url: string | undefined, base64: string | undefined, mimeType: string | undefined): JsonRecord {
	if (url) {
		return {
			type: "url",
			url,
		};
	}

	return {
		type: "base64",
		media_type: mimeType,
		data: base64,
	};
}

export function convertAnthropicFinishReason(stopReason: string | null): FinishReason {
	if (stopReason === "end_turn" || stopReason === "stop_sequence") {
		return "stop";
	}

	if (stopReason === "max_tokens") {
		return "length";
	}

	if (stopReason === "tool_use") {
		return "tool_calls";
	}

	if (stopReason === "refusal") {
		return "content_filter";
	}

	return "other";
}

/**
 * inputTokens 统一为含缓存的总输入量：input + cache_creation + cache_read
 */
export function convertAnthropicUsage(rawUsage: unknown): Usage {
	const inputTokens = readNumber(rawUsage, "input_tokens");
	const outputTokens = readNumber(rawUsage, "output_tokens");
	let totalInput: number | null = null;

	if (inputTokens !== null) {
		totalInput = inputTokens + (readNumber(rawUsage, "cache_creation_input_tokens") ?? 0) + (readNumber(rawUsage, "cache_read_input_tokens") ?? 0);
	}

	return {
		inputTokens: totalInput,
		outputTokens,
		cachedInputTokens: readNumber(rawUsage, "cache_read_input_tokens"),
		reasoningTokens: null,
		totalTokens: sumNullableCounts(totalInput, outputTokens),
	};
}

/**
 * 把 Message 对象转换为 ModelResponse，tool_use 的 input 序列化为紧凑 JSON 字符串
 */
export function convertAnthropicMessage(message: unknown, context: AnthropicConversionContext): ModelResponse {
	const contentParts: ResponseContentPart[] = [];

	for (const block of readArray(message, "content")) {
		const blockType = readString(block, "type");

		if (blockType === "text") {
			contentParts.push({
				type: "text",
				text: readString(block, "text") ?? "",
			});
			continue;
		}

		if (blockType === "tool_use") {
			contentParts.push({
				type: "tool_call",
				id: readString(block, "id") ?? "",
				name: readString(block, "name") ?? "",
				arguments: compactJson(readRecord(block, "input")),
			});
		}
	}

	const stopReason = readString(message, "stop_reason");
	let rawResponse: JsonRecord | null = null;

	if (context.includeRawResponse) {
		rawResponse = toPlainRecord(message);
	}

	return buildModelResponse({
		provider: context.provider,
		model: readString(message, "model"),
		id: readString(message, "id"),
		contentParts,
		finishReason: convertAnthropicFinishReason(stopReason),
		vendorFinishReason: stopReason,
		usage: convertAnthropicUsage(readRecord(message, "usage")),
		traceId: context.traceId,
		requestId: context.requestId,
		rawResponse,
	});
}

/**
 * 把 RawMessageStreamEvent 流转换为 ModelEvent，message_delta 的非空 usage 覆盖 message_start 的值
 */
export async function* convertAnthropicStream(
	events: AsyncIterable<unknown>,
	sequencer: ModelEventSequencer,
	context: AnthropicConversionContext,
): AsyncGenerator<ModelEvent> {
	let message: JsonRecord = {};
	const rawUsage: JsonRecord = {};
	const blocks: Map<number, StreamBlock> = new Map();
	let toolCount = 0;
	let stopReason: string | null = null;

	for await (const event of events) {
		const eventType = readString(event, "type");

		if (eventType === "message_start") {
			message = readRecord(event, "message");
			copyUsage(readRecord(message, "usage"), rawUsage);
			yield sequencer.started(readString(message, "model"), readString(message, "id"));
			continue;
		}

		if (eventType === "content_block_start") {
			const block = readRecord(event, "content_block");
			const blockIndex = readNumber(event, "index") ?? blocks.size;
			const blockType = readString(block, "type");

			if (blockType === "text") {
				const text = readString(block, "text") ?? "";
				blocks.set(blockIndex, {
					part: {
						type: "text",
						text,
					},
					toolIndex: null,
				});

				if (text !== "") {
					yield sequencer.textDelta(text);
				}
			} else if (blockType === "tool_use") {
				const toolIndex = toolCount;
				toolCount += 1;
				const callId = readString(block, "id") ?? "";
				const name = readString(block, "name") ?? "";
				blocks.set(blockIndex, {
					part: {
						type: "tool_call",
						id: callId,
						name,
						arguments: "",
					},
					toolIndex,
				});
				yield sequencer.toolCallDelta(toolIndex, callId, name, "");
			}

			continue;
		}

		if (eventType === "content_block_delta") {
			const deltaIndex = readNumber(event, "index");
			const stored = deltaIndex === null ? undefined : blocks.get(deltaIndex);
			const delta = readRecord(event, "delta");
			const deltaType = readString(delta, "type");

			if (stored && stored.part.type === "text" && deltaType === "text_delta") {
				const text = readString(delta, "text") ?? "";
				stored.part.text += text;

				if (text !== "") {
					yield sequencer.textDelta(text);
				}
			} else if (stored && stored.part.type === "tool_call" && deltaType === "input_json_delta") {
				const partialJson = readString(delta, "partial_json") ?? "";
				stored.part.arguments += partialJson;

				if (partialJson !== "" && stored.toolIndex !== null) {
					yield sequencer.toolCallDelta(stored.toolIndex, null, null, partialJson);
				}
			}

			continue;
		}

		if (eventType === "message_delta") {
			stopReason = readString(readRecord(event, "delta"), "stop_reason");
			copyUsage(readRecord(event, "usage"), rawUsage);
			continue;
		}

		if (eventType === "message_stop") {
			const contentParts: ResponseContentPart[] = [];
			const sortedIndexes = [
				...blocks.keys(),
			].sort((left, right) => left - right);

			for (const blockIndex of sortedIndexes) {
				const stored = blocks.get(blockIndex);

				if (!stored) {
					continue;
				}

				if (stored.part.type === "tool_call" && stored.part.arguments === "") {
					stored.part.arguments = "{}";
				}

				contentParts.push(stored.part);
			}

			const usage = convertAnthropicUsage(rawUsage);

			if (Object.keys(rawUsage).length > 0) {
				yield sequencer.usage(usage);
			}

			yield sequencer.completed(buildModelResponse({
				provider: context.provider,
				model: readString(message, "model"),
				id: readString(message, "id"),
				contentParts,
				finishReason: convertAnthropicFinishReason(stopReason),
				vendorFinishReason: stopReason,
				usage,
				traceId: context.traceId,
				requestId: context.requestId,
			}));
			return;
		}
	}
}

function copyUsage(source: JsonRecord, target: JsonRecord): void {
	for (const [key, value] of Object.entries(source)) {
		if (value !== null && value !== undefined) {
			target[key] = value;
		}
	}
}
