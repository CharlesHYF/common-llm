package io.github.charleshyf.commonllm.agents.claudecode;

import io.github.charleshyf.commonllm.config.AgentConfig;
import io.github.charleshyf.commonllm.core.AgentCapability;
import io.github.charleshyf.commonllm.core.AgentClient;
import io.github.charleshyf.commonllm.core.AgentEvent;
import io.github.charleshyf.commonllm.core.AgentEventBuffer;
import io.github.charleshyf.commonllm.core.AgentEventEmitter;
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
import io.github.charleshyf.commonllm.core.process.ManagedProcess;
import io.github.charleshyf.commonllm.core.process.ProcessEnvironmentBuilder;
import io.github.charleshyf.commonllm.core.process.ProcessLimits;
import io.github.charleshyf.commonllm.core.process.ProcessOutputHandler;
import io.github.charleshyf.commonllm.core.process.WorkingDirectoryResolver;
import io.github.charleshyf.commonllm.observability.CallLogger;
import io.github.charleshyf.commonllm.observability.IdGenerator;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通过 claude -p 子进程调用 Claude Code 的 Agent 客户端
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class ClaudeCodeAgentClient implements AgentClient {

	static final CliVersion MIN_SUPPORTED_VERSION = new CliVersion(2, 1, 325);

	private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);
	private static final Pattern OPTION_PATTERN = Pattern.compile("--[a-zA-Z][\\w-]*");
	private static final Pattern CHOICES_PATTERN = Pattern.compile("choices:\\s*([^)]*)\\)");
	private static final Pattern QUOTED_PATTERN = Pattern.compile("\"([^\"]+)\"");
	private static final Set<String> NATIVE_OPTION_KEYS = Set.of("max_turns", "add_dirs", "setting_sources", "include_partial_messages");

	private final AgentConfig config;
	private final IdGenerator idGenerator;
	private final Map<String, String> environment;
	private final AgentRunTracker runTracker;
	private final ScheduledExecutorService scheduler;
	private ProbeResult probeResult;

	public ClaudeCodeAgentClient(AgentConfig config, IdGenerator idGenerator, Map<String, String> environment) {
		this.config = config;
		this.idGenerator = idGenerator;
		this.environment = environment;
		this.runTracker = new AgentRunTracker(ClaudeStreamMapper.AGENT, config.getMaxConcurrentRuns());
		this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "common-llm-claude-code-timer");
			thread.setDaemon(true);
			return thread;
		});
	}

	@Override
	public AgentResult run(AgentRequest request) {
		ClaudeRun claudeRun;

		try {
			claudeRun = startRun(request, false);
		} catch (CommonLlmException exception) {
			return AgentResultCollector.collect(buildFailedStream(request, exception), config.shouldCollectEvents());
		}

		AgentResult result = AgentResultCollector.collect(claudeRun.stream, config.shouldCollectEvents());
		return result.withEventsAndExitCode(result.getEvents(), claudeRun.awaitExitCode());
	}

	/**
	 * 启动前的校验、探测与并发限制失败都以 failed 终止事件返回
	 */
	@Override
	public AgentStream runStream(AgentRequest request) {
		try {
			return startRun(request, true).stream;
		} catch (CommonLlmException exception) {
			return buildFailedStream(request, exception);
		}
	}

	private AgentStream buildFailedStream(AgentRequest request, CommonLlmException exception) {
		String traceId = request.getTraceId() != null ? request.getTraceId() : idGenerator.newTraceId();
		return BufferedAgentStream.failed(traceId, idGenerator.newRunId(), ClaudeStreamMapper.AGENT, exception.getErrorInfo());
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
	 * 探测可执行文件、版本与 -p --help 参数集合，结果缓存在实例内
	 */
	@Override
	public AgentCapability capabilities() {
		ProbeResult probe = probe();
		List<String> features = List.of(AgentFeature.STREAMING.getValue(), AgentFeature.SESSION_RESUME.getValue(), AgentFeature.INTERRUPT.getValue(), AgentFeature.COST.getValue());
		List<String> eventTypes = new ArrayList<>();

		for (AgentEventType eventType : AgentEventType.values()) {
			if (eventType != AgentEventType.FILE_CHANGED) {
				eventTypes.add(eventType.getValue());
			}
		}

		return new AgentCapability(ClaudeStreamMapper.AGENT, "cli", probe.executable.toString(), probe.version.toString(), features, eventTypes, new ArrayList<>(probe.supportedOptions));
	}

	@Override
	public void close() {
		runTracker.cancelAll();
		scheduler.shutdownNow();

		synchronized (this) {
			probeResult = null;
		}
	}

	private synchronized ProbeResult probe() {
		if (probeResult != null) {
			return probeResult;
		}

		Path executable = ExecutableResolver.resolve(config.getExecutablePath(), environment.get("PATH"), ClaudeStreamMapper.AGENT);
		Map<String, String> probeEnvironment = ProcessEnvironmentBuilder.build(environment, List.of(), Map.of());
		CommandOutput versionOutput = CommandRunner.run(List.of(executable.toString(), "--version"), null, probeEnvironment, PROBE_TIMEOUT, ClaudeStreamMapper.AGENT);
		CliVersion version = CliVersion.parse(versionOutput.getStdout());

		if (versionOutput.getExitCode() != 0 || version == null) {
			throw CommonLlmException.of(ErrorCategory.CLI_VERSION_UNSUPPORTED, "无法识别 Claude Code 版本：" + SecretMasker.mask(versionOutput.getStdout().trim()), ClaudeStreamMapper.AGENT);
		}

		if (version.compareTo(MIN_SUPPORTED_VERSION) < 0) {
			throw CommonLlmException.of(ErrorCategory.CLI_VERSION_UNSUPPORTED, "Claude Code 版本 " + version + " 低于最低支持版本 " + MIN_SUPPORTED_VERSION, ClaudeStreamMapper.AGENT);
		}

		CommandOutput helpOutput = CommandRunner.run(List.of(executable.toString(), "-p", "--help"), null, probeEnvironment, PROBE_TIMEOUT, ClaudeStreamMapper.AGENT);
		probeResult = new ProbeResult(executable, version, parseOptions(helpOutput.getStdout()), parsePermissionModes(helpOutput.getStdout()));
		return probeResult;
	}

	/**
	 * 只取缩进不超过 4 个空格且以 - 开头的行，避免把说明文字中的参数当成选项
	 */
	static Set<String> parseOptions(String helpText) {
		Set<String> options = new LinkedHashSet<>();

		for (String line : helpText.split("\n")) {
			String trimmedLine = line.stripLeading();
			int indent = line.length() - trimmedLine.length();

			if (indent > 4 || !trimmedLine.startsWith("-")) {
				continue;
			}

			int descriptionStart = trimmedLine.indexOf("  ");
			String optionPart = descriptionStart > 0 ? trimmedLine.substring(0, descriptionStart) : trimmedLine;
			Matcher matcher = OPTION_PATTERN.matcher(optionPart);

			while (matcher.find()) {
				options.add(matcher.group());
			}
		}

		return options;
	}

	/**
	 * 解析 --permission-mode 说明中的 choices 列表，找不到返回空集合
	 */
	static Set<String> parsePermissionModes(String helpText) {
		Set<String> modes = new LinkedHashSet<>();
		int optionIndex = helpText.indexOf("--permission-mode");

		if (optionIndex < 0) {
			return modes;
		}

		Matcher choicesMatcher = CHOICES_PATTERN.matcher(helpText.substring(optionIndex));

		if (!choicesMatcher.find()) {
			return modes;
		}

		Matcher quotedMatcher = QUOTED_PATTERN.matcher(choicesMatcher.group(1));

		while (quotedMatcher.find()) {
			modes.add(quotedMatcher.group(1));
		}

		return modes;
	}

	private ClaudeRun startRun(AgentRequest request, boolean isStreaming) {
		validateRequest(request);
		ProbeResult probe = probe();
		Path workingDirectory = WorkingDirectoryResolver.resolve(request.getWorkingDirectory(), config.getWorkingDirectory(), ClaudeStreamMapper.AGENT);
		List<String> command = buildCommand(request, probe, isStreaming);
		Map<String, String> processEnvironment = buildEnvironment(request);
		runTracker.acquire();
		String runId = idGenerator.newRunId();
		String traceId = request.getTraceId() != null ? request.getTraceId() : idGenerator.newTraceId();
		ClaudeRun claudeRun = new ClaudeRun(runId, traceId, request);

		try {
			claudeRun.start(command, workingDirectory, processEnvironment);
		} catch (RuntimeException exception) {
			runTracker.release(runId);
			throw exception;
		}

		return claudeRun;
	}

	private void validateRequest(AgentRequest request) {
		if (request.getPrompt() == null || request.getPrompt().isEmpty()) {
			throw CommonLlmException.of(ErrorCategory.CONFIG_INVALID, "prompt 不能为空", ClaudeStreamMapper.AGENT);
		}

		if (request.getApprovalHandler() != null) {
			throw CommonLlmException.of(ErrorCategory.SDK_UNSUPPORTED_FEATURE, "claude -p 不支持运行中审批回调，只能预设 permissionMode 与 allowedTools", ClaudeStreamMapper.AGENT);
		}

		if (request.getSandboxMode() != null) {
			throw CommonLlmException.of(ErrorCategory.SDK_UNSUPPORTED_FEATURE, "sandboxMode 只适用于 Codex", ClaudeStreamMapper.AGENT);
		}

		for (String key : request.getNativeOptions().keySet()) {
			if (!NATIVE_OPTION_KEYS.contains(key)) {
				throw CommonLlmException.of(ErrorCategory.SDK_UNSUPPORTED_FEATURE, "不支持的 nativeOptions：" + key + "，可用 " + NATIVE_OPTION_KEYS, ClaudeStreamMapper.AGENT);
			}
		}
	}

	/**
	 * 参数以数组组装，prompt 不出现在参数中而是经 stdin 传入
	 */
	private List<String> buildCommand(AgentRequest request, ProbeResult probe, boolean isStreaming) {
		Map<String, Object> nativeOptions = new LinkedHashMap<>(config.getNativeOptions());
		nativeOptions.putAll(request.getNativeOptions());
		List<String> command = new ArrayList<>();
		command.add(probe.executable.toString());
		command.add("-p");
		command.add("--output-format");
		command.add("stream-json");
		command.add("--verbose");
		boolean shouldIncludePartial = isStreaming || Boolean.TRUE.equals(nativeOptions.get("include_partial_messages"));

		if (shouldIncludePartial && probe.supportedOptions.contains("--include-partial-messages")) {
			command.add("--include-partial-messages");
		}

		if (request.getModel() != null) {
			requireOption(probe, "--model");
			command.add("--model");
			command.add(request.getModel());
		}

		appendPermissionMode(command, request, probe);
		List<String> allowedTools = request.getAllowedTools() != null ? request.getAllowedTools() : config.getAllowedTools();

		if (!allowedTools.isEmpty()) {
			requireOption(probe, "--allowedTools");
			command.add("--allowedTools");
			command.add(String.join(",", allowedTools));
		}

		if (request.getSessionId() != null) {
			requireOption(probe, "--resume");
			command.add("--resume");
			command.add(request.getSessionId());
		}

		appendNativeOptions(command, nativeOptions, probe);
		return command;
	}

	/**
	 * default 不在 CLI 可选值中时不传参，其他不支持的取值报 SDK_UNSUPPORTED_FEATURE
	 */
	private void appendPermissionMode(List<String> command, AgentRequest request, ProbeResult probe) {
		String permissionMode = request.getPermissionMode() != null ? request.getPermissionMode() : config.getPermissionMode();

		if (permissionMode == null || permissionMode.isEmpty()) {
			return;
		}

		boolean isSupported = probe.supportedOptions.contains("--permission-mode") && probe.permissionModes.contains(permissionMode);

		if (!isSupported && AgentConfig.DEFAULT_PERMISSION_MODE.equals(permissionMode)) {
			return;
		}

		if (!isSupported) {
			throw CommonLlmException.of(ErrorCategory.SDK_UNSUPPORTED_FEATURE, "当前 Claude Code 不支持 permission_mode=" + permissionMode + "，可选值 " + probe.permissionModes, ClaudeStreamMapper.AGENT);
		}

		if ("bypassPermissions".equals(permissionMode)) {
			CallLogger.warnDangerousPermission(ClaudeStreamMapper.AGENT, "permission_mode=bypassPermissions");
		}

		command.add("--permission-mode");
		command.add(permissionMode);
	}

	private void appendNativeOptions(List<String> command, Map<String, Object> nativeOptions, ProbeResult probe) {
		Object maxTurns = nativeOptions.get("max_turns");

		if (maxTurns != null) {
			requireOption(probe, "--max-turns");
			command.add("--max-turns");
			command.add(String.valueOf(maxTurns));
		}

		Object settingSources = nativeOptions.get("setting_sources");

		if (settingSources != null) {
			requireOption(probe, "--setting-sources");
			command.add("--setting-sources");
			command.add(String.valueOf(settingSources));
		}

		Object addDirs = nativeOptions.get("add_dirs");

		if (addDirs instanceof List) {
			requireOption(probe, "--add-dir");

			for (Object directory : (List<?>) addDirs) {
				command.add("--add-dir");
				command.add(String.valueOf(directory));
			}
		}
	}

	private static void requireOption(ProbeResult probe, String option) {
		if (!probe.supportedOptions.contains(option)) {
			throw CommonLlmException.of(ErrorCategory.SDK_UNSUPPORTED_FEATURE, "当前 Claude Code 不支持参数 " + option, ClaudeStreamMapper.AGENT);
		}
	}

	private Map<String, String> buildEnvironment(AgentRequest request) {
		List<String> allowlist = request.getEnvAllowlist() != null ? request.getEnvAllowlist() : config.getEnvAllowlist();
		Map<String, String> authVariables = new LinkedHashMap<>();

		if (AgentConfig.AUTH_MODE_API_KEY.equals(config.getAuthMode())) {
			authVariables.put("ANTHROPIC_API_KEY", config.getApiKey());
		}

		return ProcessEnvironmentBuilder.build(environment, allowlist, authVariables);
	}

	/**
	 * 运行时探测结果
	 */
	private static final class ProbeResult {

		private final Path executable;
		private final CliVersion version;
		private final Set<String> supportedOptions;
		private final Set<String> permissionModes;

		private ProbeResult(Path executable, CliVersion version, Set<String> supportedOptions, Set<String> permissionModes) {
			this.executable = executable;
			this.version = version;
			this.supportedOptions = supportedOptions;
			this.permissionModes = permissionModes;
		}
	}

	/**
	 * 单次 claude -p 运行：子进程、事件映射、超时与取消
	 */
	private final class ClaudeRun implements ProcessOutputHandler {

		private final String runId;
		private final String prompt;
		private final AgentEventEmitter emitter;
		private final ClaudeStreamMapper mapper;
		private final BufferedAgentStream stream;
		private final CompletableFuture<Integer> exitFuture = new CompletableFuture<>();
		private final long startNanos = System.nanoTime();
		private final Duration timeout;
		private final Duration killGrace;
		private volatile ManagedProcess process;
		private volatile ErrorInfo violationError;
		private volatile boolean isCancelled;
		private volatile boolean isTimedOut;

		private ClaudeRun(String runId, String traceId, AgentRequest request) {
			this.runId = runId;
			this.prompt = request.getPrompt();
			AgentEventBuffer buffer = new AgentEventBuffer(config.getMaxBufferedEvents());
			this.emitter = new AgentEventEmitter(traceId, runId, ClaudeStreamMapper.AGENT, buffer);
			this.mapper = new ClaudeStreamMapper(emitter, config.getMaxToolOutputChars());
			this.stream = new BufferedAgentStream(runId, buffer, this::cancel);
			double timeoutSeconds = request.getTimeoutSeconds() != null ? request.getTimeoutSeconds() : config.getTimeoutSeconds();
			this.timeout = Duration.ofMillis(Math.round(timeoutSeconds * 1000));
			this.killGrace = Duration.ofMillis(Math.round(config.getKillGraceSeconds() * 1000));
		}

		private void start(List<String> command, Path workingDirectory, Map<String, String> processEnvironment) {
			ProcessLimits limits = new ProcessLimits(config.getMaxOutputBytes(), config.getMaxLineBytes(), config.getMaxStderrBytes(), killGrace);
			runTracker.register(runId, this::cancel);
			process = ManagedProcess.start(command, workingDirectory, processEnvironment, limits, this, ClaudeStreamMapper.AGENT);
			process.writeAndCloseStdin(prompt);
			scheduler.schedule(this::onTimeout, timeout.toMillis(), TimeUnit.MILLISECONDS);
		}

		@Override
		public void onLine(String line) {
			boolean hadTerminalEvent = emitter.hasTerminalEvent();
			mapper.mapLine(line);

			// 输出 result 后进程应自行退出，超过 kill_grace 仍存活则结束进程树
			if (!hadTerminalEvent && emitter.hasTerminalEvent()) {
				scheduler.schedule(this::terminateIfAlive, killGrace.toMillis(), TimeUnit.MILLISECONDS);
			}
		}

		private void terminateIfAlive() {
			if (!exitFuture.isDone()) {
				terminateAsync();
			}
		}

		@Override
		public void onOutputViolation(ErrorInfo error) {
			violationError = error;
		}

		@Override
		public void onExit(int exitCode, String stderrTail) {
			if (!emitter.hasTerminalEvent()) {
				emitter.emit(buildExitEvent(exitCode, stderrTail));
			}

			exitFuture.complete(exitCode);
			runTracker.release(runId);
			CallLogger.logAgentRun(ClaudeStreamMapper.AGENT, runId, Duration.ofNanos(System.nanoTime() - startNanos).toMillis(), emitter.getTraceId(), null, null);
		}

		private AgentEvent.Builder buildExitEvent(int exitCode, String stderrTail) {
			if (isCancelled) {
				return AgentEvent.builder(AgentEventType.CANCELLED);
			}

			ErrorInfo error;

			if (isTimedOut) {
				error = ErrorInfo.builder(ErrorCategory.TIMEOUT, "Claude Code 运行超过 timeout_seconds 上限").provider(ClaudeStreamMapper.AGENT).retriable(true).build();
			} else if (violationError != null) {
				error = violationError;
			} else if (exitCode != 0) {
				error = ErrorInfo.builder(ErrorCategory.CLI_PROCESS_FAILED, "Claude Code 进程非零退出：" + exitCode + "，stderr：" + stderrTail.trim()).provider(ClaudeStreamMapper.AGENT).statusCode(exitCode).build();
			} else {
				error = ErrorInfo.builder(ErrorCategory.CLI_PROTOCOL_ERROR, "Claude Code 进程结束但未输出 result 消息").provider(ClaudeStreamMapper.AGENT).statusCode(exitCode).build();
			}

			return AgentEvent.builder(AgentEventType.FAILED).error(error);
		}

		private void onTimeout() {
			if (emitter.hasTerminalEvent() || exitFuture.isDone()) {
				return;
			}

			isTimedOut = true;
			terminateAsync();
		}

		private void cancel() {
			if (emitter.hasTerminalEvent() || exitFuture.isDone()) {
				return;
			}

			isCancelled = true;
			terminateAsync();
		}

		private void terminateAsync() {
			if (process == null) {
				return;
			}

			Thread terminateThread = new Thread(process::terminate, "common-llm-claude-code-terminate-" + runId);
			terminateThread.setDaemon(true);
			terminateThread.start();
		}

		/**
		 * 等待进程退出取得退出码，最多等待两倍 kill_grace 再加 1 秒
		 */
		private Integer awaitExitCode() {
			try {
				return exitFuture.get(killGrace.toMillis() * 2 + 1000, TimeUnit.MILLISECONDS);
			} catch (TimeoutException exception) {
				process.terminate();
				return null;
			} catch (ExecutionException exception) {
				return null;
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				return null;
			}
		}
	}
}
