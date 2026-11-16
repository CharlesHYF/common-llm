package io.github.charleshyf.commonllm;

import io.github.charleshyf.commonllm.config.ConfigProvider;
import io.github.charleshyf.commonllm.observability.IdGenerator;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CommonLlm.create 的可选参数：配置来源、运行时覆盖、环境变量与 ID 生成器
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class CommonLlmOptions {

	private Path configPath;
	private ConfigProvider configProvider;
	private Map<String, Object> overrides = new LinkedHashMap<>();
	private Map<String, String> environment = System.getenv();
	private IdGenerator idGenerator = IdGenerator.uuid();

	/**
	 * 显式指定配置文件，优先于环境变量 COMMON_LLM_CONFIG 与 ./common-llm.yaml
	 */
	public CommonLlmOptions configPath(Path configPath) {
		this.configPath = configPath;
		return this;
	}

	/**
	 * 注入配置来源，例如数据库配置，优先级高于配置文件
	 */
	public CommonLlmOptions configProvider(ConfigProvider configProvider) {
		this.configProvider = configProvider;
		return this;
	}

	/**
	 * 运行时显式覆盖，结构与配置文件相同，优先级最高
	 */
	public CommonLlmOptions overrides(Map<String, Object> overrides) {
		this.overrides = new LinkedHashMap<>(overrides);
		return this;
	}

	/**
	 * 替换环境变量来源，用于 ${ENV} 替换与子进程环境组装
	 */
	public CommonLlmOptions environment(Map<String, String> environment) {
		this.environment = environment;
		return this;
	}

	public CommonLlmOptions idGenerator(IdGenerator idGenerator) {
		this.idGenerator = idGenerator;
		return this;
	}

	Path getConfigPath() {
		return configPath;
	}

	ConfigProvider getConfigProvider() {
		return configProvider;
	}

	Map<String, Object> getOverrides() {
		return overrides;
	}

	Map<String, String> getEnvironment() {
		return environment;
	}

	IdGenerator getIdGenerator() {
		return idGenerator;
	}
}
