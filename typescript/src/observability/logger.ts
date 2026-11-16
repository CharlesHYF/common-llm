/**
 * 定义可注入的日志接口与按级别过滤的默认实现
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

export type LogLevel = "DEBUG" | "INFO" | "WARN" | "ERROR";

export type LogFields = Record<string, unknown>;

export interface CommonLlmLogger {
	debug(message: string, fields?: LogFields): void;
	info(message: string, fields?: LogFields): void;
	warn(message: string, fields?: LogFields): void;
	error(message: string, fields?: LogFields): void;
}

const LOG_LEVEL_WEIGHTS: Record<LogLevel, number> = {
	DEBUG: 10,
	INFO: 20,
	WARN: 30,
	ERROR: 40,
};

// 默认不输出任何日志，避免库在调用方不知情时写控制台
export const SILENT_LOGGER: CommonLlmLogger = {
	debug: () => undefined,
	info: () => undefined,
	warn: () => undefined,
	error: () => undefined,
};

/**
 * 包装调用方的 logger，低于配置级别的日志直接丢弃
 */
export function createLeveledLogger(target: CommonLlmLogger, level: LogLevel): CommonLlmLogger {
	const minimumWeight = LOG_LEVEL_WEIGHTS[level];

	return {
		debug: (message, fields) => {
			if (LOG_LEVEL_WEIGHTS.DEBUG >= minimumWeight) {
				target.debug(message, fields);
			}
		},
		info: (message, fields) => {
			if (LOG_LEVEL_WEIGHTS.INFO >= minimumWeight) {
				target.info(message, fields);
			}
		},
		warn: (message, fields) => {
			if (LOG_LEVEL_WEIGHTS.WARN >= minimumWeight) {
				target.warn(message, fields);
			}
		},
		error: (message, fields) => {
			if (LOG_LEVEL_WEIGHTS.ERROR >= minimumWeight) {
				target.error(message, fields);
			}
		},
	};
}

/**
 * 创建写入标准错误输出的 JSON 行日志，供示例与本地调试使用
 */
export function createConsoleLogger(): CommonLlmLogger {
	const writeLine = (level: LogLevel, message: string, fields?: LogFields): void => {
		const line = JSON.stringify({
			level,
			message,
			...fields,
		});
		process.stderr.write(`${line}\n`);
	};

	return {
		debug: (message, fields) => writeLine("DEBUG", message, fields),
		info: (message, fields) => writeLine("INFO", message, fields),
		warn: (message, fields) => writeLine("WARN", message, fields),
		error: (message, fields) => writeLine("ERROR", message, fields),
	};
}
