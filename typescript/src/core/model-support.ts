/**
 * 提供各模型 Provider 共用的请求校验、响应组装与流式事件编排
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

import { createError, isCommonLlmError } from "./errors.js";

import type { CommonLlmLogger } from "../observability/logger.js";
import type { CommonLlmError, ErrorInfo } from "./errors.js";
import type {
	FinishReason,
	Message,
	ModelCompletedEvent,
	ModelErrorEvent,
	ModelEvent,
	ModelRequest,
	ModelResponse,
	ModelStartedEvent,
	ModelTextDeltaEvent,
	ModelToolCallDeltaEvent,
	ModelUsageEvent,
	ProviderFeature,
	ResponseContentPart,
	ToolCall,
	Usage,
} from "./types.js";

export interface ModelResponseFields {
	provider: string;
	model: string | null;
	id: string | null;
	contentParts: ResponseContentPart[];
	finishReason: FinishReason;
	vendorFinishReason: string | null;
	usage: Usage;
	traceId: string;
	requestId: string | null;
	rawResponse?: Record<string, unknown> | null;
}

export interface ModelStreamRun {
	provider: string;
	traceId: string;
	model: string | null;
	signal?: AbortSignal | undefined;
	logger: CommonLlmLogger;
	convertError: (error: unknown, isCallerAborted: boolean) => CommonLlmError;
	produce: (sequencer: ModelEventSequencer, signal: AbortSignal) => AsyncIterable<ModelEvent>;
}

const MESSAGE_ROLES: ReadonlySet<string> = new Set([
	"system",

	"user",

	"assistant",

	"tool",
]);

/**
 * 按契约逐条校验 ModelRequest，不合法时抛出 CONFIG_INVALID
 */
export function validateModelRequest(request: ModelRequest, provider: string): void {
	if (!Array.isArray(request.messages) || request.messages.length === 0) {
		throw createError("CONFIG_INVALID", "ModelRequest.messages 至少需要 1 条消息", {
			provider,
		});
	}

	for (const message of request.messages) {
		validateMessage(message, provider);
	}

	if (request.tools) {
		for (const tool of request.tools) {
			if (!tool.name || typeof tool.parameters !== "object" || tool.parameters === null) {
				throw createError("CONFIG_INVALID", "ToolDefinition 需要 name 与 parameters", {
					provider,
				});
			}
		}
	}

	if (request.toolChoice && request.toolChoice.type === "tool" && !request.toolChoice.name) {
		throw createError("CONFIG_INVALID", "toolChoice.type=tool 时需要 name", {
			provider,
		});
	}

	if (request.responseFormat && request.responseFormat.type === "json_schema" && !request.responseFormat.name) {
		throw createError("CONFIG_INVALID", "responseFormat.type=json_schema 时需要 name 与 schema", {
			provider,
		});
	}
}

function validateMessage(message: Message, provider: string): void {
	if (!MESSAGE_ROLES.has(message.role)) {
		throw createError("CONFIG_INVALID", `不支持的消息角色：${String(message.role)}`, {
			provider,
		});
	}

	if (!Array.isArray(message.content) || message.content.length === 0) {
		throw createError("CONFIG_INVALID", "Message.content 至少需要 1 个内容片段", {
			provider,
		});
	}

	for (const part of message.content) {
		if (part.type === "tool_call" && message.role !== "assistant") {
			throw createError("CONFIG_INVALID", "tool_call 只能出现在 assistant 消息中", {
				provider,
			});
		}

		if (part.type === "tool_result" && message.role !== "tool") {
			throw createError("CONFIG_INVALID", "tool_result 只能出现在 tool 消息中", {
				provider,
			});
		}

		if (part.type === "image") {
			const hasUrl = typeof part.url === "string" && part.url !== "";
			const hasBase64 = typeof part.base64 === "string" && part.base64 !== "";

			if (hasUrl === hasBase64 || (hasBase64 && !part.mimeType)) {
				throw createError("CONFIG_INVALID", "image 片段需要 url，或 base64 加 mimeType，二者只能选一种", {
					provider,
				});
			}
		}
	}
}

/**
 * 取请求模型名，缺省时使用配置的 default_model
 */
export function resolveModelName(request: ModelRequest, defaultModel: string | undefined, provider: string): string {
	const model = request.model ?? defaultModel;

	if (!model) {
		throw createError("CONFIG_INVALID", "请求未指定 model，且配置中没有 default_model", {
			provider,
		});
	}

	return model;
}

/**
 * 合并配置 native_options 与请求 providerOptions，键与已映射字段重名时报 CONFIG_INVALID
 */
export function mergeProviderOptions(
	nativeOptions: Record<string, unknown>,
	providerOptions: Record<string, unknown> | null | undefined,
	mappedKeys: ReadonlySet<string>,
	provider: string,
): Record<string, unknown> {
	const merged: Record<string, unknown> = {
		...nativeOptions,
		...providerOptions,
	};

	for (const key of Object.keys(merged)) {
		if (mappedKeys.has(key)) {
			throw createError("CONFIG_INVALID", `providerOptions 的键 ${key} 与已映射字段重名`, {
				provider,
			});
		}
	}

	return merged;
}

/**
 * 计算单次请求超时毫秒数，请求级覆盖配置级
 */
export function resolveTimeoutMilliseconds(request: ModelRequest, timeoutSeconds: number): number {
	const effectiveSeconds = request.timeoutSeconds ?? timeoutSeconds;

	return Math.round(effectiveSeconds * 1000);
}

/**
 * 拼接全部 system 消息的文本，多条之间用空行分隔
 */
export function collectSystemText(messages: readonly Message[]): string | null {
	const systemTexts: string[] = [];

	for (const message of messages) {
		if (message.role !== "system") {
			continue;
		}

		for (const part of message.content) {
			if (part.type === "text") {
				systemTexts.push(part.text);
			}
		}
	}

	if (systemTexts.length === 0) {
		return null;
	}

	return systemTexts.join("\n\n");
}

/**
 * 解析工具参数 JSON 字符串，Anthropic 与 Gemini 需要对象形式
 */
export function parseToolArguments(argumentsText: string, provider: string): Record<string, unknown> {
	if (argumentsText.trim() === "") {
		return {};
	}

	try {
		const parsed: unknown = JSON.parse(argumentsText);

		if (parsed !== null && typeof parsed === "object" && !Array.isArray(parsed)) {
			return parsed as Record<string, unknown>;
		}
	} catch (error) {
		throw createError("CONFIG_INVALID", "tool_call.arguments 不是合法的 JSON 对象", {
			provider,
			cause: error,
		});
	}

	throw createError("CONFIG_INVALID", "tool_call.arguments 必须是 JSON 对象", {
		provider,
	});
}

export function createEmptyUsage(): Usage {
	return {
		inputTokens: null,
		outputTokens: null,
		cachedInputTokens: null,
		reasoningTokens: null,
		totalTokens: null,
	};
}

export function sumNullableCounts(first: number | null, second: number | null): number | null {
	if (first === null || second === null) {
		return null;
	}

	return first + second;
}

/**
 * 根据内容片段组装 ModelResponse，text 与 toolCalls 由 contentParts 推导
 */
export function buildModelResponse(fields: ModelResponseFields): ModelResponse {
	const textParts: string[] = [];
	const toolCalls: ToolCall[] = [];

	for (const part of fields.contentParts) {
		if (part.type === "text") {
			textParts.push(part.text);
			continue;
		}

		toolCalls.push({
			id: part.id,
			name: part.name,
			arguments: part.arguments,
		});
	}

	const response: ModelResponse = {
		provider: fields.provider,
		model: fields.model,
		id: fields.id,
		contentParts: fields.contentParts,
		text: textParts.join(""),
		toolCalls,
		finishReason: fields.finishReason,
		vendorFinishReason: fields.vendorFinishReason,
		usage: fields.usage,
		traceId: fields.traceId,
		requestId: fields.requestId,
	};

	if (fields.rawResponse !== undefined && fields.rawResponse !== null) {
		response.rawResponse = fields.rawResponse;
	}

	return response;
}

/**
 * 为单个流生成带递增 sequence 的 ModelEvent
 */
export class ModelEventSequencer {
	private nextSequence = 0;
	private startedEmitted = false;

	constructor(
		readonly provider: string,
		readonly traceId: string,
	) {}

	get hasStarted(): boolean {
		return this.startedEmitted;
	}

	started(model: string | null, id: string | null): ModelStartedEvent {
		this.startedEmitted = true;

		return {
			type: "started",
			traceId: this.traceId,
			sequence: this.takeSequence(),
			provider: this.provider,
			model,
			id,
		};
	}

	textDelta(text: string): ModelTextDeltaEvent {
		return {
			type: "text_delta",
			traceId: this.traceId,
			sequence: this.takeSequence(),
			provider: this.provider,
			text,
		};
	}

	toolCallDelta(index: number, id: string | null, name: string | null, argumentsDelta: string): ModelToolCallDeltaEvent {
		return {
			type: "tool_call_delta",
			traceId: this.traceId,
			sequence: this.takeSequence(),
			provider: this.provider,
			index,
			id,
			name,
			argumentsDelta,
		};
	}

	usage(usage: Usage): ModelUsageEvent {
		return {
			type: "usage",
			traceId: this.traceId,
			sequence: this.takeSequence(),
			provider: this.provider,
			usage,
		};
	}

	completed(response: ModelResponse): ModelCompletedEvent {
		return {
			type: "completed",
			traceId: this.traceId,
			sequence: this.takeSequence(),
			provider: this.provider,
			response,
		};
	}

	error(error: ErrorInfo): ModelErrorEvent {
		return {
			type: "error",
			traceId: this.traceId,
			sequence: this.takeSequence(),
			provider: this.provider,
			error,
		};
	}

	private takeSequence(): number {
		const sequence = this.nextSequence;
		this.nextSequence += 1;

		return sequence;
	}
}

/**
 * 创建跟随调用方信号的 AbortController，调用方取消时同步取消底层请求
 */
export function createLinkedAbortController(parent?: AbortSignal): AbortController {
	const controller = new AbortController();

	if (!parent) {
		return controller;
	}

	if (parent.aborted) {
		controller.abort(parent.reason);
		return controller;
	}

	parent.addEventListener("abort", () => controller.abort(parent.reason), {
		once: true,
	});

	return controller;
}

/**
 * 编排一次流式调用，保证只有一个终止事件，调用方提前停止时关闭底层流
 */
export async function* runModelStream(run: ModelStreamRun): AsyncGenerator<ModelEvent> {
	const sequencer = new ModelEventSequencer(run.provider, run.traceId);
	const controller = createLinkedAbortController(run.signal);
	const startedAt = Date.now();
	let hasTerminal = false;

	try {
		for await (const event of run.produce(sequencer, controller.signal)) {
			if (hasTerminal) {
				break;
			}

			if (event.type === "completed" || event.type === "error") {
				hasTerminal = true;
				logModelCall(run, startedAt, event);
			}

			yield event;
		}

		if (!hasTerminal) {
			const error = createError("PROVIDER_ERROR", "厂商流在完成事件之前结束", {
				provider: run.provider,
			});
			const errorEvent = sequencer.error(error.info);
			hasTerminal = true;
			logModelCall(run, startedAt, errorEvent);
			yield errorEvent;
		}
	} catch (caught) {
		if (hasTerminal) {
			return;
		}

		const isCallerAborted = run.signal?.aborted === true;
		let error: CommonLlmError;

		if (isCallerAborted) {
			error = createError("CANCELLED", "调用方取消了流式请求", {
				provider: run.provider,
			});
		} else if (isCommonLlmError(caught)) {
			error = caught;
		} else {
			error = run.convertError(caught, false);
		}

		const errorEvent = sequencer.error(error.info);
		hasTerminal = true;
		logModelCall(run, startedAt, errorEvent);
		yield errorEvent;
	} finally {
		controller.abort();
	}
}

export interface ModelCallRun {
	provider: string;
	traceId: string;
	model: string | null;
	signal?: AbortSignal | undefined;
	logger: CommonLlmLogger;
	convertError: (error: unknown, isCallerAborted: boolean) => CommonLlmError;
	call: (signal: AbortSignal) => Promise<ModelResponse>;
}

/**
 * 执行一次非流式调用，统一处理取消、错误转换与调用日志
 */
export async function runModelCall(run: ModelCallRun): Promise<ModelResponse> {
	const controller = createLinkedAbortController(run.signal);
	const startedAt = Date.now();

	try {
		const response = await run.call(controller.signal);
		run.logger.info("模型调用完成", {
			provider: run.provider,
			model: response.model ?? run.model,
			durationMs: Date.now() - startedAt,
			traceId: run.traceId,
			usage: response.usage,
		});

		return response;
	} catch (caught) {
		const isCallerAborted = run.signal?.aborted === true;
		let error: CommonLlmError;

		if (isCallerAborted) {
			error = createError("CANCELLED", "调用方取消了请求", {
				provider: run.provider,
			});
		} else if (isCommonLlmError(caught)) {
			error = caught;
		} else {
			error = run.convertError(caught, false);
		}

		run.logger.warn("模型调用失败", {
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

/**
 * 校验请求用到的能力是否在 Provider 能力集合内，不支持时报 SDK_UNSUPPORTED_FEATURE
 */
export function assertRequestFeatures(request: ModelRequest, features: readonly ProviderFeature[], provider: string): void {
	const requiredFeatures: ProviderFeature[] = [];

	if (request.tools && request.tools.length > 0) {
		requiredFeatures.push("tool_calls");
	}

	if (request.responseFormat && request.responseFormat.type === "json_schema") {
		requiredFeatures.push("structured_output");
	}

	for (const message of request.messages) {
		const hasImage = message.content.some((part) => part.type === "image");

		if (hasImage) {
			requiredFeatures.push("vision");
			break;
		}
	}

	for (const feature of requiredFeatures) {
		if (!features.includes(feature)) {
			throw createError("SDK_UNSUPPORTED_FEATURE", `Provider ${provider} 不支持能力：${feature}`, {
				provider,
			});
		}
	}
}

function logModelCall(run: ModelStreamRun, startedAt: number, event: ModelCompletedEvent | ModelErrorEvent): void {
	const durationMs = Date.now() - startedAt;

	if (event.type === "completed") {
		run.logger.info("模型流式调用完成", {
			provider: run.provider,
			model: event.response.model ?? run.model,
			durationMs,
			traceId: run.traceId,
			usage: event.response.usage,
		});
		return;
	}

	run.logger.warn("模型流式调用失败", {
		provider: run.provider,
		model: run.model,
		durationMs,
		traceId: run.traceId,
		category: event.error.category,
	});
}
