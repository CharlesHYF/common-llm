/**
 * 为 Claude Agent SDK 创建受监控的 Claude Code 子进程，限制输出并在取消时结束整个进程组
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

import { spawn } from "node:child_process";
import { PassThrough } from "node:stream";

import { createError } from "../../core/errors.js";
import { terminateProcessTree, trackChildProcess } from "../../core/process.js";

import type { SpawnedProcess, SpawnOptions } from "@anthropic-ai/claude-agent-sdk";
import type { ChildProcess } from "node:child_process";
import type { Readable, Writable } from "node:stream";
import type { CommonLlmError } from "../../core/errors.js";

export interface ProcessMonitor {
	agent: string;
	maxOutputBytes: number;
	maxLineBytes: number;
	maxStderrBytes: number;
	killGraceSeconds: number;
	onSpawned(child: ChildProcess): void;
	onLimitExceeded(error: CommonLlmError): void;
	onInvalidLine(line: string, byteLength: number): void;
	onStderrTail(tail: string): void;
}

type ExitListener = (code: number | null, signal: NodeJS.Signals | null) => void;

type ErrorListener = (error: Error) => void;

/**
 * 包装 ChildProcess：stdout 先经过字节与行长度检查再交给 SDK，kill 作用于整个进程组
 */
class MonitoredProcess implements SpawnedProcess {
	readonly stdin: Writable;
	readonly stdout: Readable;

	constructor(
		private readonly child: ChildProcess,
		monitoredStdout: Readable,
		private readonly killGraceSeconds: number,
	) {
		if (!child.stdin) {
			throw createError("CLI_PROCESS_FAILED", "Claude Code 子进程没有 stdin");
		}

		this.stdin = child.stdin;
		this.stdout = monitoredStdout;
	}

	get killed(): boolean {
		return this.child.killed;
	}

	get exitCode(): number | null {
		return this.child.exitCode;
	}

	get signalCode(): NodeJS.Signals | null {
		return this.child.signalCode;
	}

	kill(signal: NodeJS.Signals): boolean {
		if (signal === "SIGKILL") {
			void terminateProcessTree(this.child, 0);
			return true;
		}

		void terminateProcessTree(this.child, this.killGraceSeconds);
		return true;
	}

	on(event: "exit", listener: ExitListener): void;
	on(event: "error", listener: ErrorListener): void;
	on(event: "exit" | "error", listener: ExitListener | ErrorListener): void {
		this.child.on(event, listener);
	}

	once(event: "exit", listener: ExitListener): void;
	once(event: "error", listener: ErrorListener): void;
	once(event: "exit" | "error", listener: ExitListener | ErrorListener): void {
		this.child.once(event, listener);
	}

	off(event: "exit", listener: ExitListener): void;
	off(event: "error", listener: ErrorListener): void;
	off(event: "exit" | "error", listener: ExitListener | ErrorListener): void {
		this.child.off(event, listener);
	}
}

/**
 * 以参数数组启动子进程，Unix 下放入独立进程组，取消时整组结束
 */
export function spawnMonitoredProcess(options: SpawnOptions, monitor: ProcessMonitor): SpawnedProcess {
	const child = spawn(options.command, options.args, {
		cwd: options.cwd,
		env: options.env,
		stdio: [
			"pipe",

			"pipe",

			"pipe",
		],
		detached: process.platform !== "win32",
		windowsHide: true,
	});
	trackChildProcess(child);
	monitor.onSpawned(child);

	if (options.signal) {
		options.signal.addEventListener("abort", () => {
			void terminateProcessTree(child, monitor.killGraceSeconds);
		}, {
			once: true,
		});
	}

	const monitoredStdout = new PassThrough();
	watchStdout(child, monitoredStdout, monitor);
	watchStderr(child, monitor);

	return new MonitoredProcess(child, monitoredStdout, monitor.killGraceSeconds);
}

function watchStdout(child: ChildProcess, target: PassThrough, monitor: ProcessMonitor): void {
	let totalBytes = 0;
	let lineChunks: Buffer[] = [];
	let lineBytes = 0;
	let isLimitReported = false;

	const reportLimit = (error: CommonLlmError): void => {
		if (isLimitReported) {
			return;
		}

		isLimitReported = true;
		monitor.onLimitExceeded(error);
	};

	const inspectLine = (): void => {
		const line = Buffer.concat(lineChunks).toString("utf8").trim();
		const byteLength = lineBytes;
		lineChunks = [];
		lineBytes = 0;

		if (line === "") {
			return;
		}

		try {
			JSON.parse(line);
		} catch {
			// 诊断事件只保留非法行的前 200 个字符，避免把超长内容写入事件
			monitor.onInvalidLine(line.slice(0, 200), byteLength);
		}
	};

	child.stdout?.on("data", (chunk: Buffer) => {
		totalBytes += chunk.length;

		if (totalBytes > monitor.maxOutputBytes) {
			reportLimit(createError("CLI_PROCESS_FAILED", `stdout 累计输出超过上限 ${monitor.maxOutputBytes} 字节`, {
				provider: monitor.agent,
			}));
		}

		let offset = 0;

		while (offset < chunk.length && !isLimitReported) {
			const newlineIndex = chunk.indexOf("\n", offset);
			const segmentEnd = newlineIndex < 0 ? chunk.length : newlineIndex;
			const segment = chunk.subarray(offset, segmentEnd);
			lineBytes += segment.length;

			if (lineBytes > monitor.maxLineBytes) {
				reportLimit(createError("CLI_PROTOCOL_ERROR", `单行 JSON 超过上限 ${monitor.maxLineBytes} 字节`, {
					provider: monitor.agent,
				}));
				break;
			}

			lineChunks.push(segment);

			if (newlineIndex < 0) {
				break;
			}

			inspectLine();
			offset = newlineIndex + 1;
		}

		target.write(chunk);
	});

	child.stdout?.on("end", () => {
		if (lineBytes > 0 && !isLimitReported) {
			inspectLine();
		}

		target.end();
	});

	child.stdout?.on("error", (error: Error) => {
		target.destroy(error);
	});
}

function watchStderr(child: ChildProcess, monitor: ProcessMonitor): void {
	let tail = "";

	child.stderr?.on("data", (chunk: Buffer) => {
		tail += chunk.toString("utf8");

		if (tail.length > monitor.maxStderrBytes) {
			tail = tail.slice(tail.length - monitor.maxStderrBytes);
		}

		monitor.onStderrTail(tail);
	});
}
