package io.github.charleshyf.commonllm.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.charleshyf.commonllm.core.CommonLlmException;
import io.github.charleshyf.commonllm.core.ErrorCategory;
import io.github.charleshyf.commonllm.core.ErrorInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 从 YAML 或 JSON 文件读取配置并替换环境变量引用
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class FileConfigProvider implements ConfigProvider {

	private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private final Path path;
	private final Map<String, String> environment;
	private Map<String, List<String>> missingVariablesByPath = new LinkedHashMap<>();

	public FileConfigProvider(Path path) {
		this(path, System.getenv());
	}

	public FileConfigProvider(Path path, Map<String, String> environment) {
		this.path = path;
		this.environment = environment;
	}

	public Path getPath() {
		return path;
	}

	/**
	 * 按扩展名识别格式：.json 用 JSON 解析，其余用 YAML 解析
	 */
	@Override
	public Map<String, Object> load() {
		if (!Files.isRegularFile(path)) {
			throw CommonLlmException.of(ErrorCategory.CONFIG_INVALID, "配置文件不存在：" + path, null);
		}

		Map<String, Object> rawTree;

		try {
			rawTree = createReader().readValue(path.toFile(), MAP_TYPE);
		} catch (IOException exception) {
			throw new CommonLlmException(ErrorInfo.builder(ErrorCategory.CONFIG_INVALID, "配置文件解析失败：" + path).cause(exception).build(), exception);
		}

		if (rawTree == null) {
			rawTree = new LinkedHashMap<>();
		}

		EnvironmentVariableResolver resolver = new EnvironmentVariableResolver(environment);
		Map<String, Object> resolvedTree = resolver.resolveTree(rawTree);
		missingVariablesByPath = resolver.getMissingVariablesByPath();
		return resolvedTree;
	}

	/**
	 * 最近一次 load 中引用了未设置环境变量的字段
	 */
	public Map<String, List<String>> getMissingVariablesByPath() {
		return missingVariablesByPath;
	}

	private ObjectMapper createReader() {
		String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);

		if (fileName.endsWith(".json")) {
			return new ObjectMapper();
		}

		return new ObjectMapper(new YAMLFactory());
	}
}
