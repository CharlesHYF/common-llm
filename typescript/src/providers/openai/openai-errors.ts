/**
 * 把 OpenAI SDK 抛出的异常转换为统一错误分类
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

import {
	APIConnectionError,
	APIConnectionTimeoutError,
	APIError,
	APIUserAbortError,
} from "openai";

import { classifyHttpStatus, createError } from "../../core/errors.js";
import { readString } from "../../core/json.js";

import type { CommonLlmError } from "../../core/errors.js";

/**
 * 按 SDK 异常类型与 HTTP 状态码映射错误，vendorCode 取 error.code，为空时取 error.type
 */
export function convertOpenAiError(error: unknown, provider: string, secrets: readonly (string | undefined)[]): CommonLlmError {
	if (error instanceof APIUserAbortError) {
		return createError("CANCELLED", "请求已被取消", {
			provider,
			cause: error,
			secrets,
		});
	}

	if (error instanceof APIConnectionTimeoutError) {
		return createError("TIMEOUT", "请求超时", {
			provider,
			cause: error,
			secrets,
		});
	}

	if (error instanceof APIConnectionError) {
		return createError("NETWORK_ERROR", "连接 OpenAI 服务失败", {
			provider,
			cause: error,
			secrets,
		});
	}

	if (error instanceof APIError && typeof error.status === "number") {
		const vendorCode = error.code ?? error.type ?? readString(error.error, "code") ?? readString(error.error, "type");
		const classification = classifyHttpStatus(error.status, error.code === "insufficient_quota", false);

		return createError(classification.category, error.message, {
			provider,
			retriable: classification.retriable,
			requestId: error.requestID ?? null,
			vendorCode: vendorCode ?? null,
			statusCode: error.status,
			cause: error,
			secrets,
		});
	}

	return createError("PROVIDER_ERROR", "OpenAI SDK 调用失败", {
		provider,
		cause: error,
		secrets,
	});
}
