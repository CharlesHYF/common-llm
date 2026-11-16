/**
 * 基于官方 Codex TypeScript SDK 实现 Codex AgentClient，SDK 无审批回调因此固定 approvalPolicy=never
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
import {
	compareVersions,
	parseVersion,
	resolveExecutable,
	runCommand,
} from "../../core/process.js";
import { redactText } from "../../core/redaction.js";
import { CodexExecEventConverter } from "./codex-mapper.js";

import type { CodexOptions, ThreadOptions, TurnOptions } from "@openai/codex-sdk";
import type { AgentClient, AgentClientOptions } from "../../core/clients.js";
import type { CommonLlmError, ErrorInfo } from "../../core/errors.js";
import type { SemanticVersion } from "../../core/process.js";
import type {
	AgentCapability,
	AgentEvent,
	AgentEventType,
	AgentFeature,
	AgentRequest,
	AgentResult,
	AgentRunOptions,
} from "../../core/types.js";
import type { AgentConfig } from "../../config/types.js";
import type { CommonLlmLogger } from "../../observability/logger.js";
import type { IdGenerator } from "../../observability/ids.js";

interface CodexRuntime {
	executablePath: string;
	version: string;
}

interface CodexRunState {
	runId: string;
	factory: AgentEventFactory;
	channel: AgentEventChannel;
	abortController: AbortController;
	exitCode: number | null;
	outputBytes: number;
	limitError: CommonLlmError | null;
	isCancelRequested: boolean;
	isTimedOut: boolean;
	hasTerminal: boolean;
}

interface PreparedRun {
	codexOptions: CodexOptions;
	threadOptions: ThreadOptions;
	turnOptions: TurnOptions;
	workingDirectory: string;
	timeoutSeconds: number;
}

const AGENT_NAME = "codex";

// 与锁定的 @openai/codex-sdk 0.169.0 依赖的 @openai/codex 版本一致
export const MIN_CODEX_VERSION: SemanticVersion = {
	major: 0,
	minor: 169,
	patch: 0,
	text: "0.169.0",
};

const CODEX_FEATURES: readonly AgentFeature[] = [
	"streaming",

	"session_resume",

	"interrupt",
];

const CODEX_EVENT_TYPES: readonly AgentEventType[] = [
	"started",

	"message",

	"tool_started",

	"tool_completed",

	"file_changed",

	"usage",

	"completed",

	"failed",

	"cancelled",

	"raw_vendor_event",
];

// nativeOptions 中可透传给 ThreadOptions 的键
const THREAD_OPTION_KEYS: ReadonlySet<string> = new Set([
	"modelReasoningEffort",

	"networkAccessEnabled",

	"webSearchMode",

	"webSearchEnabled",

	"skipGitRepoCheck",

	"additionalDirectories",

	"threadSource",
]);

// nativeOptions 中可透传给 TurnOptions 的键
const TURN_OPTION_KEYS: ReadonlySet<string> = new Set([
	"outputSchema",

	"cyberAccessProgram",
]);

// nativeOptions 中可透传给 CodexOptions 的键
const CODEX_OPTION_KEYS: ReadonlySet<string> = new Set([
	"config",

	"configOverrides",

	"baseUrl",
]);

// 这些选项由本库根据请求与配置生成，nativeOptions 不能覆盖
const MANAGED_OPTION_KEYS: ReadonlySet<string> = new Set([
	"model",

	"sandboxMode",

	"workingDirectory",

	"approvalPolicy",

	"apiKey",

	"env",

	"codexPathOverride",

	"signal",
]);

export class CodexAgentClient implements AgentClient {
	readonly agent = AGENT_NAME;
	private readonly config: AgentConfig;
	private readonly logger: CommonLlmLogger;
	private readonly idGenerator: IdGenerator;
	private readonly environment: NodeJS.ProcessEnv;
	private readonly limiter: ConcurrencyLimiter;
	private readonly runs: Map<string, CodexRunState> = new Map();
	private runtimePromise: Promise<CodexRuntime> | null = null;

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

		this.cancelRun(state);
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
				...CODEX_FEATURES,
			],
			eventTypes: [
				...CODEX_EVENT_TYPES,
			],
			supportedOptions: null,
		};
	}

	async close(): Promise<void> {
		for (const state of this.runs.values()) {
			this.cancelRun(state);
		}

		this.runtimePromise = null;
	}

	/**
	 * 解析可执行文件并执行 --version，低于最低支持版本时报 CLI_VERSION_UNSUPPORTED
	 */
	private getRuntime(): Promise<CodexRuntime> {
		if (this.runtimePromise === null) {
			this.runtimePromise = this.probeRuntime();
			this.runtimePromise.catch(() => {
				this.runtimePromise = null;
			});
		}

		return this.runtimePromise;
	}

	private async probeRuntime(): Promise<CodexRuntime> {
		const environment = buildChildEnvironment(this.environment, this.config.env_allowlist, {});
		const executablePath = await resolveExecutable(this.config.executable_path, environment.PATH, AGENT_NAME);
		let versionText: string;

		try {
			const output = await runCommand(executablePath, ["--version"], environment, 30);
			versionText = output.stdout;
		} catch (error) {
			throw createError("CLI_PROCESS_FAILED", "执行 codex --version 失败", {
				provider: AGENT_NAME,
				cause: error,
			});
		}

		const version = parseVersion(versionText);

		if (version === null) {
			throw createError("CLI_PROTOCOL_ERROR", "无法解析 codex --version 的输出", {
				provider: AGENT_NAME,
			});
		}

		if (compareVersions(version, MIN_CODEX_VERSION) < 0) {
			throw createError("CLI_VERSION_UNSUPPORTED", `Codex ${version.text} 低于最低支持版本 ${MIN_CODEX_VERSION.text}`, {
				provider: AGENT_NAME,
			});
		}

		return {
			executablePath,
			version: version.text,
		};
	}

	private createRunState(request: AgentRequest): CodexRunState {
		const runId = this.idGenerator.createRunId();
		const traceId = request.traceId ?? this.idGenerator.createTraceId();

		return {
			runId,
			factory: new AgentEventFactory(AGENT_NAME, runId, traceId),
			channel: new AgentEventChannel(this.config.max_buffered_events),
			abortController: new AbortController(),
			exitCode: null,
			outputBytes: 0,
			limitError: null,
			isCancelRequested: false,
			isTimedOut: false,
			hasTerminal: false,
		};
	}

	private async *executeRun(request: AgentRequest, options: AgentRunOptions, state: CodexRunState): AsyncGenerator<AgentEvent> {
		let prepared: PreparedRun;

		try {
			prepared = await this.prepareRun(request, options);
			this.limiter.acquire();
		} catch (error) {
			yield state.factory.failed(this.toErrorInfo(error));
			return;
		}

		this.runs.set(state.runId, state);
		const removeSignalListener = this.watchCallerSignal(options.signal, state);
		const timeoutTimer = setTimeout(() => {
			state.isTimedOut = true;
			state.abortController.abort();
		}, prepared.timeoutSeconds * 1000);
		const pumpPromise = this.pumpEvents(request, prepared, state);

		try {
			for await (const event of state.channel.drain()) {
				yield event;
			}
		} finally {
			clearTimeout(timeoutTimer);
			removeSignalListener();

			if (!state.hasTerminal) {
				this.cancelRun(state);
			}

			state.channel.close();
			await pumpPromise;
		}
	}

	private async prepareRun(request: AgentRequest, options: AgentRunOptions): Promise<PreparedRun> {
		if (request.agent !== AGENT_NAME) {
			throw createError("CONFIG_INVALID", `请求的 agent 为 ${String(request.agent)}，与 CodexAgentClient 不符`, {
				provider: AGENT_NAME,
			});
		}

		if (!request.prompt) {
			throw createError("CONFIG_INVALID", "AgentRequest.prompt 不能为空", {
				provider: AGENT_NAME,
			});
		}

		if (options.approvalHandler) {
			throw createError("SDK_UNSUPPORTED_FEATURE", "Codex TypeScript SDK 不提供审批回调，无法使用 approvalHandler", {
				provider: AGENT_NAME,
			});
		}

		const workingDirectory = await resolveWorkingDirectory(request.workingDirectory, this.config.working_directory, AGENT_NAME);
		const runtime = await this.getRuntime();
		const sandboxMode = request.sandboxMode ?? this.config.sandbox_mode;

		if (sandboxMode === "danger-full-access") {
			this.logger.warn("Codex 使用 danger-full-access 沙箱模式运行", {
				agent: AGENT_NAME,
			});
		}

		const extraEnvironment: Record<string, string> = {};

		if (this.config.home_directory) {
			extraEnvironment.CODEX_HOME = this.config.home_directory;
		}

		const codexOptions: CodexOptions = {
			codexPathOverride: runtime.executablePath,
			env: buildChildEnvironment(this.environment, selectList(request.envAllowlist, this.config.env_allowlist), extraEnvironment),
		};

		if (this.config.auth_mode === "api_key" && this.config.api_key) {
			codexOptions.apiKey = this.config.api_key;
		}

		const threadOptions: ThreadOptions = {
			sandboxMode,
			workingDirectory,
			approvalPolicy: "never",
			networkAccessEnabled: false,
		};

		if (request.model) {
			threadOptions.model = request.model;
		}

		const turnOptions: TurnOptions = {};
		this.applyNativeOptions(request, codexOptions, threadOptions, turnOptions);

		return {
			codexOptions,
			threadOptions,
			turnOptions,
			workingDirectory,
			timeoutSeconds: request.timeoutSeconds ?? this.config.timeout_seconds,
		};
	}

	/**
	 * 按键名把 nativeOptions 分配到 CodexOptions / ThreadOptions / TurnOptions，未知键报 SDK_UNSUPPORTED_FEATURE
	 */
	private applyNativeOptions(request: AgentRequest, codexOptions: CodexOptions, threadOptions: ThreadOptions, turnOptions: TurnOptions): void {
		const nativeOptions: Record<string, unknown> = {
			...this.config.native_options,
			...request.nativeOptions,
		};
		const codexTarget = codexOptions as Record<string, unknown>;
		const threadTarget = threadOptions as Record<string, unknown>;
		const turnTarget = turnOptions as Record<string, unknown>;

		for (const [key, value] of Object.entries(nativeOptions)) {
			if (MANAGED_OPTION_KEYS.has(key)) {
				throw createError("CONFIG_INVALID", `nativeOptions 的键 ${key} 由本库管理，不能覆盖`, {
					provider: AGENT_NAME,
				});
			}

			if (THREAD_OPTION_KEYS.has(key)) {
				threadTarget[key] = value;
				continue;
			}

			if (TURN_OPTION_KEYS.has(key)) {
				turnTarget[key] = value;
				continue;
			}

			if (CODEX_OPTION_KEYS.has(key)) {
				codexTarget[key] = value;
				continue;
			}

			throw createError("SDK_UNSUPPORTED_FEATURE", `Codex TypeScript SDK 不支持选项 ${key}`, {
				provider: AGENT_NAME,
			});
		}
	}

	private async pumpEvents(request: AgentRequest, prepared: PreparedRun, state: CodexRunState): Promise<void> {
		const converter = new CodexExecEventConverter(state.factory, request.model ?? null, prepared.workingDirectory, this.config.max_tool_output_chars);
		let failure: unknown = null;

		try {
			const sdk = await import("@openai/codex-sdk");
			const codex = new sdk.Codex(prepared.codexOptions);
			const thread = request.sessionId
				? codex.resumeThread(request.sessionId, prepared.threadOptions)
				: codex.startThread(prepared.threadOptions);
			const streamed = await thread.runStreamed(request.prompt, {
				...prepared.turnOptions,
				signal: state.abortController.signal,
			});

			for await (const event of streamed.events) {
				this.checkOutputLimits(event, state);

				for (const agentEvent of converter.convert(event)) {
					this.emitEvent(state, agentEvent);
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

			this.limiter.release();
			this.runs.delete(state.runId);
			state.channel.close();
		}
	}

	/**
	 * SDK 不暴露原始 stdout，按事件序列化后的字节数近似统计输出量与单行长度
	 */
	private checkOutputLimits(event: unknown, state: CodexRunState): void {
		const lineBytes = Buffer.byteLength(JSON.stringify(event), "utf8") + 1;
		state.outputBytes += lineBytes;

		if (lineBytes > this.config.max_line_bytes) {
			state.limitError = createError("CLI_PROTOCOL_ERROR", `单行 JSON 超过上限 ${this.config.max_line_bytes} 字节`, {
				provider: AGENT_NAME,
			});
		} else if (state.outputBytes > this.config.max_output_bytes) {
			state.limitError = createError("CLI_PROCESS_FAILED", `stdout 累计输出超过上限 ${this.config.max_output_bytes} 字节`, {
				provider: AGENT_NAME,
			});
		}

		if (state.limitError !== null) {
			state.abortController.abort();
			throw state.limitError;
		}
	}

	private emitEvent(state: CodexRunState, event: AgentEvent): void {
		if (state.hasTerminal) {
			return;
		}

		if (isTerminalAgentEvent(event)) {
			state.hasTerminal = true;
		}

		state.channel.push(event);
	}

	/**
	 * 根据取消、超时、输出超限与 SDK 异常文本判定终止事件
	 */
	private buildFailureTerminal(state: CodexRunState, failure: unknown): AgentEvent {
		if (state.isCancelRequested) {
			return state.factory.cancelled();
		}

		if (state.isTimedOut) {
			return state.factory.failed(createError("TIMEOUT", "Codex run 超时", {
				provider: AGENT_NAME,
			}).info);
		}

		if (state.limitError) {
			return state.factory.failed(state.limitError.info);
		}

		if (isCommonLlmError(failure)) {
			return state.factory.failed(failure.info);
		}

		const message = failure instanceof Error ? failure.message : "";
		const secrets = [
			this.config.api_key,
		];

		if (message.startsWith("Failed to parse item")) {
			return state.factory.failed(createError("CLI_PROTOCOL_ERROR", "codex exec 输出了非法 JSON 行", {
				provider: AGENT_NAME,
				cause: failure,
				secrets,
			}).info);
		}

		const exitMatch = /Codex Exec exited with code (\d+): ([\s\S]*)/.exec(message);

		if (exitMatch) {
			state.exitCode = Number(exitMatch[1]);
			const stderrTail = (exitMatch[2] ?? "").slice(-this.config.max_stderr_bytes).trim();

			return state.factory.failed(createError("CLI_PROCESS_FAILED", `Codex 进程以退出码 ${state.exitCode} 结束：${redactText(stderrTail, secrets)}`, {
				provider: AGENT_NAME,
				statusCode: state.exitCode,
				secrets,
			}).info);
		}

		if (failure instanceof Error && (failure as NodeJS.ErrnoException).code === "ENOENT") {
			return state.factory.failed(createError("CLI_NOT_FOUND", "找不到 Codex 可执行文件", {
				provider: AGENT_NAME,
				cause: failure,
			}).info);
		}

		if (failure !== null) {
			return state.factory.failed(createError("CLI_PROCESS_FAILED", "Codex 进程异常结束", {
				provider: AGENT_NAME,
				cause: failure,
				secrets,
			}).info);
		}

		return state.factory.failed(createError("CLI_PROTOCOL_ERROR", "Codex 进程结束但没有输出 turn.completed 事件", {
			provider: AGENT_NAME,
		}).info);
	}

	private watchCallerSignal(signal: AbortSignal | undefined, state: CodexRunState): () => void {
		if (!signal) {
			return () => undefined;
		}

		const onAbort = (): void => {
			this.cancelRun(state);
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
	 * SDK 没有 turn 级中断，取消即通过 AbortSignal 结束 codex exec 进程
	 */
	private cancelRun(state: CodexRunState): void {
		if (state.hasTerminal || state.isCancelRequested) {
			return;
		}

		state.isCancelRequested = true;
		state.abortController.abort();
	}

	private toErrorInfo(error: unknown): ErrorInfo {
		if (isCommonLlmError(error)) {
			return error.info;
		}

		return createError("INTERNAL_ERROR", "Codex run 预检失败", {
			provider: AGENT_NAME,
			cause: error,
		}).info;
	}
}
