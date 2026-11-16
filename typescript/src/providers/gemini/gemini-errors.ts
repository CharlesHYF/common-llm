/**
 * 把 Gemini SDK 抛出的异常转换为统一错误分类
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

import { ApiError } from "@google/genai";

import { classifyHttpStatus, createError } from "../../core/errors.js";
import { isRecord, readRecord, readString } from "../../core/json.js";

import type { CommonLlmError } from "../../core/errors.js";

/**
 * ApiError 的 message 是响应体 JSON 文本，vendorCode 取 error.status，400 且详情含 API_KEY_INVALID 视为认证失败
 */
export function convertGeminiError(error: unknown, provider: string, secrets: readonly (string | undefined)[]): CommonLlmError {
	if (error instanceof ApiError) {
		const errorBody = parseErrorBody(error.message);
		const vendorCode = readString(errorBody, "status");
		const isAuthFailure = error.status === 400 && JSON.stringify(errorBody).includes("API_KEY_INVALID");
		const classification = classifyHttpStatus(error.status, false, isAuthFailure);

		return createError(classification.category, readString(errorBody, "message") ?? error.message, {
			provider,
			retriable: classification.retriable,
			vendorCode,
			statusCode: error.status,
			cause: error,
			secrets,
		});
	}

	if (error instanceof Error && error.name === "AbortError") {
		return createError("TIMEOUT", "请求超时", {
			provider,
			cause: error,
			secrets,
		});
	}

	if (error instanceof TypeError) {
		return createError("NETWORK_ERROR", "连接 Gemini 服务失败", {
			provider,
			cause: error,
			secrets,
		});
	}

	return createError("PROVIDER_ERROR", "Gemini SDK 调用失败", {
		provider,
		cause: error,
		secrets,
	});
}

function parseErrorBody(message: string): Record<string, unknown> {
	const jsonStart = message.indexOf("{");

	if (jsonStart < 0) {
		return {};
	}

	try {
		const parsed: unknown = JSON.parse(message.slice(jsonStart));

		if (isRecord(parsed)) {
			return readRecord(parsed, "error");
		}
	} catch {
		return {};
	}

	return {};
}
