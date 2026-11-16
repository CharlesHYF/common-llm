package io.github.charleshyf.commonllm.core.process;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按白名单组装子进程环境变量
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class ProcessEnvironmentBuilder {

	// 子进程运行必需的系统变量，其余变量只有列入白名单才会继承
	private static final List<String> ESSENTIAL_VARIABLES = List.of(
		"PATH",
		"HOME",
		"USER",
		"LOGNAME",
		"SHELL",
		"TMPDIR",
		"LANG",
		"LC_ALL",
		"LC_CTYPE",
		"TERM",
		"SystemRoot",
		"TEMP",
		"TMP",
		"USERPROFILE",
		"APPDATA",
		"LOCALAPPDATA"
	);

	private ProcessEnvironmentBuilder() {
	}

	/**
	 * 组装环境变量：必需变量、白名单变量，再叠加认证等显式变量
	 */
	public static Map<String, String> build(Map<String, String> parentEnvironment, List<String> allowlist, Map<String, String> explicitVariables) {
		Map<String, String> environment = new LinkedHashMap<>();

		for (String variableName : ESSENTIAL_VARIABLES) {
			copyVariable(parentEnvironment, environment, variableName);
		}

		if (allowlist != null) {
			for (String variableName : allowlist) {
				copyVariable(parentEnvironment, environment, variableName);
			}
		}

		if (explicitVariables != null) {
			environment.putAll(explicitVariables);
		}

		return environment;
	}

	private static void copyVariable(Map<String, String> source, Map<String, String> target, String variableName) {
		String value = source.get(variableName);

		if (value != null) {
			target.put(variableName, value);
		}
	}
}
