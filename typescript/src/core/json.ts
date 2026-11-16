/**
 * 提供读取厂商 JSON 结构的安全取值工具
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

export type JsonRecord = Record<string, unknown>;

export function isRecord(value: unknown): value is JsonRecord {
	return value !== null && typeof value === "object" && !Array.isArray(value);
}

/**
 * 读取对象字段，非对象时返回空对象，便于逐层取值
 */
export function readRecord(source: unknown, key: string): JsonRecord {
	if (!isRecord(source)) {
		return {};
	}

	const value = source[key];

	if (isRecord(value)) {
		return value;
	}

	return {};
}

export function readString(source: unknown, key: string): string | null {
	if (!isRecord(source)) {
		return null;
	}

	const value = source[key];

	if (typeof value === "string") {
		return value;
	}

	return null;
}

export function readNumber(source: unknown, key: string): number | null {
	if (!isRecord(source)) {
		return null;
	}

	const value = source[key];

	if (typeof value === "number") {
		return value;
	}

	return null;
}

export function readArray(source: unknown, key: string): unknown[] {
	if (!isRecord(source)) {
		return [];
	}

	const value = source[key];

	if (Array.isArray(value)) {
		return value;
	}

	return [];
}

export function compactJson(value: unknown): string {
	return JSON.stringify(value);
}

/**
 * 把 SDK 返回的对象转为纯 JSON 副本，用于 rawResponse 与 raw_vendor_event
 */
export function toPlainRecord(value: unknown): JsonRecord {
	const serialized = JSON.stringify(value);

	if (serialized === undefined) {
		return {};
	}

	const parsed: unknown = JSON.parse(serialized);

	if (isRecord(parsed)) {
		return parsed;
	}

	return {};
}
