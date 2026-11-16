package io.github.charleshyf.commonllm;

import com.google.genai.Client;
import com.openai.client.OpenAIClient;
import io.github.charleshyf.commonllm.agents.claudecode.ClaudeCodeAgentClient;
import io.github.charleshyf.commonllm.agents.codex.CodexAgentClient;
import io.github.charleshyf.commonllm.config.AgentConfig;
import io.github.charleshyf.commonllm.config.CommonLlmConfig;
import io.github.charleshyf.commonllm.config.ConfigProvider;
import io.github.charleshyf.commonllm.config.ConfigResolver;
import io.github.charleshyf.commonllm.config.FileConfigProvider;
import io.github.charleshyf.commonllm.config.ProviderConfig;
import io.github.charleshyf.commonllm.core.AgentCapability;
import io.github.charleshyf.commonllm.core.AgentClient;
import io.github.charleshyf.commonllm.core.AgentEvent;
import io.github.charleshyf.commonllm.core.AgentRequest;
import io.github.charleshyf.commonllm.core.AgentResult;
import io.github.charleshyf.commonllm.core.AgentResultCollector;
import io.github.charleshyf.commonllm.core.AgentStream;
import io.github.charleshyf.commonllm.core.BufferedAgentStream;
import io.github.charleshyf.commonllm.core.CommonLlmException;
import io.github.charleshyf.commonllm.core.ErrorCategory;
import io.github.charleshyf.commonllm.core.ErrorInfo;
import io.github.charleshyf.commonllm.core.ImageClient;
import io.github.charleshyf.commonllm.core.ImageRequest;
import io.github.charleshyf.commonllm.core.ImageResponse;
import io.github.charleshyf.commonllm.core.ImageStream;
import io.github.charleshyf.commonllm.core.ModelClient;
import io.github.charleshyf.commonllm.core.ModelRequest;
import io.github.charleshyf.commonllm.core.ModelResponse;
import io.github.charleshyf.commonllm.core.ModelStream;
import io.github.charleshyf.commonllm.core.ProviderCapability;
import io.github.charleshyf.commonllm.providers.anthropic.AnthropicModelClient;
import io.github.charleshyf.commonllm.providers.gemini.GeminiImageClient;
import io.github.charleshyf.commonllm.providers.gemini.GeminiModelClient;
import io.github.charleshyf.commonllm.providers.openai.OpenAiImageClient;
import io.github.charleshyf.commonllm.providers.openai.OpenAiModelClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * common-llm 的统一入口：按配置装配模型与 Coding Agent 客户端
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class CommonLlm implements AutoCloseable {

	private static final String CONFIG_PATH_VARIABLE = "COMMON_LLM_CONFIG";
	private static final String DEFAULT_CONFIG_FILE = "common-llm.yaml";

	private final CommonLlmOptions options;
	private volatile ClientGeneration currentGeneration;
	private volatile boolean isClosed;

	private CommonLlm(CommonLlmOptions options, ClientGeneration generation) {
		this.options = options;
		this.currentGeneration = generation;
	}

	/**
	 * 从默认路径加载文件配置：环境变量 COMMON_LLM_CONFIG，其次当前目录的 common-llm.yaml
	 */
	public static CommonLlm create() {
		return create(new CommonLlmOptions());
	}

	public static CommonLlm create(ConfigProvider configProvider) {
		return create(new CommonLlmOptions().configProvider(configProvider));
	}

	public static CommonLlm create(CommonLlmOptions options) {
		return new CommonLlm(options, buildGeneration(options));
	}

	public ModelResponse generate(ModelRequest request) {
		ClientGeneration generation = acquireGeneration();

		try {
			return generation.getModelClient(request.getProvider()).generate(request);
		} finally {
			generation.release();
		}
	}

	/**
	 * 流式调用，不抛异常：Provider 不存在等预检失败以 error 事件结束；流关闭后才允许 reload 释放旧客户端
	 */
	public ModelStream stream(ModelRequest request) {
		ClientGeneration generation;

		try {
			generation = acquireGeneration();
		} catch (CommonLlmException exception) {
			return ModelStream.failed(resolveTraceId(request.getTraceId()), request.getProvider(), exception.getErrorInfo());
		}

		try {
			return generation.getModelClient(request.getProvider()).stream(request).onClose(generation::release);
		} catch (CommonLlmException exception) {
			generation.release();
			return ModelStream.failed(resolveTraceId(request.getTraceId()), request.getProvider(), exception.getErrorInfo());
		}
	}

	/**
	 * 获取 Provider 实例的客户端；reload 之后旧客户端会在进行中的请求结束后关闭
	 */
	public ModelClient model(String provider) {
		return currentGeneration().getModelClient(provider);
	}

	/**
	 * 同步生成图片，失败时抛出 CommonLlmException；Anthropic 等不支持图片生成的 Provider 报 SDK_UNSUPPORTED_FEATURE
	 */
	public ImageResponse generateImage(ImageRequest request) {
		ClientGeneration generation = acquireGeneration();

		try {
			return generation.getImageClient(request.getProvider()).generate(request);
		} finally {
			generation.release();
		}
	}

	/**
	 * 流式生成图片，不抛异常：预检失败以 error 事件结束；流关闭后才允许 reload 释放旧客户端
	 */
	public ImageStream streamImage(ImageRequest request) {
		ClientGeneration generation;

		try {
			generation = acquireGeneration();
		} catch (CommonLlmException exception) {
			return ImageStream.failed(resolveTraceId(request.getTraceId()), request.getProvider(), exception.getErrorInfo());
		}

		try {
			return generation.getImageClient(request.getProvider()).stream(request).onClose(generation::release);
		} catch (CommonLlmException exception) {
			generation.release();
			return ImageStream.failed(resolveTraceId(request.getTraceId()), request.getProvider(), exception.getErrorInfo());
		}
	}

	/**
	 * 获取图片生成客户端；未启用报 CONFIG_INVALID，Provider 不支持图片生成报 SDK_UNSUPPORTED_FEATURE
	 */
	public ImageClient image(String provider) {
		return currentGeneration().getImageClient(provider);
	}

	public AgentClient agent(String agent) {
		return currentGeneration().getAgentClient(agent);
	}

	/**
	 * 执行一次 Agent 运行，不抛异常：Agent 未启用等预检失败返回 status=failed 的结果
	 */
	public AgentResult runAgent(AgentRequest request) {
		ClientGeneration generation;

		try {
			generation = acquireGeneration();
		} catch (CommonLlmException exception) {
			return AgentResultCollector.collect(buildFailedAgentStream(request, exception), false);
		}

		try {
			return generation.getAgentClient(request.getAgent()).run(request);
		} catch (CommonLlmException exception) {
			return AgentResultCollector.collect(buildFailedAgentStream(request, exception), false);
		} finally {
			generation.release();
		}
	}

	/**
	 * 流式执行 Agent，不抛异常：预检失败以 failed 事件结束
	 */
	public AgentStream runAgentStream(AgentRequest request) {
		ClientGeneration generation;

		try {
			generation = acquireGeneration();
		} catch (CommonLlmException exception) {
			return buildFailedAgentStream(request, exception);
		}

		try {
			AgentStream agentStream = generation.getAgentClient(request.getAgent()).runStream(request);
			return new GenerationAgentStream(agentStream, generation);
		} catch (CommonLlmException exception) {
			generation.release();
			return buildFailedAgentStream(request, exception);
		}
	}

	private AgentStream buildFailedAgentStream(AgentRequest request, CommonLlmException exception) {
		return BufferedAgentStream.failed(resolveTraceId(request.getTraceId()), options.getIdGenerator().newRunId(), request.getAgent(), exception.getErrorInfo());
	}

	private String resolveTraceId(String requestTraceId) {
		if (requestTraceId != null && !requestTraceId.isBlank()) {
			return requestTraceId;
		}

		return options.getIdGenerator().newTraceId();
	}

	public List<ProviderCapability> listProviders() {
		List<ProviderCapability> capabilities = new ArrayList<>();

		ClientGeneration generation = currentGeneration();

		for (ModelClient modelClient : generation.modelClients.values()) {
			ProviderCapability modelCapability = modelClient.capability();
			ImageClient imageClient = generation.imageClients.get(modelCapability.getProvider());

			if (imageClient == null) {
				capabilities.add(modelCapability);
				continue;
			}

			List<String> features = new ArrayList<>(modelCapability.getFeatures());
			features.addAll(imageClient.capability().getFeatures());
			capabilities.add(new ProviderCapability(modelCapability.getProvider(), features));
		}

		return capabilities;
	}

	/**
	 * 返回已启用 Agent 的能力，会触发运行时探测
	 */
	public List<AgentCapability> listAgents() {
		List<AgentCapability> capabilities = new ArrayList<>();

		for (AgentClient agentClient : currentGeneration().agentClients.values()) {
			capabilities.add(agentClient.capabilities());
		}

		return capabilities;
	}

	public CommonLlmConfig getConfig() {
		return currentGeneration().config;
	}

	/**
	 * 重新加载全部配置来源；失败时保留旧配置并抛出 CONFIG_INVALID
	 */
	public synchronized void reload() {
		ClientGeneration newGeneration;

		try {
			newGeneration = buildGeneration(options);
		} catch (CommonLlmException exception) {
			if (exception.getCategory() == ErrorCategory.CONFIG_INVALID) {
				throw exception;
			}

			ErrorInfo error = ErrorInfo.builder(ErrorCategory.CONFIG_INVALID, "重新加载配置失败，已保留旧配置：" + exception.getErrorInfo().getMessage()).provider(exception.getErrorInfo().getProvider()).build();
			throw new CommonLlmException(error, exception);
		} catch (RuntimeException exception) {
			ErrorInfo error = ErrorInfo.builder(ErrorCategory.CONFIG_INVALID, "重新加载配置失败，已保留旧配置").cause(exception).build();
			throw new CommonLlmException(error, exception);
		}

		ClientGeneration oldGeneration = currentGeneration;
		currentGeneration = newGeneration;
		oldGeneration.retire();
	}

	@Override
	public synchronized void close() {
		if (isClosed) {
			return;
		}

		isClosed = true;
		currentGeneration.closeClients();
	}

	private ClientGeneration currentGeneration() {
		if (isClosed) {
			throw CommonLlmException.of(ErrorCategory.CONFIG_INVALID, "CommonLlm 已关闭", null);
		}

		return currentGeneration;
	}

	private ClientGeneration acquireGeneration() {
		while (true) {
			ClientGeneration generation = currentGeneration();

			if (generation.tryAcquire()) {
				return generation;
			}
		}
	}

	/**
	 * 加载全部配置来源（文件 < ConfigProvider < 运行时覆盖）并创建客户端
	 */
	private static ClientGeneration buildGeneration(CommonLlmOptions options) {
		List<Map<String, Object>> sources = new ArrayList<>();
		Map<String, List<String>> missingVariables = new LinkedHashMap<>();
		Path configPath = resolveConfigPath(options);

		if (configPath != null) {
			FileConfigProvider fileProvider = new FileConfigProvider(configPath, options.getEnvironment());
			sources.add(fileProvider.load());
			missingVariables.putAll(fileProvider.getMissingVariablesByPath());
		}

		if (options.getConfigProvider() != null) {
			sources.add(loadFromProvider(options.getConfigProvider()));
		}

		if (!options.getOverrides().isEmpty()) {
			sources.add(options.getOverrides());
		}

		if (sources.isEmpty()) {
			throw CommonLlmException.of(ErrorCategory.CONFIG_INVALID, "找不到配置：未指定配置文件、未设置 COMMON_LLM_CONFIG、当前目录没有 common-llm.yaml，也没有其他配置来源", null);
		}

		CommonLlmConfig config = ConfigResolver.resolve(sources, missingVariables);
		return new ClientGeneration(config, options);
	}

	private static Path resolveConfigPath(CommonLlmOptions options) {
		if (options.getConfigPath() != null) {
			return options.getConfigPath();
		}

		String configuredPath = options.getEnvironment().get(CONFIG_PATH_VARIABLE);

		if (configuredPath != null && !configuredPath.isBlank()) {
			return Paths.get(configuredPath);
		}

		Path defaultPath = Paths.get(System.getProperty("user.dir"), DEFAULT_CONFIG_FILE);
		return Files.isRegularFile(defaultPath) ? defaultPath : null;
	}

	/**
	 * ConfigProvider 加载异常一律视为 CONFIG_INVALID，不回退到文件中的旧密钥
	 */
	private static Map<String, Object> loadFromProvider(ConfigProvider configProvider) {
		try {
			Map<String, Object> loaded = configProvider.load();
			return loaded == null ? new LinkedHashMap<>() : loaded;
		} catch (CommonLlmException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			ErrorInfo error = ErrorInfo.builder(ErrorCategory.CONFIG_INVALID, "ConfigProvider 加载失败").cause(exception).build();
			throw new CommonLlmException(error, exception);
		}
	}

	/**
	 * 一次配置加载产生的全部客户端，reload 后在进行中的调用结束时关闭
	 */
	private static final class ClientGeneration {

		private final CommonLlmConfig config;
		private final Map<String, ModelClient> modelClients = new LinkedHashMap<>();
		private final Map<String, ImageClient> imageClients = new LinkedHashMap<>();
		private final Map<String, AgentClient> agentClients = new LinkedHashMap<>();
		private final AtomicInteger inFlightCount = new AtomicInteger();
		private final AtomicBoolean isRetired = new AtomicBoolean(false);
		private final AtomicBoolean isClosed = new AtomicBoolean(false);

		private ClientGeneration(CommonLlmConfig config, CommonLlmOptions options) {
			this.config = config;

			for (ProviderConfig providerConfig : config.getProviders().values()) {
				if (!providerConfig.isEnabled()) {
					continue;
				}

				ModelClient modelClient = createModelClient(providerConfig, options);
				modelClients.put(providerConfig.getInstanceName(), modelClient);

				// 图片客户端复用同一 Provider 的 SDK 客户端，Anthropic 没有图片生成能力
				if (ProviderConfig.TYPE_OPENAI.equals(providerConfig.getType())) {
					imageClients.put(providerConfig.getInstanceName(), new OpenAiImageClient(providerConfig, options.getIdGenerator(), (OpenAIClient) modelClient.nativeClient()));
				} else if (ProviderConfig.TYPE_GEMINI.equals(providerConfig.getType())) {
					imageClients.put(providerConfig.getInstanceName(), new GeminiImageClient(providerConfig, options.getIdGenerator(), (Client) modelClient.nativeClient()));
				}
			}

			for (AgentConfig agentConfig : config.getAgents().values()) {
				if (!agentConfig.isEnabled()) {
					continue;
				}

				if (AgentConfig.AGENT_CLAUDE_CODE.equals(agentConfig.getName())) {
					agentClients.put(agentConfig.getName(), new ClaudeCodeAgentClient(agentConfig, options.getIdGenerator(), options.getEnvironment()));
				} else {
					agentClients.put(agentConfig.getName(), new CodexAgentClient(agentConfig, options.getIdGenerator(), options.getEnvironment()));
				}
			}
		}

		private static ModelClient createModelClient(ProviderConfig providerConfig, CommonLlmOptions options) {
			if (ProviderConfig.TYPE_OPENAI.equals(providerConfig.getType())) {
				return new OpenAiModelClient(providerConfig, options.getIdGenerator());
			}

			if (ProviderConfig.TYPE_ANTHROPIC.equals(providerConfig.getType())) {
				return new AnthropicModelClient(providerConfig, options.getIdGenerator());
			}

			return new GeminiModelClient(providerConfig, options.getIdGenerator());
		}

		private ModelClient getModelClient(String provider) {
			ModelClient modelClient = modelClients.get(provider);

			if (modelClient == null) {
				throw CommonLlmException.of(ErrorCategory.CONFIG_INVALID, "Provider 未配置或未启用：" + provider, provider);
			}

			return modelClient;
		}

		private ImageClient getImageClient(String provider) {
			ImageClient imageClient = imageClients.get(provider);

			if (imageClient != null) {
				return imageClient;
			}

			// 已启用的 Provider 没有图片客户端说明厂商不支持图片生成
			if (modelClients.containsKey(provider)) {
				throw CommonLlmException.of(ErrorCategory.SDK_UNSUPPORTED_FEATURE, "Provider 不支持图片生成：" + provider, provider);
			}

			throw CommonLlmException.of(ErrorCategory.CONFIG_INVALID, "Provider 未配置或未启用：" + provider, provider);
		}

		private AgentClient getAgentClient(String agent) {
			AgentClient agentClient = agentClients.get(agent);

			if (agentClient == null) {
				throw CommonLlmException.of(ErrorCategory.CONFIG_INVALID, "Agent 未配置或未启用：" + agent, agent);
			}

			return agentClient;
		}

		private boolean tryAcquire() {
			inFlightCount.incrementAndGet();

			if (isRetired.get()) {
				release();
				return false;
			}

			return true;
		}

		private void release() {
			if (inFlightCount.decrementAndGet() == 0 && isRetired.get()) {
				closeClients();
			}
		}

		private void retire() {
			isRetired.set(true);

			if (inFlightCount.get() == 0) {
				closeClients();
			}
		}

		private void closeClients() {
			if (!isClosed.compareAndSet(false, true)) {
				return;
			}

			for (ImageClient imageClient : imageClients.values()) {
				imageClient.close();
			}

			for (ModelClient modelClient : modelClients.values()) {
				modelClient.close();
			}

			for (AgentClient agentClient : agentClients.values()) {
				agentClient.close();
			}
		}
	}

	/**
	 * 读完终止事件或关闭时释放所属的客户端代
	 */
	private static final class GenerationAgentStream implements AgentStream {

		private final AgentStream delegate;
		private final ClientGeneration generation;
		private final AtomicBoolean isReleased = new AtomicBoolean(false);

		private GenerationAgentStream(AgentStream delegate, ClientGeneration generation) {
			this.delegate = delegate;
			this.generation = generation;
		}

		@Override
		public String getRunId() {
			return delegate.getRunId();
		}

		@Override
		public boolean hasNext() {
			boolean hasMore = delegate.hasNext();

			if (!hasMore) {
				releaseOnce();
			}

			return hasMore;
		}

		@Override
		public AgentEvent next() {
			AgentEvent event = delegate.next();

			if (event.getType().isTerminal()) {
				releaseOnce();
			}

			return event;
		}

		@Override
		public void close() {
			delegate.close();
			releaseOnce();
		}

		private void releaseOnce() {
			if (isReleased.compareAndSet(false, true)) {
				generation.release();
			}
		}
	}
}
