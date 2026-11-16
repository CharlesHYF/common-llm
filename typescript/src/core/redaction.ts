/**
 * 提供日志、错误与配置序列化时使用的密钥脱敏工具
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

import { inspect } from "node:util";

export const REDACTED_VALUE = "***";

const SECRET_KEY_NAMES: ReadonlySet<string> = new Set([
	"api_key",

	"apikey",

	"authorization",

	"access_token",

	"token",

	"password",

	"secret",
]);

const SECRET_PATTERNS: readonly RegExp[] = [
	/sk-[A-Za-z0-9_-]{8,}/g,

	/(Bearer\s+)[A-Za-z0-9._~+/=-]+/gi,

	/(x-api-key["':=\s]+)[A-Za-z0-9._-]+/gi,

	/(x-goog-api-key["':=\s]+)[A-Za-z0-9._-]+/gi,
];

/**
 * 替换文本中出现的已知密钥与常见密钥格式
 */
export function redactText(text: string, secrets: readonly (string | null | undefined)[] = []): string {
	let result = text;

	for (const secret of secrets) {
		// 过短的值替换后会误伤普通文本，只脱敏 4 个字符以上的已知密钥
		if (!secret || secret.length < 4) {
			continue;
		}

		result = result.split(secret).join(REDACTED_VALUE);
	}

	for (const pattern of SECRET_PATTERNS) {
		result = result.replace(pattern, (matched: string, prefix?: string) => {
			if (typeof prefix === "string" && matched.startsWith(prefix)) {
				return `${prefix}${REDACTED_VALUE}`;
			}

			return REDACTED_VALUE;
		});
	}

	return result;
}

export function isSecretKey(key: string): boolean {
	return SECRET_KEY_NAMES.has(key.toLowerCase());
}

/**
 * 深拷贝对象并把密钥字段替换为 ***
 */
export function redactObject(value: unknown): unknown {
	if (Array.isArray(value)) {
		const items: unknown[] = [];

		for (const element of value) {
			items.push(redactObject(element));
		}

		return items;
	}

	if (value === null || typeof value !== "object") {
		return value;
	}

	const copied: Record<string, unknown> = {};

	for (const [key, fieldValue] of Object.entries(value)) {
		if (isSecretKey(key) && fieldValue !== undefined && fieldValue !== null && fieldValue !== "") {
			copied[key] = REDACTED_VALUE;
			continue;
		}

		copied[key] = redactObject(fieldValue);
	}

	return copied;
}

/**
 * 给对象挂上不可枚举的 toJSON / toString / inspect，序列化时输出脱敏副本
 */
export function attachRedactedSerialization<TValue extends object>(target: TValue): TValue {
	const describeRedacted = (): unknown => redactObject(target);

	Object.defineProperty(target, "toJSON", {
		value: describeRedacted,
		enumerable: false,
		configurable: true,
	});

	Object.defineProperty(target, "toString", {
		value: () => JSON.stringify(describeRedacted()),
		enumerable: false,
		configurable: true,
	});

	Object.defineProperty(target, inspect.custom, {
		value: () => describeRedacted(),
		enumerable: false,
		configurable: true,
	});

	return target;
}
