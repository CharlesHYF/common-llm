/**
 * 按字段合并多个配置来源，高优先级来源出现的字段覆盖低优先级字段
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

import type { CommonLlmConfigInput } from "./types.js";

/**
 * 依次合并来源，后面的来源优先级更高；对象逐字段合并，数组与标量整体替换
 */
export function mergeConfigInputs(sources: readonly CommonLlmConfigInput[]): CommonLlmConfigInput {
	let merged: Record<string, unknown> = {};

	for (const source of sources) {
		merged = mergeRecords(merged, source as Record<string, unknown>);
	}

	return merged as CommonLlmConfigInput;
}

function mergeRecords(lower: Record<string, unknown>, higher: Record<string, unknown>): Record<string, unknown> {
	const result: Record<string, unknown> = {
		...lower,
	};

	for (const [key, higherValue] of Object.entries(higher)) {
		if (higherValue === undefined) {
			continue;
		}

		const lowerValue = result[key];

		if (isPlainRecord(lowerValue) && isPlainRecord(higherValue)) {
			result[key] = mergeRecords(lowerValue, higherValue);
			continue;
		}

		result[key] = higherValue;
	}

	return result;
}

function isPlainRecord(value: unknown): value is Record<string, unknown> {
	return value !== null && typeof value === "object" && !Array.isArray(value);
}
