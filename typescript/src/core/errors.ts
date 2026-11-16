/**
 * 定义统一错误分类、ErrorInfo 结构与 CommonLlmError 异常
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

import { inspect } from "node:util";

import { redactText } from "./redaction.js";

export type ErrorCategory =
	| "CONFIG_INVALID"
	| "AUTH_MISSING"
	| "AUTH_FAILED"
	| "PERMISSION_DENIED"
	| "MODEL_UNAVAILABLE"
	| "RATE_LIMITED"
	| "QUOTA_EXCEEDED"
	| "TIMEOUT"
	| "CANCELLED"
	| "NETWORK_ERROR"
	| "PROVIDER_ERROR"
	| "SDK_UNSUPPORTED_FEATURE"
	| "CLI_NOT_FOUND"
	| "CLI_VERSION_UNSUPPORTED"
	| "CLI_PROCESS_FAILED"
	| "CLI_PROTOCOL_ERROR"
	| "AGENT_APPROVAL_REQUIRED"
	| "SANDBOX_DENIED"
	| "INTERNAL_ERROR";

export interface ErrorInfo {
	category: ErrorCategory;
	message: string;
	provider: string | null;
	retriable: boolean;
	requestId: string | null;
	vendorCode: string | null;
	statusCode: number | null;
	cause: string | null;
}

export interface ErrorOptions {
	provider?: string | null;
	retriable?: boolean;
	requestId?: string | null;
	vendorCode?: string | null;
	statusCode?: number | null;
	cause?: unknown;
	secrets?: readonly (string | null | undefined)[];
}

export interface HttpErrorClassification {
	category: ErrorCategory;
	retriable: boolean;
}

export const ERROR_CATEGORIES: readonly ErrorCategory[] = [
	"CONFIG_INVALID",

	"AUTH_MISSING",

	"AUTH_FAILED",

	"PERMISSION_DENIED",

	"MODEL_UNAVAILABLE",

	"RATE_LIMITED",

	"QUOTA_EXCEEDED",

	"TIMEOUT",

	"CANCELLED",

	"NETWORK_ERROR",

	"PROVIDER_ERROR",

	"SDK_UNSUPPORTED_FEATURE",

	"CLI_NOT_FOUND",

	"CLI_VERSION_UNSUPPORTED",

	"CLI_PROCESS_FAILED",

	"CLI_PROTOCOL_ERROR",

	"AGENT_APPROVAL_REQUIRED",

	"SANDBOX_DENIED",

	"INTERNAL_ERROR",
];

const RETRIABLE_CATEGORIES: ReadonlySet<ErrorCategory> = new Set<ErrorCategory>([
	"RATE_LIMITED",

	"TIMEOUT",

	"NETWORK_ERROR",
]);

export class CommonLlmError extends Error {
	readonly info: ErrorInfo;

	constructor(info: ErrorInfo) {
		super(info.message);
		this.name = "CommonLlmError";
		this.info = info;
	}

	get category(): ErrorCategory {
		return this.info.category;
	}

	get retriable(): boolean {
		return this.info.retriable;
	}

	toJSON(): ErrorInfo {
		return {
			...this.info,
		};
	}

	[inspect.custom](): string {
		return `CommonLlmError ${JSON.stringify(this.info)}`;
	}
}

/**
 * 按分类创建 CommonLlmError，消息与底层原因都会做密钥脱敏
 */
export function createError(category: ErrorCategory, message: string, options: ErrorOptions = {}): CommonLlmError {
	const secrets = options.secrets ?? [];
	let retriable = RETRIABLE_CATEGORIES.has(category);

	if (options.retriable !== undefined) {
		retriable = options.retriable;
	}

	return new CommonLlmError({
		category,
		message: redactText(message, secrets),
		provider: options.provider ?? null,
		retriable,
		requestId: options.requestId ?? null,
		vendorCode: options.vendorCode ?? null,
		statusCode: options.statusCode ?? null,
		cause: describeCause(options.cause, secrets),
	});
}

export function isCommonLlmError(value: unknown): value is CommonLlmError {
	return value instanceof CommonLlmError;
}

/**
 * 把底层异常转为脱敏后的 "类型: 消息" 文本
 */
export function describeCause(cause: unknown, secrets: readonly (string | null | undefined)[] = []): string | null {
	if (cause === undefined || cause === null) {
		return null;
	}

	if (cause instanceof Error) {
		return redactText(`${cause.name}: ${cause.message}`, secrets);
	}

	return redactText(String(cause), secrets);
}

/**
 * 按 HTTP 状态码与厂商错误码判定统一分类，规则见 api-contract 5.2
 */
export function classifyHttpStatus(statusCode: number, isQuotaExceeded: boolean, isAuthFailure: boolean): HttpErrorClassification {
	if (statusCode === 401 || isAuthFailure) {
		return {
			category: "AUTH_FAILED",
			retriable: false,
		};
	}

	if (statusCode === 403) {
		return {
			category: "PERMISSION_DENIED",
			retriable: false,
		};
	}

	if (statusCode === 404) {
		return {
			category: "MODEL_UNAVAILABLE",
			retriable: false,
		};
	}

	if (statusCode === 408) {
		return {
			category: "TIMEOUT",
			retriable: true,
		};
	}

	if (statusCode === 429 && isQuotaExceeded) {
		return {
			category: "QUOTA_EXCEEDED",
			retriable: false,
		};
	}

	if (statusCode === 429) {
		return {
			category: "RATE_LIMITED",
			retriable: true,
		};
	}

	if (statusCode >= 500) {
		return {
			category: "PROVIDER_ERROR",
			retriable: true,
		};
	}

	return {
		category: "PROVIDER_ERROR",
		retriable: false,
	};
}
