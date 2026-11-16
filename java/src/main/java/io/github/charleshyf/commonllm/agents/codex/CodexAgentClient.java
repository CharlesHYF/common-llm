package io.github.charleshyf.commonllm.agents.codex;

import io.github.charleshyf.commonllm.config.AgentConfig;
import io.github.charleshyf.commonllm.core.AgentCapability;
import io.github.charleshyf.commonllm.core.AgentClient;
import io.github.charleshyf.commonllm.core.AgentEventType;
import io.github.charleshyf.commonllm.core.AgentFeature;
import io.github.charleshyf.commonllm.core.AgentRequest;
import io.github.charleshyf.commonllm.core.AgentResult;
import io.github.charleshyf.commonllm.core.AgentResultCollector;
import io.github.charleshyf.commonllm.core.AgentStream;
import io.github.charleshyf.commonllm.core.BufferedAgentStream;
import io.github.charleshyf.commonllm.core.CommonLlmException;
import io.github.charleshyf.commonllm.core.ErrorCategory;
import io.github.charleshyf.commonllm.core.ErrorInfo;
import io.github.charleshyf.commonllm.core.SecretMasker;
import io.github.charleshyf.commonllm.core.process.AgentRunTracker;
import io.github.charleshyf.commonllm.core.process.CliVersion;
import io.github.charleshyf.commonllm.core.process.CommandOutput;
import io.github.charleshyf.commonllm.core.process.CommandRunner;
import io.github.charleshyf.commonllm.core.process.ExecutableResolver;
import io.github.charleshyf.commonllm.core.process.ProcessEnvironmentBuilder;
import io.github.charleshyf.commonllm.core.process.WorkingDirectoryResolver;
import io.github.charleshyf.commonllm.observability.CallLogger;
import io.github.charleshyf.commonllm.observability.IdGenerator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 通过 codex app-server stdio JSON-RPC 调用 Codex 的 Agent 客户端
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class CodexAgentClient implements AgentClient {

	static final CliVersion MIN_SUPPORTED_VERSION = new CliVersion(0, 169, 0);

	private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);
	private static final Set<String> NATIVE_OPTION_KEYS = Set.of("approval_policy", "effort", "network_access", "config");

	private final AgentConfig config;
	private final IdGenerator idGenerator;
	private final Map<String, String> environment;
	private final AgentRunTracker runTracker;
	private final ScheduledExecutorService scheduler;
	private Path executable;
	private CliVersion version;
	private boolean isHandshakeVerified;

	public CodexAgentClient(AgentConfig config, IdGenerator idGenerator, Map<String, String> environment) {
		this.config = config;
		this.idGenerator = idGenerator;
		this.environment = environment;
		this.runTracker = new AgentRunTracker(CodexEventMapper.AGENT, config.getMaxConcurrentRuns());
		this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "common-llm-codex-timer");
			thread.setDaemon(true);
			return thread;
		});
	}

	@Override
	public AgentResult run(AgentRequest request) {
		CodexRun codexRun;

		try {
			codexRun = startRun(request);
		} catch (CommonLlmException exception) {
			return AgentResultCollector.collect(buildFailedStream(request, exception), config.shouldCollectEvents());
		}

		AgentResult result = AgentResultCollector.collect(codexRun.getStream(), config.shouldCollectEvents());
		return result.withEventsAndExitCode(result.getEvents(), codexRun.awaitExitCode());
	}

	/**
	 * 启动前的校验、探测与并发限制失败都以 failed 终止事件返回
	 */
	@Override
	public AgentStream runStream(AgentRequest request) {
		try {
			return startRun(request).getStream();
		} catch (CommonLlmException exception) {
			return buildFailedStream(request, exception);
		}
	}

	private AgentStream buildFailedStream(AgentRequest request, CommonLlmException exception) {
		String traceId = request.getTraceId() != null ? request.getTraceId() : idGenerator.newTraceId();
		return BufferedAgentStream.failed(traceId, idGenerator.newRunId(), CodexEventMapper.AGENT, exception.getErrorInfo());
	}

	@Override
	public AgentResult resume(String sessionId, AgentRequest request) {
		return run(request.toBuilder().sessionId(sessionId).build());
	}

	@Override
	public boolean interrupt(String runId) {
		return runTracker.cancel(runId);
	}

	/**
	 * 探测可执行文件与版本，并以一次 initialize 握手确认协议可用
	 */
	@Override
	public AgentCapability capabilities() {
		probeExecutable();
		verifyHandshake();
		List<String> features = List.of(AgentFeature.STREAMING.getValue(), AgentFeature.SESSION_RESUME.getValue(), AgentFeature.APPROVALS.getValue(), AgentFeature.INTERRUPT.getValue());
		List<String> eventTypes = new ArrayList<>();

		for (AgentEventType eventType : AgentEventType.values()) {
			eventTypes.add(eventType.getValue());
		}

		return new AgentCapability(CodexEventMapper.AGENT, "app_server", executable.toString(), version.toString(), features, eventTypes, null);
	}

	@Override
	public void close() {
		runTracker.cancelAll();
		scheduler.shutdownNow();

		synchronized (this) {
			executable = null;
			version = null;
			isHandshakeVerified = false;
		}
	}

	private synchronized void probeExecutable() {
		if (executable != null) {
			return;
		}

		Path resolvedExecutable = ExecutableResolver.resolve(config.getExecutablePath(), environment.get("PATH"), CodexEventMapper.AGENT);
		CommandOutput versionOutput = CommandRunner.run(List.of(resolvedExecutable.toString(), "--version"), null, ProcessEnvironmentBuilder.build(environment, List.of(), Map.of()), PROBE_TIMEOUT, CodexEventMapper.AGENT);
		CliVersion parsedVersion = CliVersion.parse(versionOutput.getStdout());

		if (versionOutput.getExitCode() != 0 || parsedVersion == null) {
			throw CommonLlmException.of(ErrorCategory.CLI_VERSION_UNSUPPORTED, "无法识别 Codex 版本：" + SecretMasker.mask(versionOutput.getStdout().trim()), CodexEventMapper.AGENT);
		}

		if (parsedVersion.compareTo(MIN_SUPPORTED_VERSION) < 0) {
			throw CommonLlmException.of(ErrorCategory.CLI_VERSION_UNSUPPORTED, "Codex 版本 " + parsedVersion + " 低于最低支持版本 " + MIN_SUPPORTED_VERSION, CodexEventMapper.AGENT);
		}

		executable = resolvedExecutable;
		version = parsedVersion;
	}

	/**
	 * 在临时会话中执行 initialize，失败时报 CLI_PROTOCOL_ERROR
	 */
	private synchronized void verifyHandshake() {
		if (isHandshakeVerified) {
			return;
		}

		Path probeDirectory = Paths.get(environment.getOrDefault("HOME", System.getProperty("user.home")));
		CodexHandshakeProbe.verify(List.of(executable.toString(), "app-server", "--listen", "stdio://"), probeDirectory, buildEnvironment(null), config, PROBE_TIMEOUT);
		isHandshakeVerified = true;
	}

	private CodexRun startRun(AgentRequest request) {
		CodexRun.CodexRunSettings settings = buildSettings(request);
		probeExecutable();
		Map<String, String> processEnvironment = buildEnvironment(request.getEnvAllowlist());
		List<String> command = List.of(executable.toString(), "app-server", "--listen", "stdio://");
		runTracker.acquire();
		String runId = idGenerator.newRunId();
		String traceId = request.getTraceId() != null ? request.getTraceId() : idGenerator.newTraceId();
		CodexRun codexRun = new CodexRun(runId, traceId, request, config, settings, runTracker, scheduler);

		try {
			codexRun.start(command, processEnvironment);
		} catch (RuntimeException exception) {
			runTracker.release(runId);
			throw exception;
		}

		return codexRun;
	}

	/**
	 * 校验请求并解析沙箱、审批策略与原生选项，危险沙箱输出 WARN 日志
	 */
	private CodexRun.CodexRunSettings buildSettings(AgentRequest request) {
		if (request.getPrompt() == null || request.getPrompt().isEmpty()) {
			throw CommonLlmException.of(ErrorCategory.CONFIG_INVALID, "prompt 不能为空", CodexEventMapper.AGENT);
		}

		if (request.getPermissionMode() != null || request.getAllowedTools() != null) {
			throw CommonLlmException.of(ErrorCategory.SDK_UNSUPPORTED_FEATURE, "permissionMode 与 allowedTools 只适用于 Claude Code，Codex 请使用 sandboxMode", CodexEventMapper.AGENT);
		}

		Map<String, Object> nativeOptions = new LinkedHashMap<>(config.getNativeOptions());
		nativeOptions.putAll(request.getNativeOptions());

		for (String key : nativeOptions.keySet()) {
			if (!NATIVE_OPTION_KEYS.contains(key)) {
				throw CommonLlmException.of(ErrorCategory.SDK_UNSUPPORTED_FEATURE, "不支持的 nativeOptions：" + key + "，可用 " + NATIVE_OPTION_KEYS, CodexEventMapper.AGENT);
			}
		}

		Path workingDirectory = WorkingDirectoryResolver.resolve(request.getWorkingDirectory(), config.getWorkingDirectory(), CodexEventMapper.AGENT);
		String sandboxMode = request.getSandboxMode() != null ? request.getSandboxMode() : config.getSandboxMode();

		if ("danger-full-access".equals(sandboxMode)) {
			CallLogger.warnDangerousPermission(CodexEventMapper.AGENT, "sandbox_mode=danger-full-access");
		}

		Object approvalPolicy = nativeOptions.get("approval_policy");
		Object effort = nativeOptions.get("effort");
		Map<String, Object> threadConfig = new LinkedHashMap<>();
		Object configOption = nativeOptions.get("config");

		if (configOption instanceof Map) {
			for (Map.Entry<?, ?> configEntry : ((Map<?, ?>) configOption).entrySet()) {
				threadConfig.put(String.valueOf(configEntry.getKey()), configEntry.getValue());
			}
		}

		// 网络访问默认关闭，只有显式开启时才写入 sandbox_workspace_write.network_access
		if (Boolean.TRUE.equals(nativeOptions.get("network_access"))) {
			threadConfig.put("sandbox_workspace_write", Map.of("network_access", true));
		}

		return new CodexRun.CodexRunSettings(workingDirectory, sandboxMode, approvalPolicy == null ? "on-request" : String.valueOf(approvalPolicy), effort == null ? null : String.valueOf(effort), threadConfig);
	}

	/**
	 * api_key 模式使用独立的 home_directory 作为 CODEX_HOME，不改写用户 ~/.codex
	 */
	private Map<String, String> buildEnvironment(List<String> requestAllowlist) {
		List<String> allowlist = new ArrayList<>(requestAllowlist != null ? requestAllowlist : config.getEnvAllowlist());
		Map<String, String> explicitVariables = new LinkedHashMap<>();

		if (AgentConfig.AUTH_MODE_API_KEY.equals(config.getAuthMode())) {
			Path homeDirectory = Paths.get(config.getHomeDirectory()).toAbsolutePath();

			try {
				Files.createDirectories(homeDirectory);
			} catch (IOException exception) {
				throw new CommonLlmException(ErrorInfo.builder(ErrorCategory.CONFIG_INVALID, "无法创建 Codex home_directory：" + homeDirectory).provider(CodexEventMapper.AGENT).cause(exception).build(), exception);
			}

			explicitVariables.put("CODEX_HOME", homeDirectory.toString());
		} else {
			allowlist.add("CODEX_HOME");
		}

		return ProcessEnvironmentBuilder.build(environment, allowlist, explicitVariables);
	}
}
