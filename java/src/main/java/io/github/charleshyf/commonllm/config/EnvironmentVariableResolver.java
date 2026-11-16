package io.github.charleshyf.commonllm.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 替换配置字符串中的环境变量引用
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class EnvironmentVariableResolver {

	private static final String DEFAULT_SEPARATOR = ":-";
	private static final String REFERENCE_PREFIX = "${";
	private static final String ESCAPED_PREFIX = "$${";
	private static final char REFERENCE_SUFFIX = '}';

	private final Map<String, String> environment;
	private final Map<String, List<String>> missingVariablesByPath = new LinkedHashMap<>();

	public EnvironmentVariableResolver(Map<String, String> environment) {
		this.environment = environment;
	}

	/**
	 * 递归替换配置树中全部字符串值，键名不替换
	 */
	public Map<String, Object> resolveTree(Map<String, Object> tree) {
		return resolveMap(tree, "");
	}

	/**
	 * 返回引用了未设置环境变量的字段路径与变量名
	 */
	public Map<String, List<String>> getMissingVariablesByPath() {
		return missingVariablesByPath;
	}

	/**
	 * 替换单个字符串中的变量引用，转义写法输出字面量
	 */
	public String resolveText(String text, String fieldPath) {
		StringBuilder resolved = new StringBuilder();
		int position = 0;

		while (position < text.length()) {
			boolean isEscaped = text.startsWith(ESCAPED_PREFIX, position);

			if (!isEscaped && !text.startsWith(REFERENCE_PREFIX, position)) {
				resolved.append(text.charAt(position));
				position++;
				continue;
			}

			int endIndex = text.indexOf(REFERENCE_SUFFIX, position);

			if (endIndex < 0) {
				resolved.append(text.substring(position));
				break;
			}

			if (isEscaped) {
				resolved.append(text, position + 1, endIndex + 1);
			} else {
				String expression = text.substring(position + REFERENCE_PREFIX.length(), endIndex);
				resolved.append(resolveExpression(expression, fieldPath));
			}

			position = endIndex + 1;
		}

		return resolved.toString();
	}

	private String resolveExpression(String expression, String fieldPath) {
		String variableName = expression;
		String defaultValue = null;
		int separatorIndex = expression.indexOf(DEFAULT_SEPARATOR);

		if (separatorIndex >= 0) {
			variableName = expression.substring(0, separatorIndex);
			defaultValue = expression.substring(separatorIndex + DEFAULT_SEPARATOR.length());
		}

		String value = environment.get(variableName);

		if (value != null) {
			return value;
		}

		if (defaultValue != null) {
			return defaultValue;
		}

		missingVariablesByPath.computeIfAbsent(fieldPath, path -> new ArrayList<>()).add(variableName);
		return "";
	}

	private Map<String, Object> resolveMap(Map<String, Object> source, String parentPath) {
		Map<String, Object> resolved = new LinkedHashMap<>();

		for (Map.Entry<String, Object> entry : source.entrySet()) {
			String fieldPath = parentPath.isEmpty() ? entry.getKey() : parentPath + "." + entry.getKey();
			resolved.put(entry.getKey(), resolveValue(entry.getValue(), fieldPath));
		}

		return resolved;
	}

	@SuppressWarnings("unchecked")
	private Object resolveValue(Object value, String fieldPath) {
		if (value instanceof String) {
			return resolveText((String) value, fieldPath);
		}

		if (value instanceof Map) {
			return resolveMap((Map<String, Object>) value, fieldPath);
		}

		if (value instanceof List) {
			List<Object> resolvedItems = new ArrayList<>();
			int index = 0;

			for (Object listItem : (List<Object>) value) {
				resolvedItems.add(resolveValue(listItem, fieldPath + "[" + index + "]"));
				index++;
			}

			return resolvedItems;
		}

		return value;
	}
}
