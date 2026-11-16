/**
 * 基于官方 Claude Agent SDK 的 query() 实现 Claude Code AgentClient
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

import {
	AgentEventChannel,
	AgentEventFactory,
	buildChildEnvironment,
	collectAgentResult,
	ConcurrencyLimiter,
	isTerminalAgentEvent,
	resolveWorkingDirectory,
	selectList,
} from "../../core/agent-support.js";
import { createError, isCommonLlmError } from "../../core/errors.js";
import { terminateProcessTree } from "../../core/process.js";
import { redactText } from "../../core/redaction.js";
import { ClaudeCodeEventConverter } from "./claude-code-mapper.js";
import { probeClaudeCode } from "./claude-code-probe.js";
import { spawnMonitoredProcess } from "./claude-code-process.js";
import { VERSION } from "../../version.js";

import type {
	CanUseTool,
	Options,
	PermissionResult,
	Query,
} from "@anthropic-ai/claude-agent-sdk";
import type { ChildProcess } from "node:child_process";
import type { AgentClient, AgentClientOptions } from "../../core/clients.js";
import type { CommonLlmError } from "../../core/errors.js";
import type {
	AgentCapability,
	AgentEvent,
	AgentEventType,
	AgentFeature,
	AgentRequest,
	AgentResult,
	AgentRunOptions,
	ApprovalDecision,
	ApprovalHandler,
} from "../../core/types.js";
import type { AgentConfig } from "../../config/types.js";
import type { CommonLlmLogger } from "../../observability/logger.js";
import type { IdGenerator } from "../../observability/ids.js";
import type { ClaudeCodeRuntime } from "./claude-code-probe.js";

interface ClaudeRunState {
	runId: string;
	factory: AgentEventFactory;
	channel: AgentEventChannel;
	abortController: AbortController;
	query: Query | null;
	child: ChildProcess | null;
	exitCode: number | null;
	stderrTail: string;
	limitError: CommonLlmError | null;
	isCancelRequested: boolean;
	isTimedOut: boolean;
	hasTerminal: boolean;
	emittedApprovalIds: Set<string>;
}

interface PreparedRun {
	runtime: ClaudeCodeRuntime;
	options: Options;
	timeoutSeconds: number;
}

const AGENT_NAME = "claude_code";

const CLAUDE_FEATURES: readonly AgentFeature[] = [
	"streaming",

	"session_resume",

	"approvals",

	"interrupt",

	"cost",
];

const CLAUDE_EVENT_TYPES: readonly AgentEventType[] = [
	"started",

	"message",

	"tool_started",

	"tool_completed",

	"approval_requested",

	"usage",

	"completed",

	"failed",

	"cancelled",

	"raw_vendor_event",
];

// 这些 SDK 选项由本库根据请求与配置生成，nativeOptions 不能覆盖
const MANAGED_OPTION_KEYS: ReadonlySet<string> = new Set([
	"cwd",

	"env",

	"permissionMode",

	"allowedTools",

	"resume",

	"model",

	"abortController",

	"canUseTool",

	"pathToClaudeCodeExecutable",

	"includePartialMessages",

	"spawnClaudeCodeProcess",
]);

export class ClaudeCodeAgentClient implements AgentClient {
	readonly agent = AGENT_NAME;
	private readonly config: AgentConfig;
	private readonly logger: CommonLlmLogger;
	private readonly idGenerator: IdGenerator;
	private readonly environment: NodeJS.ProcessEnv;
	private readonly limiter: ConcurrencyLimiter;
	private readonly runs: Map<string, ClaudeRunState> = new Map();
	private runtimePromise: Promise<ClaudeCodeRuntime> | null = null;

	constructor(options: AgentClientOptions) {
		this.config = options.config;
		this.logger = options.logger;
		this.idGenerator = options.idGenerator;
		this.environment = options.environment ?? process.env;
		this.limiter = new ConcurrencyLimiter(this.config.max_concurrent_runs, AGENT_NAME);
	}

	async run(request: AgentRequest, options: AgentRunOptions = {}): Promise<AgentResult> {
		const state = this.createRunState(request);

		return collectAgentResult(this.executeRun(request, options, state), this.config.collect_events, () => state.exitCode);
	}

	runStream(request: AgentRequest, options: AgentRunOptions = {}): AsyncIterable<AgentEvent> {
		return this.executeRun(request, options, this.createRunState(request));
	}

	resume(sessionId: string, request: AgentRequest, options: AgentRunOptions = {}): Promise<AgentResult> {
		return this.run({
			...request,
			sessionId,
		}, options);
	}

	async interrupt(runId: string): Promise<boolean> {
		const state = this.runs.get(runId);

		if (!state) {
			return false;
		}

		await this.cancelRun(state);
		return true;
	}

	async capabilities(): Promise<AgentCapability> {
		const runtime = await this.getRuntime();

		return {
			agent: AGENT_NAME,
			implementation: "sdk",
			executablePath: runtime.executablePath,
			version: runtime.version,
			features: [
				...CLAUDE_FEATURES,
			],
			eventTypes: [
				...CLAUDE_EVENT_TYPES,
			],
			supportedOptions: [
				...runtime.supportedOptions,
			],
		};
	}

	async close(): Promise<void> {
		const cancellations: Promise<void>[] = [];

		for (const state of this.runs.values()) {
			cancellations.push(this.cancelRun(state));
		}

		await Promise.all(cancellations);
		this.runtimePromise = null;
	}

	private getRuntime(): Promise<ClaudeCodeRuntime> {
		if (this.runtimePromise === null) {
			const environment = buildChildEnvironment(this.environment, this.config.env_allowlist, {});
			this.runtimePromise = probeClaudeCode(this.config.executable_path, environment);
			this.runtimePromise.catch(() => {
				this.runtimePromise = null;
			});
		}

		return this.runtimePromise;
	}

	private createRunState(request: AgentRequest): ClaudeRunState {
		const runId = this.idGenerator.createRunId();
		const traceId = request.traceId ?? this.idGenerator.createTraceId();

		return {
			runId,
			factory: new AgentEventFactory(AGENT_NAME, runId, traceId),
			channel: new AgentEventChannel(this.config.max_buffered_events),
			abortController: new AbortController(),
			query: null,
			child: null,
			exitCode: null,
			stderrTail: "",
			limitError: null,
			isCancelRequested: false,
			isTimedOut: false,
			hasTerminal: false,
			emittedApprovalIds: new Set(),
		};
	}

	/**
	 * 执行一次 run：预检失败直接发 failed，运行中的事件经队列按顺序交给调用方
	 */
	private async *executeRun(request: AgentRequest, options: AgentRunOptions, state: ClaudeRunState): AsyncGenerator<AgentEvent> {
		let prepared: PreparedRun;

		try {
			prepared = await this.prepareRun(request, options, state);
			this.limiter.acquire();
		} catch (error) {
			yield state.factory.failed(this.toErrorInfo(error));
			return;
		}

		this.runs.set(state.runId, state);
		const removeSignalListener = this.watchCallerSignal(options.signal, state);
		const timeoutTimer = setTimeout(() => {
			state.isTimedOut = true;
			this.abortRun(state);
		}, prepared.timeoutSeconds * 1000);
		const pumpPromise = this.pumpMessages(request, prepared, state);

		try {
			for await (const event of state.channel.drain()) {
				yield event;
			}
		} finally {
			clearTimeout(timeoutTimer);
			removeSignalListener();

			if (!state.hasTerminal) {
				state.isCancelRequested = true;
				this.abortRun(state);
			}

			state.channel.close();
			await pumpPromise;
		}
	}

	private async prepareRun(request: AgentRequest, options: AgentRunOptions, state: ClaudeRunState): Promise<PreparedRun> {
		if (request.agent !== AGENT_NAME) {
			throw createError("CONFIG_INVALID", `请求的 agent 为 ${String(request.agent)}，与 ClaudeCodeAgentClient 不符`, {
				provider: AGENT_NAME,
			});
		}

		if (!request.prompt) {
			throw createError("CONFIG_INVALID", "AgentRequest.prompt 不能为空", {
				provider: AGENT_NAME,
			});
		}

		const workingDirectory = await resolveWorkingDirectory(request.workingDirectory, this.config.working_directory, AGENT_NAME);
		const runtime = await this.getRuntime();
		const allowlist = selectList(request.envAllowlist, this.config.env_allowlist);
		const authEnvironment: Record<string, string> = {
			CLAUDE_AGENT_SDK_CLIENT_APP: `common-llm/${VERSION}`,
		};

		if (this.config.auth_mode === "api_key" && this.config.api_key) {
			authEnvironment.ANTHROPIC_API_KEY = this.config.api_key;
		}

		const sdkOptions: Options = {
			...this.buildNativeOptions(request, runtime),
			cwd: workingDirectory,
			env: buildChildEnvironment(this.environment, allowlist, authEnvironment),
			pathToClaudeCodeExecutable: runtime.executablePath,
			includePartialMessages: true,
			abortController: state.abortController,
			spawnClaudeCodeProcess: (spawnOptions) => spawnMonitoredProcess(spawnOptions, {
				agent: AGENT_NAME,
				maxOutputBytes: this.config.max_output_bytes,
				maxLineBytes: this.config.max_line_bytes,
				maxStderrBytes: this.config.max_stderr_bytes,
				killGraceSeconds: this.config.kill_grace_seconds,
				onSpawned: (child) => {
					state.child = child;
					child.once("exit", (code) => {
						state.exitCode = code;
					});
				},
				onLimitExceeded: (error) => {
					state.limitError = error;
					this.abortRun(state);
				},
				onInvalidLine: (line, byteLength) => {
					state.channel.push(state.factory.rawVendorEvent("invalid_json", {
						line,
						byteLength,
					}));
				},
				onStderrTail: (tail) => {
					state.stderrTail = tail;
				},
			}),
		};
		const permissionMode = this.resolvePermissionMode(request, runtime);
		const allowedTools = selectList(request.allowedTools, this.config.allowed_tools);

		if (permissionMode !== null) {
			sdkOptions.permissionMode = permissionMode as Options["permissionMode"];
		}

		if (allowedTools.length > 0) {
			sdkOptions.allowedTools = allowedTools;
		}

		if (request.sessionId) {
			sdkOptions.resume = request.sessionId;
		}

		if (request.model) {
			sdkOptions.model = request.model;
		}

		if (options.approvalHandler) {
			sdkOptions.canUseTool = this.buildCanUseTool(options.approvalHandler, state);
		}

		return {
			runtime,
			options: sdkOptions,
			timeoutSeconds: request.timeoutSeconds ?? this.config.timeout_seconds,
		};
	}

	/**
	 * 取有效权限模式；默认值 default 不在 CLI 取值中时不传参，显式指定的不支持取值报错
	 */
	private resolvePermissionMode(request: AgentRequest, runtime: ClaudeCodeRuntime): string | null {
		const permissionMode = request.permissionMode ?? this.config.permission_mode;
		const supportedModes = runtime.permissionModes;

		if (permissionMode === "bypassPermissions") {
			this.logger.warn("Claude Code 使用 bypassPermissions 权限模式运行", {
				agent: AGENT_NAME,
			});
		}

		if (supportedModes === null || supportedModes.includes(permissionMode)) {
			return permissionMode;
		}

		if (permissionMode === "default") {
			return null;
		}

		throw createError("SDK_UNSUPPORTED_FEATURE", `Claude Code ${runtime.version} 不支持权限模式 ${permissionMode}`, {
			provider: AGENT_NAME,
		});
	}

	/**
	 * 合并配置与请求的 nativeOptions，本库管理的键报 CONFIG_INVALID，maxTurns 需要 CLI 支持 --max-turns
	 */
	private buildNativeOptions(request: AgentRequest, runtime: ClaudeCodeRuntime): Options {
		const nativeOptions: Record<string, unknown> = {
			...this.config.native_options,
			...request.nativeOptions,
		};

		for (const key of Object.keys(nativeOptions)) {
			if (MANAGED_OPTION_KEYS.has(key)) {
				throw createError("CONFIG_INVALID", `nativeOptions 的键 ${key} 由本库管理，不能覆盖`, {
					provider: AGENT_NAME,
				});
			}
		}

		if (nativeOptions.maxTurns !== undefined && !runtime.supportedOptions.includes("--max-turns")) {
			throw createError("SDK_UNSUPPORTED_FEATURE", `Claude Code ${runtime.version} 不支持 --max-turns`, {
				provider: AGENT_NAME,
			});
		}

		return nativeOptions as Options;
	}

	private buildCanUseTool(handler: ApprovalHandler, state: ClaudeRunState): CanUseTool {
		return async (toolName, input, context): Promise<PermissionResult> => {
			const approvalId = context.toolUseID;
			let decision: ApprovalDecision = "decline";

			try {
				decision = await handler({
					approvalId,
					kind: "tool",
					summary: toolName,
					toolName,
					input,
					runId: state.runId,
					sessionId: state.factory.sessionId,
				});
			} catch (error) {
				this.logger.warn("审批处理器抛出异常，按拒绝处理", {
					agent: AGENT_NAME,
					runId: state.runId,
					cause: error instanceof Error ? error.name : "unknown",
				});
			}

			state.emittedApprovalIds.add(approvalId);
			state.channel.push(state.factory.approvalRequested(approvalId, "tool", toolName, decision));

			if (decision === "approve") {
				return {
					behavior: "allow",
					updatedInput: input,
				};
			}

			if (decision === "approve_for_session") {
				return {
					behavior: "allow",
					updatedInput: input,
					updatedPermissions: context.suggestions ?? [],
				};
			}

			return {
				behavior: "deny",
				message: "调用方拒绝了该工具调用",
				interrupt: decision === "cancel",
			};
		};
	}

	/**
	 * 读取 SDK 消息并转换为事件；SDK 抛错或进程结束而没有 result 时按状态补发终止事件
	 */
	private async pumpMessages(request: AgentRequest, prepared: PreparedRun, state: ClaudeRunState): Promise<void> {
		const converter = new ClaudeCodeEventConverter(state.factory, this.config.max_tool_output_chars, state.emittedApprovalIds);
		let failure: unknown = null;

		try {
			const sdk = await import("@anthropic-ai/claude-agent-sdk");
			state.query = sdk.query({
				prompt: request.prompt,
				options: prepared.options,
			});

			for await (const message of state.query) {
				if (state.limitError !== null) {
					break;
				}

				for (const event of converter.convert(message, state.isCancelRequested)) {
					this.emitEvent(state, event);
				}

				if (state.hasTerminal) {
					break;
				}
			}
		} catch (error) {
			failure = error;
		} finally {
			if (!state.hasTerminal) {
				this.emitEvent(state, this.buildFailureTerminal(state, failure));
			}

			if (state.child) {
				await terminateProcessTree(state.child, this.config.kill_grace_seconds);
			}

			this.limiter.release();
			this.runs.delete(state.runId);
			state.channel.close();
		}
	}

	private emitEvent(state: ClaudeRunState, event: AgentEvent): void {
		if (state.hasTerminal) {
			return;
		}

		if (isTerminalAgentEvent(event)) {
			state.hasTerminal = true;
		}

		state.channel.push(event);
	}

	private buildFailureTerminal(state: ClaudeRunState, failure: unknown): AgentEvent {
		if (state.isCancelRequested) {
			return state.factory.cancelled();
		}

		if (state.isTimedOut) {
			return state.factory.failed(createError("TIMEOUT", "Claude Code run 超时", {
				provider: AGENT_NAME,
			}).info);
		}

		if (state.limitError) {
			return state.factory.failed(state.limitError.info);
		}

		if (isCommonLlmError(failure)) {
			return state.factory.failed(failure.info);
		}

		const stderrTail = redactText(state.stderrTail.trim(), [
			this.config.api_key,
		]);

		if (state.exitCode !== null && state.exitCode !== 0) {
			return state.factory.failed(createError("CLI_PROCESS_FAILED", `Claude Code 进程以退出码 ${state.exitCode} 结束：${stderrTail}`, {
				provider: AGENT_NAME,
				statusCode: state.exitCode,
				cause: failure,
				secrets: [
					this.config.api_key,
				],
			}).info);
		}

		if (failure !== null) {
			return state.factory.failed(createError("CLI_PROCESS_FAILED", "Claude Code 进程异常结束", {
				provider: AGENT_NAME,
				cause: failure,
				secrets: [
					this.config.api_key,
				],
			}).info);
		}

		return state.factory.failed(createError("CLI_PROTOCOL_ERROR", "Claude Code 进程结束但没有输出 result 消息", {
			provider: AGENT_NAME,
		}).info);
	}

	private watchCallerSignal(signal: AbortSignal | undefined, state: ClaudeRunState): () => void {
		if (!signal) {
			return () => undefined;
		}

		const onAbort = (): void => {
			void this.cancelRun(state);
		};

		if (signal.aborted) {
			onAbort();
			return () => undefined;
		}

		signal.addEventListener("abort", onAbort, {
			once: true,
		});

		return () => signal.removeEventListener("abort", onAbort);
	}

	/**
	 * 先请求 SDK 中断当前 turn，在宽限时间内仍未结束再终止进程组
	 */
	private async cancelRun(state: ClaudeRunState): Promise<void> {
		if (state.hasTerminal || state.isCancelRequested) {
			return;
		}

		state.isCancelRequested = true;

		if (state.query) {
			let graceTimer: NodeJS.Timeout | undefined;
			const interruptPromise = state.query.interrupt().catch(() => undefined);
			const gracePromise = new Promise<void>((resolve) => {
				graceTimer = setTimeout(resolve, this.config.kill_grace_seconds * 1000);
			});
			await Promise.race([
				interruptPromise,

				gracePromise,
			]);
			clearTimeout(graceTimer);
		}

		this.abortRun(state);
	}

	private abortRun(state: ClaudeRunState): void {
		state.abortController.abort();

		if (state.child) {
			void terminateProcessTree(state.child, this.config.kill_grace_seconds);
		}
	}

	private toErrorInfo(error: unknown): CommonLlmError["info"] {
		if (isCommonLlmError(error)) {
			return error.info;
		}

		return createError("INTERNAL_ERROR", "Claude Code run 预检失败", {
			provider: AGENT_NAME,
			cause: error,
		}).info;
	}
}
