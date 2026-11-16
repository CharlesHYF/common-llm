/**
 * 提供各 Agent Adapter 共用的事件组装、事件队列、并发限制与运行环境校验
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

import { realpath, stat } from "node:fs/promises";
import path from "node:path";

import { createError } from "./errors.js";

import type { ErrorInfo } from "./errors.js";
import type {
	AgentApprovalRequestedEvent,
	AgentCancelledEvent,
	AgentCompletedEvent,
	AgentEvent,
	AgentFailedEvent,
	AgentFileChangedEvent,
	AgentMessageEvent,
	AgentName,
	AgentRawVendorEvent,
	AgentResult,
	AgentStartedEvent,
	AgentStatus,
	AgentToolCompletedEvent,
	AgentToolStartedEvent,
	AgentUsage,
	AgentUsageEvent,
	ApprovalDecision,
	ApprovalKind,
	FileChange,
} from "./types.js";

export interface AgentResultFields {
	status: AgentStatus;
	finalText: string;
	usage: AgentUsage | null;
	exitCode: number | null;
	error: ErrorInfo | null;
}

// 子进程运行必需的系统变量，其余变量只有列入 envAllowlist 才会继承
export const BASE_ENVIRONMENT_NAMES: readonly string[] = [
	"PATH",

	"HOME",

	"USER",

	"LOGNAME",

	"SHELL",

	"TMPDIR",

	"TEMP",

	"TMP",

	"LANG",

	"LC_ALL",

	"LC_CTYPE",

	"TERM",

	"SYSTEMROOT",

	"WINDIR",

	"COMSPEC",

	"PATHEXT",

	"USERPROFILE",

	"APPDATA",

	"LOCALAPPDATA",
];

/**
 * 为单次 run 生成带公共字段与递增 sequence 的 AgentEvent
 */
export class AgentEventFactory {
	sessionId: string | null = null;
	private nextSequence = 0;

	constructor(
		readonly agent: AgentName,
		readonly runId: string,
		readonly traceId: string,
	) {}

	started(model: string | null, workingDirectory: string): AgentStartedEvent {
		return {
			...this.buildBase(),
			type: "started",
			model,
			workingDirectory,
		};
	}

	message(role: "assistant" | "user", text: string, isDelta: boolean): AgentMessageEvent {
		return {
			...this.buildBase(),
			type: "message",
			role,
			text,
			isDelta,
		};
	}

	toolStarted(toolCallId: string, toolName: string, input: Record<string, unknown> | null): AgentToolStartedEvent {
		return {
			...this.buildBase(),
			type: "tool_started",
			toolCallId,
			toolName,
			input,
		};
	}

	toolCompleted(toolCallId: string, toolName: string | null, output: string | null, isError: boolean): AgentToolCompletedEvent {
		return {
			...this.buildBase(),
			type: "tool_completed",
			toolCallId,
			toolName,
			output,
			isError,
		};
	}

	fileChanged(changes: FileChange[]): AgentFileChangedEvent {
		return {
			...this.buildBase(),
			type: "file_changed",
			changes,
		};
	}

	approvalRequested(approvalId: string, kind: ApprovalKind, summary: string, decision: ApprovalDecision): AgentApprovalRequestedEvent {
		return {
			...this.buildBase(),
			type: "approval_requested",
			approvalId,
			kind,
			summary,
			decision,
		};
	}

	usage(usage: AgentUsage): AgentUsageEvent {
		return {
			...this.buildBase(),
			type: "usage",
			usage,
		};
	}

	completed(result: AgentResult): AgentCompletedEvent {
		return {
			...this.buildBase(),
			type: "completed",
			result,
		};
	}

	failed(error: ErrorInfo): AgentFailedEvent {
		return {
			...this.buildBase(),
			type: "failed",
			error,
		};
	}

	cancelled(): AgentCancelledEvent {
		return {
			...this.buildBase(),
			type: "cancelled",
		};
	}

	rawVendorEvent(vendorType: string, payload: Record<string, unknown>): AgentRawVendorEvent {
		return {
			...this.buildBase(),
			type: "raw_vendor_event",
			vendorType,
			payload,
		};
	}

	buildResult(fields: AgentResultFields): AgentResult {
		return {
			runId: this.runId,
			sessionId: this.sessionId,
			status: fields.status,
			finalText: fields.finalText,
			usage: fields.usage,
			exitCode: fields.exitCode,
			error: fields.error,
		};
	}

	private buildBase(): {
		traceId: string;
		runId: string;
		sessionId: string | null;
		sequence: number;
		agent: AgentName;
	} {
		const sequence = this.nextSequence;
		this.nextSequence += 1;

		return {
			traceId: this.traceId,
			runId: this.runId,
			sessionId: this.sessionId,
			sequence,
			agent: this.agent,
		};
	}
}

/**
 * 生产者写入、消费者异步读取的事件队列，缓存超限时丢弃最早的 raw_vendor_event
 */
export class AgentEventChannel {
	private readonly bufferedEvents: AgentEvent[] = [];
	private waitingResolver: (() => void) | null = null;
	private isClosed = false;

	constructor(private readonly maxBufferedEvents: number) {}

	push(event: AgentEvent): void {
		if (this.isClosed) {
			return;
		}

		this.bufferedEvents.push(event);

		if (this.bufferedEvents.length > this.maxBufferedEvents) {
			const rawIndex = this.bufferedEvents.findIndex((buffered) => buffered.type === "raw_vendor_event");

			if (rawIndex >= 0) {
				this.bufferedEvents.splice(rawIndex, 1);
			}
		}

		this.notifyReader();
	}

	close(): void {
		this.isClosed = true;
		this.notifyReader();
	}

	async *drain(): AsyncGenerator<AgentEvent> {
		while (true) {
			const event = this.bufferedEvents.shift();

			if (event !== undefined) {
				yield event;
				continue;
			}

			if (this.isClosed) {
				return;
			}

			await new Promise<void>((resolve) => {
				this.waitingResolver = resolve;
			});
		}
	}

	private notifyReader(): void {
		const resolver = this.waitingResolver;
		this.waitingResolver = null;

		if (resolver) {
			resolver();
		}
	}
}

/**
 * 限制单个 AgentClient 同时运行的 run 数量，超出时报 RATE_LIMITED
 */
export class ConcurrencyLimiter {
	private activeCount = 0;

	constructor(
		private readonly maxConcurrent: number,
		private readonly agent: AgentName,
	) {}

	acquire(): void {
		if (this.activeCount >= this.maxConcurrent) {
			throw createError("RATE_LIMITED", `同时运行的 run 已达到上限 ${this.maxConcurrent}`, {
				provider: this.agent,
				retriable: true,
			});
		}

		this.activeCount += 1;
	}

	release(): void {
		if (this.activeCount > 0) {
			this.activeCount -= 1;
		}
	}
}

/**
 * 校验工作目录存在且为目录，返回解析符号链接后的绝对路径
 */
export async function resolveWorkingDirectory(requested: string | null | undefined, configured: string | undefined, agent: AgentName): Promise<string> {
	const candidate = requested ?? configured;

	if (!candidate) {
		throw createError("CONFIG_INVALID", "未指定工作目录，请在请求或配置中提供 working_directory", {
			provider: agent,
		});
	}

	const absolutePath = path.resolve(candidate);

	try {
		const directoryStat = await stat(absolutePath);

		if (!directoryStat.isDirectory()) {
			throw createError("CONFIG_INVALID", `工作目录不是目录：${absolutePath}`, {
				provider: agent,
			});
		}

		return await realpath(absolutePath);
	} catch (error) {
		if (error instanceof Error && error.name === "CommonLlmError") {
			throw error;
		}

		throw createError("CONFIG_INVALID", `工作目录不存在：${absolutePath}`, {
			provider: agent,
			cause: error,
		});
	}
}

/**
 * 按白名单组装子进程环境变量，只继承运行必需变量与 envAllowlist，再叠加认证变量
 */
export function buildChildEnvironment(
	source: NodeJS.ProcessEnv,
	allowlist: readonly string[],
	overrides: Record<string, string>,
): Record<string, string> {
	const environment: Record<string, string> = {};
	const allowedNames: string[] = [
		...BASE_ENVIRONMENT_NAMES,
		...allowlist,
	];

	for (const name of allowedNames) {
		const value = source[name];

		if (typeof value === "string") {
			environment[name] = value;
		}
	}

	for (const [name, value] of Object.entries(overrides)) {
		environment[name] = value;
	}

	return environment;
}

/**
 * 按 Unicode 码点截断工具输出
 */
export function truncateToolOutput(output: string | null, maxChars: number): string | null {
	if (output === null) {
		return null;
	}

	const codePoints = Array.from(output);

	if (codePoints.length <= maxChars) {
		return output;
	}

	return codePoints.slice(0, maxChars).join("");
}

export function isTerminalAgentEvent(event: AgentEvent): boolean {
	return event.type === "completed" || event.type === "failed" || event.type === "cancelled";
}

/**
 * 合并请求与配置中的白名单列表，请求级优先
 */
export function selectList(requested: readonly string[] | null | undefined, configured: readonly string[]): string[] {
	if (requested) {
		return [
			...requested,
		];
	}

	return [
		...configured,
	];
}

/**
 * 消费 runStream 的事件并汇总为 AgentResult，collectEvents 为 true 时附带全部事件
 */
export async function collectAgentResult(events: AsyncIterable<AgentEvent>, collectEvents: boolean, exitCodeOf: () => number | null): Promise<AgentResult> {
	const collected: AgentEvent[] = [];
	let finalText = "";
	let usage: AgentUsage | null = null;
	let runId = "";
	let sessionId: string | null = null;
	let terminal: AgentEvent | null = null;

	for await (const event of events) {
		collected.push(event);
		runId = event.runId;
		sessionId = event.sessionId ?? sessionId;

		if (event.type === "message" && event.role === "assistant" && !event.isDelta) {
			finalText = event.text;
		}

		if (event.type === "usage") {
			usage = event.usage;
		}

		if (isTerminalAgentEvent(event)) {
			terminal = event;
		}
	}

	let result: AgentResult = {
		runId,
		sessionId,
		status: "failed",
		finalText,
		usage,
		exitCode: exitCodeOf(),
		error: null,
	};

	if (terminal !== null && terminal.type === "completed") {
		result = {
			...terminal.result,
		};
	} else if (terminal !== null && terminal.type === "cancelled") {
		result.status = "cancelled";
	} else if (terminal !== null && terminal.type === "failed") {
		result.error = terminal.error;
	} else {
		result.error = createError("INTERNAL_ERROR", "run 结束时没有终止事件").info;
	}

	if (collectEvents) {
		result.events = collected;
	}

	return result;
}

export function toRecord(value: unknown): Record<string, unknown> | null {
	if (value !== null && typeof value === "object" && !Array.isArray(value)) {
		return value as Record<string, unknown>;
	}

	return null;
}
