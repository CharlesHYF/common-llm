/**
 * 提供可执行文件定位、探测命令执行与子进程组终止等进程工具
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

import { execFile } from "node:child_process";
import { constants as fsConstants } from "node:fs";
import { access, stat } from "node:fs/promises";
import path from "node:path";

import { createError } from "./errors.js";

import type { ChildProcess } from "node:child_process";

export interface CommandOutput {
	exitCode: number;
	stdout: string;
	stderr: string;
}

export interface SemanticVersion {
	major: number;
	minor: number;
	patch: number;
	text: string;
}

const MILLISECONDS_PER_SECOND = 1000;

const trackedProcesses: Set<ChildProcess> = new Set();
let isExitHookInstalled = false;

/**
 * 解析可执行文件路径，带目录分隔符时直接校验，否则在 PATH 中查找
 */
export async function resolveExecutable(executable: string, searchPath: string | undefined, provider: string): Promise<string> {
	const candidates: string[] = [];

	if (executable.includes("/") || executable.includes("\\")) {
		candidates.push(path.resolve(executable));
	} else {
		for (const directory of (searchPath ?? "").split(path.delimiter)) {
			if (directory === "") {
				continue;
			}

			candidates.push(path.join(directory, executable));
		}
	}

	for (const candidate of candidates) {
		if (await isExecutableFile(candidate)) {
			return candidate;
		}
	}

	throw createError("CLI_NOT_FOUND", `找不到可执行文件：${executable}`, {
		provider,
	});
}

async function isExecutableFile(candidate: string): Promise<boolean> {
	try {
		const fileStat = await stat(candidate);

		if (!fileStat.isFile()) {
			return false;
		}

		await access(candidate, fsConstants.X_OK);
		return true;
	} catch {
		return false;
	}
}

/**
 * 以参数数组执行探测命令，不经过 shell，超时后由 Node 结束进程
 */
export function runCommand(executable: string, args: readonly string[], env: NodeJS.ProcessEnv, timeoutSeconds: number): Promise<CommandOutput> {
	return new Promise((resolve, reject) => {
		execFile(executable, [...args], {
			env,
			timeout: Math.round(timeoutSeconds * MILLISECONDS_PER_SECOND),
			// 探测命令只读取版本与帮助文本，限制输出避免异常程序占满内存
			maxBuffer: 4 * 1024 * 1024,
			windowsHide: true,
			encoding: "utf8",
		}, (error, stdout, stderr) => {
			if (error && typeof error.code !== "number") {
				reject(error);
				return;
			}

			let exitCode = 0;

			if (error && typeof error.code === "number") {
				exitCode = error.code;
			}

			resolve({
				exitCode,
				stdout,
				stderr,
			});
		});
	});
}

/**
 * 从版本输出中解析第一个 x.y.z 版本号
 */
export function parseVersion(text: string): SemanticVersion | null {
	const matched = /(\d+)\.(\d+)\.(\d+)/.exec(text);

	if (!matched) {
		return null;
	}

	return {
		major: Number(matched[1]),
		minor: Number(matched[2]),
		patch: Number(matched[3]),
		text: `${matched[1]}.${matched[2]}.${matched[3]}`,
	};
}

/**
 * 比较两个版本号，返回负数、0 或正数
 */
export function compareVersions(left: SemanticVersion, right: SemanticVersion): number {
	if (left.major !== right.major) {
		return left.major - right.major;
	}

	if (left.minor !== right.minor) {
		return left.minor - right.minor;
	}

	return left.patch - right.patch;
}

/**
 * 记录由本库创建的子进程，父进程退出时整组结束，避免遗留孤儿进程
 */
export function trackChildProcess(child: ChildProcess): void {
	trackedProcesses.add(child);
	child.once("exit", () => trackedProcesses.delete(child));

	if (isExitHookInstalled) {
		return;
	}

	isExitHookInstalled = true;
	process.once("exit", () => {
		for (const tracked of trackedProcesses) {
			sendSignal(tracked, "SIGKILL");
		}
	});
}

export function hasProcessExited(child: ChildProcess): boolean {
	return child.exitCode !== null || child.signalCode !== null;
}

/**
 * 先发 SIGTERM，等待宽限时间后仍未退出则发 SIGKILL，Unix 下作用于整个进程组
 */
export function terminateProcessTree(child: ChildProcess, graceSeconds: number): Promise<void> {
	if (hasProcessExited(child)) {
		return Promise.resolve();
	}

	return new Promise((resolve) => {
		const forceTimer = setTimeout(() => {
			sendSignal(child, "SIGKILL");
		}, Math.round(graceSeconds * MILLISECONDS_PER_SECOND));

		child.once("exit", () => {
			clearTimeout(forceTimer);
			resolve();
		});
		sendSignal(child, "SIGTERM");
	});
}

function sendSignal(child: ChildProcess, signal: NodeJS.Signals): void {
	if (hasProcessExited(child) || child.pid === undefined) {
		return;
	}

	try {
		if (process.platform === "win32") {
			child.kill(signal);
			return;
		}

		process.kill(-child.pid, signal);
	} catch {
		try {
			child.kill(signal);
		} catch {
			return;
		}
	}
}
