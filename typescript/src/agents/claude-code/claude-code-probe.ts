/**
 * 探测 Claude Code 可执行文件、版本与 claude -p --help 支持的参数集合
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

import { createError } from "../../core/errors.js";
import {
	compareVersions,
	parseVersion,
	resolveExecutable,
	runCommand,
} from "../../core/process.js";

import type { SemanticVersion } from "../../core/process.js";

export interface ClaudeCodeRuntime {
	executablePath: string;
	version: string;
	supportedOptions: string[];
	permissionModes: string[] | null;
}

// 与锁定的 Agent SDK 0.3.325 内置 CLI 版本一致，控制协议按该版本实现
export const MIN_CLAUDE_CODE_VERSION: SemanticVersion = {
	major: 2,
	minor: 1,
	patch: 325,
	text: "2.1.325",
};

/**
 * 依次解析可执行文件、执行 --version 与 -p --help，任何一步失败都按对应分类报错
 */
export async function probeClaudeCode(executable: string, environment: Record<string, string>): Promise<ClaudeCodeRuntime> {
	const executablePath = await resolveExecutable(executable, environment.PATH, "claude_code");
	const versionOutput = await runProbe(executablePath, ["--version"], environment);
	const version = parseVersion(versionOutput);

	if (version === null) {
		throw createError("CLI_PROTOCOL_ERROR", "无法解析 claude --version 的输出", {
			provider: "claude_code",
		});
	}

	if (compareVersions(version, MIN_CLAUDE_CODE_VERSION) < 0) {
		throw createError("CLI_VERSION_UNSUPPORTED", `Claude Code ${version.text} 低于最低支持版本 ${MIN_CLAUDE_CODE_VERSION.text}`, {
			provider: "claude_code",
		});
	}

	const helpOutput = await runProbe(executablePath, ["-p", "--help"], environment);

	return {
		executablePath,
		version: version.text,
		supportedOptions: parseHelpOptions(helpOutput),
		permissionModes: parsePermissionModes(helpOutput),
	};
}

async function runProbe(executablePath: string, args: string[], environment: Record<string, string>): Promise<string> {
	try {
		const output = await runCommand(executablePath, args, environment, 30);

		if (output.exitCode !== 0) {
			throw createError("CLI_PROCESS_FAILED", `claude ${args.join(" ")} 退出码为 ${output.exitCode}`, {
				provider: "claude_code",
				statusCode: output.exitCode,
			});
		}

		return output.stdout;
	} catch (error) {
		if (error instanceof Error && error.name === "CommonLlmError") {
			throw error;
		}

		throw createError("CLI_PROCESS_FAILED", `执行 claude ${args.join(" ")} 失败`, {
			provider: "claude_code",
			cause: error,
		});
	}
}

/**
 * 从帮助文本的选项列中提取全部 --xxx 参数名
 */
export function parseHelpOptions(helpText: string): string[] {
	const options: Set<string> = new Set();

	for (const line of helpText.split("\n")) {
		const trimmed = line.trim();

		if (!trimmed.startsWith("-")) {
			continue;
		}

		const specification = trimmed.split(/\s{2,}/)[0] ?? "";

		for (const matched of specification.matchAll(/--[a-zA-Z][a-zA-Z0-9-]*/g)) {
			options.add(matched[0]);
		}
	}

	return [
		...options,
	].sort();
}

/**
 * 解析 --permission-mode 的 choices 列表，帮助文本没有列出时返回 null
 */
export function parsePermissionModes(helpText: string): string[] | null {
	const lines = helpText.split("\n");
	const startIndex = lines.findIndex((line) => line.trim().startsWith("--permission-mode"));

	if (startIndex < 0) {
		return null;
	}

	const sectionLines: string[] = [
		lines[startIndex] ?? "",
	];

	for (const line of lines.slice(startIndex + 1)) {
		if (line.trim().startsWith("-")) {
			break;
		}

		sectionLines.push(line.trim());
	}

	const choicesMatch = /choices:([^)]*)\)/.exec(sectionLines.join(" "));

	if (!choicesMatch) {
		return null;
	}

	const modes: string[] = [];

	for (const matched of (choicesMatch[1] ?? "").matchAll(/"([^"]+)"/g)) {
		modes.push(matched[1] ?? "");
	}

	return modes;
}
