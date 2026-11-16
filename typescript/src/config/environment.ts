/**
 * 替换配置字符串值中的 ${NAME}、${NAME:-default} 与 $${NAME} 环境变量引用
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

export type MissingVariables = Map<string, string[]>;

// 依次匹配转义写法 $${...} 与普通引用 ${NAME} / ${NAME:-default}
const REFERENCE_PATTERN = /\$\$\{([^}]*)\}|\$\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?\}/g;

/**
 * 递归替换字符串值，只替换值不替换键名，缺失变量替换为空字符串并按字段路径记录
 */
export function substituteEnvironmentVariables(value: unknown, environment: NodeJS.ProcessEnv, fieldPath: string, missing: MissingVariables): unknown {
	if (typeof value === "string") {
		return substituteText(value, environment, fieldPath, missing);
	}

	if (Array.isArray(value)) {
		const items: unknown[] = [];
		let index = 0;

		for (const element of value) {
			items.push(substituteEnvironmentVariables(element, environment, `${fieldPath}[${index}]`, missing));
			index += 1;
		}

		return items;
	}

	if (value !== null && typeof value === "object") {
		const substituted: Record<string, unknown> = {};

		for (const [key, fieldValue] of Object.entries(value)) {
			const childPath = fieldPath === "" ? key : `${fieldPath}.${key}`;
			substituted[key] = substituteEnvironmentVariables(fieldValue, environment, childPath, missing);
		}

		return substituted;
	}

	return value;
}

function substituteText(text: string, environment: NodeJS.ProcessEnv, fieldPath: string, missing: MissingVariables): string {
	return text.replace(REFERENCE_PATTERN, (matched: string, escapedBody: string | undefined, name: string | undefined, defaultValue: string | undefined) => {
		if (escapedBody !== undefined) {
			return matched.slice(1);
		}

		const variableName = name ?? "";
		const resolved = environment[variableName];

		// 与 shell 一致：变量为空字符串且带默认值时使用默认值
		if (resolved !== undefined && (resolved !== "" || defaultValue === undefined)) {
			return resolved;
		}

		if (defaultValue !== undefined) {
			return defaultValue;
		}

		const names = missing.get(fieldPath) ?? [];
		names.push(variableName);
		missing.set(fieldPath, names);

		return "";
	});
}
