/**
 * 把 Codex SDK 输出的 codex exec 事件转换为 AgentEvent
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
import type {
	AgentEvent,
	AgentUsage,
	FileChange,
	FileChangeKind,
} from "../../core/types.js";

const FAILED_ITEM_STATUSES: ReadonlySet<string> = new Set([
	"failed",

	"declined",
]);

const FILE_CHANGE_KINDS: readonly FileChangeKind[] = [
	"add",

	"update",

	"delete",
];

/**
 * 保存单次 run 的转换状态；exec 不返回模型名与工作目录，started 使用请求值
 */
export class CodexExecEventConverter {
	private finalText = "";

	constructor(
		private readonly factory: AgentEventFactory,
		private readonly requestedModel: string | null,
		private readonly workingDirectory: string,
		private readonly maxToolOutputChars: number,
	) {}

	convert(event: unknown): AgentEvent[] {
		const eventType = readString(event, "type");

		if (eventType === "thread.started") {
			this.factory.sessionId = readString(event, "thread_id");
			return [];
		}

		if (eventType === "turn.started") {
			return [
				this.factory.started(this.requestedModel, this.workingDirectory),
			];
		}

		if (eventType === "item.started") {
			const started = this.convertItemStarted(readRecord(event, "item"));

			if (started !== null) {
				return [
					started,
				];
			}
		}

		if (eventType === "item.completed") {
			const completed = this.convertItemCompleted(readRecord(event, "item"));

			if (completed !== null) {
				return [
					completed,
				];
			}
		}

		if (eventType === "turn.completed") {
			return this.convertTurnCompleted(readRecord(event, "usage"));
		}

		if (eventType === "turn.failed" || eventType === "error") {
			const message = readString(readRecord(event, "error"), "message") ?? readString(event, "message") ?? "Codex exec 执行失败";

			return [
				this.factory.failed(createError("PROVIDER_ERROR", message, {
					provider: "codex",
				}).info),
			];
		}

		return [
			this.factory.rawVendorEvent(eventType ?? "unknown", toPlainRecord(event)),
		];
	}

	private convertItemStarted(item: Record<string, unknown>): AgentEvent | null {
		const itemType = readString(item, "type");
		const itemId = readString(item, "id") ?? "";

		if (itemType === "command_execution") {
			return this.factory.toolStarted(itemId, "command_execution", {
				command: readString(item, "command"),
			});
		}

		if (itemType === "mcp_tool_call") {
			const toolArguments = isRecord(item.arguments) ? item.arguments : null;

			return this.factory.toolStarted(itemId, `${readString(item, "server") ?? ""}.${readString(item, "tool") ?? ""}`, toolArguments);
		}

		if (itemType === "web_search") {
			return this.factory.toolStarted(itemId, "web_search", {
				query: readString(item, "query"),
			});
		}

		return null;
	}

	private convertItemCompleted(item: Record<string, unknown>): AgentEvent | null {
		const itemType = readString(item, "type");
		const itemId = readString(item, "id") ?? "";
		const isFailedStatus = FAILED_ITEM_STATUSES.has(readString(item, "status") ?? "");

		if (itemType === "command_execution") {
			const exitCode = readNumber(item, "exit_code");
			const isError = isFailedStatus || (exitCode !== null && exitCode !== 0);

			return this.factory.toolCompleted(itemId, "command_execution", truncateToolOutput(readString(item, "aggregated_output"), this.maxToolOutputChars), isError);
		}

		if (itemType === "mcp_tool_call") {
			const toolName = `${readString(item, "server") ?? ""}.${readString(item, "tool") ?? ""}`;
			const output = readString(readRecord(item, "error"), "message") ?? collectMcpText(readRecord(item, "result"));

			return this.factory.toolCompleted(itemId, toolName, truncateToolOutput(output, this.maxToolOutputChars), isFailedStatus);
		}

		if (itemType === "web_search") {
			return this.factory.toolCompleted(itemId, "web_search", null, isFailedStatus);
		}

		if (itemType === "agent_message") {
			this.finalText = readString(item, "text") ?? "";
			return this.factory.message("assistant", this.finalText, false);
		}

		if (itemType === "file_change") {
			const changes: FileChange[] = [];

			for (const change of readArray(item, "changes")) {
				const kind = readString(change, "kind") ?? "update";

				changes.push({
					path: readString(change, "path") ?? "",
					kind: FILE_CHANGE_KINDS.includes(kind as FileChangeKind) ? kind as FileChangeKind : "update",
				});
			}

			return this.factory.fileChanged(changes);
		}

		return null;
	}

	/**
	 * turn.completed 先发 usage 再发 completed，totalTokens 为输入加输出
	 */
	private convertTurnCompleted(rawUsage: Record<string, unknown>): AgentEvent[] {
		const inputTokens = readNumber(rawUsage, "input_tokens");
		const outputTokens = readNumber(rawUsage, "output_tokens");
		let totalTokens: number | null = null;

		if (inputTokens !== null && outputTokens !== null) {
			totalTokens = inputTokens + outputTokens;
		}

		const usage: AgentUsage = {
			inputTokens,
			outputTokens,
			cachedInputTokens: readNumber(rawUsage, "cached_input_tokens"),
			reasoningTokens: readNumber(rawUsage, "reasoning_output_tokens"),
			totalTokens,
			costUsd: null,
			numTurns: null,
		};

		return [
			this.factory.usage(usage),

			this.factory.completed(this.factory.buildResult({
				status: "completed",
				finalText: this.finalText,
				usage,
				exitCode: null,
				error: null,
			})),
		];
	}

}

function collectMcpText(result: Record<string, unknown>): string | null {
	const texts: string[] = [];

	for (const block of readArray(result, "content")) {
		const text = readString(block, "text");

		if (text !== null) {
			texts.push(text);
		}
	}

	if (texts.length === 0) {
		return null;
	}

	return texts.join("\n");
}
