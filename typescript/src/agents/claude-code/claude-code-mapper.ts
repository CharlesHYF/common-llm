/**
 * 把 Claude Agent SDK 输出的 stream-json 消息转换为 AgentEvent
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

import { truncateToolOutput } from "../../core/agent-support.js";
import { createError } from "../../core/errors.js";
import {
	isRecord,
	readArray,
	readNumber,
	readRecord,
	readString,
	toPlainRecord,
} from "../../core/json.js";

import type { AgentEventFactory } from "../../core/agent-support.js";
import type { ErrorInfo } from "../../core/errors.js";
import type { AgentEvent, AgentUsage } from "../../core/types.js";

/**
 * 保存单次 run 的转换状态：工具名映射与已发出的审批
 */
export class ClaudeCodeEventConverter {
	private readonly toolNames: Map<string, string> = new Map();

	constructor(
		private readonly factory: AgentEventFactory,
		private readonly maxToolOutputChars: number,
		private readonly emittedApprovalIds: Set<string>,
	) {}

	/**
	 * 转换一条消息；result 消息先发 usage，再按 subtype 与 is_error 发出终止事件
	 */
	convert(message: unknown, isCancelRequested: boolean): AgentEvent[] {
		const messageType = readString(message, "type");
		const subtype = readString(message, "subtype");

		if (messageType === "system" && subtype === "init") {
			this.factory.sessionId = readString(message, "session_id");
			return [
				this.factory.started(readString(message, "model"), readString(message, "cwd") ?? ""),
			];
		}

		if (messageType === "system" && subtype === "permission_denied") {
			return this.convertPermissionDenied(message);
		}

		if (messageType === "stream_event") {
			return this.convertStreamEvent(message);
		}

		if (messageType === "assistant") {
			return this.convertAssistant(message);
		}

		if (messageType === "user") {
			return this.convertUser(message);
		}

		if (messageType === "result") {
			return this.convertResult(message, isCancelRequested);
		}

		let vendorType = messageType ?? "unknown";

		if (messageType === "system") {
			vendorType = `system/${subtype ?? "unknown"}`;
		}

		return [
			this.factory.rawVendorEvent(vendorType, toPlainRecord(message)),
		];
	}

	private convertPermissionDenied(message: unknown): AgentEvent[] {
		const approvalId = readString(message, "tool_use_id") ?? "";

		if (this.emittedApprovalIds.has(approvalId)) {
			return [];
		}

		this.emittedApprovalIds.add(approvalId);

		return [
			this.factory.approvalRequested(approvalId, "tool", readString(message, "tool_name") ?? "", "decline"),
		];
	}

	private convertStreamEvent(message: unknown): AgentEvent[] {
		const event = readRecord(message, "event");
		const delta = readRecord(event, "delta");

		if (readString(event, "type") !== "content_block_delta" || readString(delta, "type") !== "text_delta") {
			return [];
		}

		const text = readString(delta, "text") ?? "";

		if (text === "") {
			return [];
		}

		return [
			this.factory.message("assistant", text, true),
		];
	}

	private convertAssistant(message: unknown): AgentEvent[] {
		const events: AgentEvent[] = [];

		for (const block of readArray(readRecord(message, "message"), "content")) {
			const blockType = readString(block, "type");

			if (blockType === "text") {
				events.push(this.factory.message("assistant", readString(block, "text") ?? "", false));
				continue;
			}

			if (blockType === "tool_use") {
				const toolCallId = readString(block, "id") ?? "";
				const toolName = readString(block, "name") ?? "";
				const input = isRecord(block) && isRecord(block.input) ? block.input : null;
				this.toolNames.set(toolCallId, toolName);
				events.push(this.factory.toolStarted(toolCallId, toolName, input));
			}
		}

		return events;
	}

	private convertUser(message: unknown): AgentEvent[] {
		const events: AgentEvent[] = [];

		for (const block of readArray(readRecord(message, "message"), "content")) {
			if (readString(block, "type") !== "tool_result") {
				continue;
			}

			const toolCallId = readString(block, "tool_use_id") ?? "";
			const output = stringifyToolResult(isRecord(block) ? block.content : null);
			events.push(this.factory.toolCompleted(
				toolCallId,
				this.toolNames.get(toolCallId) ?? null,
				truncateToolOutput(output, this.maxToolOutputChars),
				isRecord(block) && block.is_error === true,
			));
		}

		return events;
	}

	private convertResult(message: unknown, isCancelRequested: boolean): AgentEvent[] {
		const usage = buildClaudeUsage(message);
		const events: AgentEvent[] = [
			this.factory.usage(usage),
		];

		if (isCancelRequested) {
			events.push(this.factory.cancelled());
			return events;
		}

		const isSuccess = readString(message, "subtype") === "success" && isRecord(message) && message.is_error !== true;

		if (isSuccess) {
			events.push(this.factory.completed(this.factory.buildResult({
				status: "completed",
				finalText: readString(message, "result") ?? "",
				usage,
				exitCode: null,
				error: null,
			})));
			return events;
		}

		events.push(this.factory.failed(buildClaudeResultError(message)));
		return events;
	}
}

/**
 * tool_result 的 content 统一转为字符串，数组形式只拼接 text 元素
 */
function stringifyToolResult(content: unknown): string | null {
	if (content === null || content === undefined) {
		return null;
	}

	if (typeof content === "string") {
		return content;
	}

	const textParts: string[] = [];

	if (Array.isArray(content)) {
		for (const part of content) {
			if (readString(part, "type") === "text") {
				textParts.push(readString(part, "text") ?? "");
			}
		}
	}

	return textParts.join("\n");
}

/**
 * inputTokens 为含缓存的总输入量，totalTokens 为输入加输出
 */
function buildClaudeUsage(result: unknown): AgentUsage {
	const usage = readRecord(result, "usage");
	const inputTokens = readNumber(usage, "input_tokens");
	const outputTokens = readNumber(usage, "output_tokens");
	let totalInput: number | null = null;
	let totalTokens: number | null = null;

	if (inputTokens !== null) {
		totalInput = inputTokens + (readNumber(usage, "cache_creation_input_tokens") ?? 0) + (readNumber(usage, "cache_read_input_tokens") ?? 0);
	}

	if (totalInput !== null && outputTokens !== null) {
		totalTokens = totalInput + outputTokens;
	}

	return {
		inputTokens: totalInput,
		outputTokens,
		cachedInputTokens: readNumber(usage, "cache_read_input_tokens"),
		reasoningTokens: readNumber(readRecord(usage, "output_tokens_details"), "thinking_tokens"),
		totalTokens,
		costUsd: readNumber(result, "total_cost_usd"),
		numTurns: readNumber(result, "num_turns"),
	};
}

function buildClaudeResultError(result: unknown): ErrorInfo {
	const errorMessages: string[] = [];

	for (const errorText of readArray(result, "errors")) {
		errorMessages.push(String(errorText));
	}

	const subtype = readString(result, "subtype");
	let message = errorMessages.join("; ");

	if (message === "") {
		message = `Claude Code 执行失败：${subtype ?? "unknown"}`;
	}

	return createError("PROVIDER_ERROR", message, {
		provider: "claude_code",
		vendorCode: subtype,
	}).info;
}
