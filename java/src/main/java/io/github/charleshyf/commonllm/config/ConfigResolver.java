package io.github.charleshyf.commonllm.config;

import io.github.charleshyf.commonllm.core.CommonLlmException;
import io.github.charleshyf.commonllm.core.ErrorCategory;
import io.github.charleshyf.commonllm.core.ErrorInfo;
import io.github.charleshyf.commonllm.core.SecretMasker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按字段合并多个配置来源并补默认值、做校验
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class ConfigResolver {

	private static final Set<String> ROOT_KEYS = Set.of("common", "providers", "agents");
	private static final Set<String> COMMON_KEYS = Set.of("timeout_seconds", "connect_timeout_seconds", "max_retries", "log_level");
	private static final Set<String> LOG_LEVELS = Set.of("DEBUG", "INFO", "WARN", "ERROR");
	private static final Set<String> PROVIDER_KEYS = Set.of("type", "api", "enabled", "api_key", "base_url", "default_model", "timeout_seconds", "max_retries", "native_options");
	private static final Set<String> PROVIDER_TYPES = Set.of(ProviderConfig.TYPE_OPENAI, ProviderConfig.TYPE_ANTHROPIC, ProviderConfig.TYPE_GEMINI);
	private static final Set<String> OPENAI_APIS = Set.of(ProviderConfig.API_RESPONSES, ProviderConfig.API_CHAT_COMPLETIONS);
	private static final Set<String> AGENT_NAMES = Set.of(AgentConfig.AGENT_CLAUDE_CODE, AgentConfig.AGENT_CODEX);
	private static final Set<String> AUTH_MODES = Set.of(AgentConfig.AUTH_MODE_API_KEY, AgentConfig.AUTH_MODE_CLI_EXISTING_SESSION, AgentConfig.AUTH_MODE_VENDOR_SUPPORTED);
	private static final Set<String> SANDBOX_MODES = Set.of("read-only", "workspace-write", "danger-full-access");
	private static final Set<String> AGENT_KEYS = Set.of("enabled", "auth_mode", "api_key", "executable_path", "home_directory", "working_directory", "timeout_seconds", "permission_mode", "allowed_tools", "sandbox_mode", "env_allowlist", "max_concurrent_runs", "max_output_bytes", "max_line_bytes", "max_stderr_bytes", "max_buffered_events", "max_tool_output_chars", "kill_grace_seconds", "collect_events", "native_options");

	private ConfigResolver() {
	}

	/**
	 * 按字段深度合并：后面的来源覆盖前面同名字段，未出现的字段保留
	 */
	@SuppressWarnings("unchecked")
	public static Map<String, Object> merge(Map<String, Object> lower, Map<String, Object> higher) {
		Map<String, Object> merged = new LinkedHashMap<>(lower);

		for (Map.Entry<String, Object> entry : higher.entrySet()) {
			Object lowerValue = merged.get(entry.getKey());
			Object higherValue = entry.getValue();

			if (lowerValue instanceof Map && higherValue instanceof Map) {
				merged.put(entry.getKey(), merge((Map<String, Object>) lowerValue, (Map<String, Object>) higherValue));
				continue;
			}

			merged.put(entry.getKey(), higherValue);
		}

		return merged;
	}

	/**
	 * 合并来源（优先级从低到高）后补默认值并校验，缺失变量信息用于错误提示
	 */
	public static CommonLlmConfig resolve(List<Map<String, Object>> sourcesFromLowToHigh, Map<String, List<String>> missingVariablesByPath) {
		Map<String, Object> mergedTree = new LinkedHashMap<>();

		for (Map<String, Object> source : sourcesFromLowToHigh) {
			if (source != null) {
				mergedTree = merge(mergedTree, source);
			}
		}

		ConfigTreeReader reader = new ConfigTreeReader(missingVariablesByPath);
		reader.requireKnownKeys(mergedTree, ROOT_KEYS, "");
		CommonConfig common = parseCommon(reader, reader.readMap(mergedTree, "common", ""));
		Map<String, ProviderConfig> providers = new LinkedHashMap<>();
		Map<String, Object> providerTrees = reader.readMap(mergedTree, "providers", "");

		for (Map.Entry<String, Object> providerEntry : providerTrees.entrySet()) {
			String fieldPath = "providers." + providerEntry.getKey();
			Map<String, Object> providerTree = reader.asMap(providerEntry.getValue(), fieldPath);
			providers.put(providerEntry.getKey(), parseProvider(reader, providerEntry.getKey(), providerTree, common, fieldPath));
		}

		Map<String, AgentConfig> agents = new LinkedHashMap<>();
		Map<String, Object> agentTrees = reader.readMap(mergedTree, "agents", "");

		for (Map.Entry<String, Object> agentEntry : agentTrees.entrySet()) {
			String fieldPath = "agents." + agentEntry.getKey();

			if (!AGENT_NAMES.contains(agentEntry.getKey())) {
				throw invalid(fieldPath + " 不是支持的 Agent，只支持 claude_code 与 codex");
			}

			Map<String, Object> agentTree = reader.asMap(agentEntry.getValue(), fieldPath);
			agents.put(agentEntry.getKey(), parseAgent(reader, agentEntry.getKey(), agentTree, fieldPath));
		}

		return new CommonLlmConfig(common, providers, agents);
	}

	private static CommonConfig parseCommon(ConfigTreeReader reader, Map<String, Object> commonTree) {
		reader.requireKnownKeys(commonTree, COMMON_KEYS, "common");
		double timeoutSeconds = reader.readPositiveNumber(commonTree, "timeout_seconds", "common", CommonConfig.DEFAULT_TIMEOUT_SECONDS);
		double connectTimeoutSeconds = reader.readPositiveNumber(commonTree, "connect_timeout_seconds", "common", CommonConfig.DEFAULT_CONNECT_TIMEOUT_SECONDS);
		int maxRetries = (int) reader.readNonNegativeInteger(commonTree, "max_retries", "common", CommonConfig.DEFAULT_MAX_RETRIES);
		String logLevel = reader.readEnum(commonTree, "log_level", "common", LOG_LEVELS, CommonConfig.DEFAULT_LOG_LEVEL);
		return new CommonConfig(timeoutSeconds, connectTimeoutSeconds, maxRetries, logLevel);
	}

	private static ProviderConfig parseProvider(ConfigTreeReader reader, String instanceName, Map<String, Object> providerTree, CommonConfig common, String fieldPath) {
		reader.requireKnownKeys(providerTree, PROVIDER_KEYS, fieldPath);
		String defaultType = PROVIDER_TYPES.contains(instanceName) ? instanceName : null;
		String type = reader.readEnum(providerTree, "type", fieldPath, PROVIDER_TYPES, defaultType);

		if (type == null) {
			throw invalid(fieldPath + ".type 必填：实例名不是 openai / anthropic / gemini 时必须指定 type");
		}

		String api = reader.readEnum(providerTree, "api", fieldPath, OPENAI_APIS, ProviderConfig.API_RESPONSES);

		if (providerTree.containsKey("api") && !ProviderConfig.TYPE_OPENAI.equals(type)) {
			throw invalid(fieldPath + ".api 只适用于 type=openai");
		}

		boolean isEnabled = reader.readBoolean(providerTree, "enabled", fieldPath, false);
		String apiKey = reader.readString(providerTree, "api_key", fieldPath);
		String baseUrl = emptyToNull(reader.readString(providerTree, "base_url", fieldPath));
		String defaultModel = emptyToNull(reader.readString(providerTree, "default_model", fieldPath));
		double timeoutSeconds = reader.readPositiveNumber(providerTree, "timeout_seconds", fieldPath, common.getTimeoutSeconds());
		int maxRetries = (int) reader.readNonNegativeInteger(providerTree, "max_retries", fieldPath, common.getMaxRetries());
		Map<String, Object> nativeOptions = reader.readMap(providerTree, "native_options", fieldPath);

		if (isEnabled && (apiKey == null || apiKey.isEmpty())) {
			throw new CommonLlmException(ErrorInfo.builder(ErrorCategory.AUTH_MISSING, fieldPath + ".api_key 为空" + reader.describeMissing(fieldPath + ".api_key")).provider(instanceName).build());
		}

		SecretMasker.registerSecret(apiKey);
		return new ProviderConfig(instanceName, type, api, isEnabled, emptyToNull(apiKey), baseUrl, defaultModel, timeoutSeconds, common.getConnectTimeoutSeconds(), maxRetries, nativeOptions);
	}

	private static AgentConfig parseAgent(ConfigTreeReader reader, String name, Map<String, Object> agentTree, String fieldPath) {
		reader.requireKnownKeys(agentTree, AGENT_KEYS, fieldPath);
		AgentConfig.Builder builder = AgentConfig.builder(name);
		boolean isEnabled = reader.readBoolean(agentTree, "enabled", fieldPath, false);
		String authMode = reader.readEnum(agentTree, "auth_mode", fieldPath, AUTH_MODES, AgentConfig.AUTH_MODE_CLI_EXISTING_SESSION);
		String apiKey = emptyToNull(reader.readString(agentTree, "api_key", fieldPath));
		String homeDirectory = emptyToNull(reader.readString(agentTree, "home_directory", fieldPath));
		builder.enabled(isEnabled).authMode(authMode).apiKey(apiKey).homeDirectory(homeDirectory);
		String executablePath = emptyToNull(reader.readString(agentTree, "executable_path", fieldPath));

		if (executablePath != null) {
			builder.executablePath(executablePath);
		}

		builder.workingDirectory(emptyToNull(reader.readString(agentTree, "working_directory", fieldPath)));
		builder.timeoutSeconds(reader.readPositiveNumber(agentTree, "timeout_seconds", fieldPath, AgentConfig.DEFAULT_TIMEOUT_SECONDS));
		String permissionMode = emptyToNull(reader.readString(agentTree, "permission_mode", fieldPath));
		builder.permissionMode(permissionMode == null ? AgentConfig.DEFAULT_PERMISSION_MODE : permissionMode);
		builder.allowedTools(reader.readStringList(agentTree, "allowed_tools", fieldPath));
		builder.sandboxMode(reader.readEnum(agentTree, "sandbox_mode", fieldPath, SANDBOX_MODES, AgentConfig.DEFAULT_SANDBOX_MODE));
		builder.envAllowlist(reader.readStringList(agentTree, "env_allowlist", fieldPath));
		builder.maxConcurrentRuns((int) reader.readPositiveInteger(agentTree, "max_concurrent_runs", fieldPath, AgentConfig.DEFAULT_MAX_CONCURRENT_RUNS));
		builder.maxOutputBytes(reader.readPositiveInteger(agentTree, "max_output_bytes", fieldPath, AgentConfig.DEFAULT_MAX_OUTPUT_BYTES));
		builder.maxLineBytes((int) reader.readPositiveInteger(agentTree, "max_line_bytes", fieldPath, AgentConfig.DEFAULT_MAX_LINE_BYTES));
		builder.maxStderrBytes((int) reader.readNonNegativeInteger(agentTree, "max_stderr_bytes", fieldPath, AgentConfig.DEFAULT_MAX_STDERR_BYTES));
		builder.maxBufferedEvents((int) reader.readPositiveInteger(agentTree, "max_buffered_events", fieldPath, AgentConfig.DEFAULT_MAX_BUFFERED_EVENTS));
		builder.maxToolOutputChars((int) reader.readNonNegativeInteger(agentTree, "max_tool_output_chars", fieldPath, AgentConfig.DEFAULT_MAX_TOOL_OUTPUT_CHARS));
		builder.killGraceSeconds(reader.readNonNegativeNumber(agentTree, "kill_grace_seconds", fieldPath, AgentConfig.DEFAULT_KILL_GRACE_SECONDS));
		builder.collectEvents(reader.readBoolean(agentTree, "collect_events", fieldPath, false));
		builder.nativeOptions(reader.readMap(agentTree, "native_options", fieldPath));

		if (isEnabled && AgentConfig.AUTH_MODE_API_KEY.equals(authMode) && apiKey == null) {
			throw new CommonLlmException(ErrorInfo.builder(ErrorCategory.AUTH_MISSING, fieldPath + ".api_key 为空：auth_mode=api_key 时必填" + reader.describeMissing(fieldPath + ".api_key")).provider(name).build());
		}

		if (isEnabled && AgentConfig.AGENT_CODEX.equals(name) && AgentConfig.AUTH_MODE_API_KEY.equals(authMode) && homeDirectory == null) {
			throw invalid(fieldPath + ".home_directory 为空：Codex 使用 api_key 认证时必须指定独立的 home_directory" + reader.describeMissing(fieldPath + ".home_directory"));
		}

		SecretMasker.registerSecret(apiKey);
		return builder.build();
	}

	private static String emptyToNull(String value) {
		if (value == null || value.isEmpty()) {
			return null;
		}

		return value;
	}

	static CommonLlmException invalid(String message) {
		return CommonLlmException.of(ErrorCategory.CONFIG_INVALID, message, null);
	}

	/**
	 * 带字段路径的类型读取与校验工具
	 */
	private static final class ConfigTreeReader {

		private final Map<String, List<String>> missingVariablesByPath;

		private ConfigTreeReader(Map<String, List<String>> missingVariablesByPath) {
			this.missingVariablesByPath = missingVariablesByPath == null ? Map.of() : missingVariablesByPath;
		}

		private String describeMissing(String fieldPath) {
			List<String> variableNames = missingVariablesByPath.get(fieldPath);

			if (variableNames == null || variableNames.isEmpty()) {
				return "";
			}

			return "（引用的环境变量未设置：" + String.join(", ", variableNames) + "）";
		}

		private void requireKnownKeys(Map<String, Object> tree, Set<String> knownKeys, String fieldPath) {
			for (String key : tree.keySet()) {
				if (!knownKeys.contains(key)) {
					throw invalid("未知配置字段：" + join(fieldPath, key));
				}
			}
		}

		@SuppressWarnings("unchecked")
		private Map<String, Object> asMap(Object value, String fieldPath) {
			if (value == null) {
				return new LinkedHashMap<>();
			}

			if (!(value instanceof Map)) {
				throw invalid(fieldPath + " 必须是对象");
			}

			return (Map<String, Object>) value;
		}

		private Map<String, Object> readMap(Map<String, Object> tree, String key, String fieldPath) {
			return asMap(tree.get(key), join(fieldPath, key));
		}

		private String readString(Map<String, Object> tree, String key, String fieldPath) {
			Object value = tree.get(key);

			if (value == null) {
				return null;
			}

			if (value instanceof String) {
				return (String) value;
			}

			if (value instanceof Number || value instanceof Boolean) {
				return String.valueOf(value);
			}

			throw invalid(join(fieldPath, key) + " 必须是字符串");
		}

		private String readEnum(Map<String, Object> tree, String key, String fieldPath, Set<String> allowedValues, String defaultValue) {
			String value = emptyToNull(readString(tree, key, fieldPath));

			if (value == null) {
				return defaultValue;
			}

			if (!allowedValues.contains(value)) {
				throw invalid(join(fieldPath, key) + " 取值非法：" + value + "，可选值 " + allowedValues);
			}

			return value;
		}

		private boolean readBoolean(Map<String, Object> tree, String key, String fieldPath, boolean defaultValue) {
			Object value = tree.get(key);

			if (value == null) {
				return defaultValue;
			}

			if (value instanceof Boolean) {
				return (Boolean) value;
			}

			if ("true".equals(value) || "false".equals(value)) {
				return Boolean.parseBoolean((String) value);
			}

			throw invalid(join(fieldPath, key) + " 必须是布尔值");
		}

		private double readNumber(Map<String, Object> tree, String key, String fieldPath, double defaultValue) {
			Object value = tree.get(key);

			if (value == null || "".equals(value)) {
				return defaultValue;
			}

			if (value instanceof Number) {
				return ((Number) value).doubleValue();
			}

			try {
				return Double.parseDouble(String.valueOf(value));
			} catch (NumberFormatException exception) {
				throw invalid(join(fieldPath, key) + " 必须是数字");
			}
		}

		private double readPositiveNumber(Map<String, Object> tree, String key, String fieldPath, double defaultValue) {
			double value = readNumber(tree, key, fieldPath, defaultValue);

			if (value <= 0) {
				throw invalid(join(fieldPath, key) + " 必须大于 0");
			}

			return value;
		}

		private double readNonNegativeNumber(Map<String, Object> tree, String key, String fieldPath, double defaultValue) {
			double value = readNumber(tree, key, fieldPath, defaultValue);

			if (value < 0) {
				throw invalid(join(fieldPath, key) + " 不能小于 0");
			}

			return value;
		}

		private long readInteger(Map<String, Object> tree, String key, String fieldPath, long defaultValue) {
			double value = readNumber(tree, key, fieldPath, defaultValue);

			if (value != Math.rint(value)) {
				throw invalid(join(fieldPath, key) + " 必须是整数");
			}

			return (long) value;
		}

		private long readPositiveInteger(Map<String, Object> tree, String key, String fieldPath, long defaultValue) {
			long value = readInteger(tree, key, fieldPath, defaultValue);

			if (value <= 0) {
				throw invalid(join(fieldPath, key) + " 必须大于 0");
			}

			return value;
		}

		private long readNonNegativeInteger(Map<String, Object> tree, String key, String fieldPath, long defaultValue) {
			long value = readInteger(tree, key, fieldPath, defaultValue);

			if (value < 0) {
				throw invalid(join(fieldPath, key) + " 不能小于 0");
			}

			return value;
		}

		private List<String> readStringList(Map<String, Object> tree, String key, String fieldPath) {
			Object value = tree.get(key);
			List<String> values = new ArrayList<>();

			if (value == null) {
				return values;
			}

			if (!(value instanceof List)) {
				throw invalid(join(fieldPath, key) + " 必须是字符串数组");
			}

			for (Object listValue : (List<?>) value) {
				if (!(listValue instanceof String)) {
					throw invalid(join(fieldPath, key) + " 必须是字符串数组");
				}

				values.add((String) listValue);
			}

			return values;
		}

		private static String join(String fieldPath, String key) {
			if (fieldPath.isEmpty()) {
				return key;
			}

			return fieldPath + "." + key;
		}
	}
}
