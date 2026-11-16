package io.github.charleshyf.commonllm.agents.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.charleshyf.commonllm.config.AgentConfig;
import io.github.charleshyf.commonllm.core.AgentEvent;
import io.github.charleshyf.commonllm.core.AgentEventBuffer;
import io.github.charleshyf.commonllm.core.AgentEventEmitter;
import io.github.charleshyf.commonllm.core.AgentEventType;
import io.github.charleshyf.commonllm.core.AgentRequest;
import io.github.charleshyf.commonllm.core.ApprovalDecision;
import io.github.charleshyf.commonllm.core.ApprovalHandler;
import io.github.charleshyf.commonllm.core.ApprovalKind;
import io.github.charleshyf.commonllm.core.ApprovalRequest;
import io.github.charleshyf.commonllm.core.BufferedAgentStream;
import io.github.charleshyf.commonllm.core.CommonLlmException;
import io.github.charleshyf.commonllm.core.ErrorCategory;
import io.github.charleshyf.commonllm.core.ErrorInfo;
import io.github.charleshyf.commonllm.core.JsonSupport;
import io.github.charleshyf.commonllm.core.TextTruncator;
import io.github.charleshyf.commonllm.core.process.AgentRunTracker;
import io.github.charleshyf.commonllm.core.process.ManagedProcess;
import io.github.charleshyf.commonllm.core.process.ProcessLimits;
import io.github.charleshyf.commonllm.observability.CallLogger;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 单次 Codex 运行：App Server 进程、握手、线程与 turn 生命周期、审批、中断与超时
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
final class CodexRun {

	static final String CLIENT_NAME = "common_llm";
	static final String CLIENT_VERSION = "1.0.0";

	private final String runId;
	private final AgentRequest request;
	private final AgentConfig config;
	private final CodexRunSettings settings;
	private final AgentRunTracker runTracker;
	private final ScheduledExecutorService scheduler;
	private final AgentEventEmitter emitter;
	private final CodexEventMapper mapper;
	private final BufferedAgentStream stream;
	private final CodexRpcConnection connection;
	private final CompletableFuture<Integer> exitFuture = new CompletableFuture<>();
	private final Duration timeout;
	private final Duration killGrace;
	private final long startNanos = System.nanoTime();
	private volatile ManagedProcess process;
	private volatile ErrorInfo violationError;
	private volatile boolean isCancelRequested;
	private volatile boolean isForcedCancel;
	private volatile boolean isTimedOut;
	private volatile boolean isInterruptSent;

	CodexRun(String runId, String traceId, AgentRequest request, AgentConfig config, CodexRunSettings settings, AgentRunTracker runTracker, ScheduledExecutorService scheduler) {
		this.runId = runId;
		this.request = request;
		this.config = config;
		this.settings = settings;
		this.runTracker = runTracker;
		this.scheduler = scheduler;
		AgentEventBuffer buffer = new AgentEventBuffer(config.getMaxBufferedEvents());
		this.emitter = new AgentEventEmitter(traceId, runId, CodexEventMapper.AGENT, buffer);
		this.mapper = new CodexEventMapper(emitter, config.getMaxToolOutputChars(), settings.workingDirectory.toString());
		this.stream = new BufferedAgentStream(runId, buffer, this::cancel);
		this.connection = new CodexRpcConnection(this);
		double timeoutSeconds = request.getTimeoutSeconds() != null ? request.getTimeoutSeconds() : config.getTimeoutSeconds();
		this.timeout = Duration.ofMillis(Math.round(timeoutSeconds * 1000));
		this.killGrace = Duration.ofMillis(Math.round(config.getKillGraceSeconds() * 1000));
	}

	BufferedAgentStream getStream() {
		return stream;
	}

	/**
	 * 启动 App Server 进程，握手与 turn 在独立线程中执行
	 */
	void start(List<String> command, Map<String, String> processEnvironment) {
		ProcessLimits limits = new ProcessLimits(config.getMaxOutputBytes(), config.getMaxLineBytes(), config.getMaxStderrBytes(), killGrace);
		runTracker.register(runId, this::cancel);
		process = ManagedProcess.start(command, settings.workingDirectory, processEnvironment, limits, connection, CodexEventMapper.AGENT);
		connection.attach(process);
		scheduler.schedule(this::onTimeout, timeout.toMillis(), TimeUnit.MILLISECONDS);
		Thread runThread = new Thread(this::execute, "common-llm-codex-run-" + runId);
		runThread.setDaemon(true);
		runThread.start();
	}

	/**
	 * 等待进程退出取得退出码，最多等待两倍 kill_grace 再加 1 秒
	 */
	Integer awaitExitCode() {
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

	private void execute() {
		try {
			initialize();
			JsonNode threadResult = startOrResumeThread();
			mapper.mapThreadResult(threadResult);
			JsonNode turnResult = connection.call("turn/start", buildTurnParams(), ErrorCategory.PROVIDER_ERROR);
			mapper.setTurnId(JsonSupport.textOrNull(JsonSupport.fieldOrNull(turnResult, "turn"), "id"));

			if (isCancelRequested) {
				sendInterrupt();
			}
		} catch (CommonLlmException exception) {
			if (!emitter.hasTerminalEvent() && !exitFuture.isDone()) {
				emitter.emit(AgentEvent.builder(AgentEventType.FAILED).error(exception.getErrorInfo()));
				process.terminate();
			}
		}
	}

	/**
	 * initialize 握手并按认证模式登录或校验账号，握手失败报 CLI_PROTOCOL_ERROR
	 */
	private void initialize() {
		ObjectNode params = JsonSupport.newObject();
		ObjectNode clientInfo = params.putObject("clientInfo");
		clientInfo.put("name", CLIENT_NAME);
		clientInfo.put("title", "common-llm");
		clientInfo.put("version", CLIENT_VERSION);
		params.putObject("capabilities").put("experimentalApi", true);
		connection.call("initialize", params, ErrorCategory.CLI_PROTOCOL_ERROR);
		connection.sendNotification("initialized", null);

		if (AgentConfig.AUTH_MODE_API_KEY.equals(config.getAuthMode())) {
			ObjectNode loginParams = JsonSupport.newObject();
			loginParams.put("type", "apiKey");
			loginParams.put("apiKey", config.getApiKey());
			connection.call("account/login/start", loginParams, ErrorCategory.AUTH_FAILED);
			return;
		}

		if (AgentConfig.AUTH_MODE_CLI_EXISTING_SESSION.equals(config.getAuthMode())) {
			JsonNode accountResult = connection.call("account/read", JsonSupport.newObject(), ErrorCategory.AUTH_MISSING);
			boolean hasAccount = JsonSupport.fieldOrNull(accountResult, "account") != null;

			if (!hasAccount && JsonSupport.isTrue(accountResult, "requiresOpenaiAuth")) {
				throw CommonLlmException.of(ErrorCategory.AUTH_MISSING, "本机 Codex 未登录，请先执行 codex login 或改用 auth_mode=api_key", CodexEventMapper.AGENT);
			}
		}
	}

	private JsonNode startOrResumeThread() {
		ObjectNode params = JsonSupport.newObject();
		params.put("cwd", settings.workingDirectory.toString());

		if (request.getModel() != null) {
			params.put("model", request.getModel());
		}

		params.put("sandbox", settings.sandboxMode);
		params.put("approvalPolicy", settings.approvalPolicy);

		if (!settings.threadConfig.isEmpty()) {
			params.set("config", JsonSupport.toTree(settings.threadConfig));
		}

		if (request.getSessionId() == null) {
			return connection.call("thread/start", params, ErrorCategory.PROVIDER_ERROR);
		}

		params.put("threadId", request.getSessionId());
		params.put("excludeTurns", true);
		return connection.call("thread/resume", params, ErrorCategory.PROVIDER_ERROR);
	}

	private ObjectNode buildTurnParams() {
		ObjectNode params = JsonSupport.newObject();
		params.put("threadId", emitter.getSessionId());
		ObjectNode textInput = params.putArray("input").addObject();
		textInput.put("type", "text");
		textInput.put("text", request.getPrompt());

		if (request.getModel() != null) {
			params.put("model", request.getModel());
		}

		if (settings.effort != null) {
			params.put("effort", settings.effort);
		}

		return params;
	}

	void onNotification(String method, JsonNode params, JsonNode rawMessage) {
		mapper.mapNotification(method, params, rawMessage);

		// turn 开始前收到的中断请求在拿到 turnId 后补发
		if ("turn/started".equals(method) && isCancelRequested) {
			sendInterrupt();
		}

		if (emitter.hasTerminalEvent()) {
			finishAfterTerminal();
		}
	}

	/**
	 * 处理服务端请求：审批交给审批处理器，未提供时一律拒绝；其他请求回复方法不存在
	 */
	void onServerRequest(String method, JsonNode requestId, JsonNode params) {
		boolean isCommandApproval = "item/commandExecution/requestApproval".equals(method);
		boolean isFileApproval = "item/fileChange/requestApproval".equals(method);

		if (!isCommandApproval && !isFileApproval) {
			ObjectNode rawMessage = JsonSupport.newObject();
			rawMessage.put("method", method);
			rawMessage.set("id", requestId);
			rawMessage.set("params", params);
			mapper.emitRaw(method, rawMessage);
			connection.sendError(requestId, CodexRpcConnection.METHOD_NOT_FOUND_CODE, "common-llm 不处理该服务端请求：" + method);
			return;
		}

		String approvalId = JsonSupport.textOrNull(params, "approvalId");

		if (approvalId == null) {
			approvalId = JsonSupport.textOrNull(params, "itemId");
		}

		ApprovalKind kind = isCommandApproval ? ApprovalKind.COMMAND : ApprovalKind.FILE_CHANGE;
		String summary = isCommandApproval ? JsonSupport.textOrNull(params, "command") : JsonSupport.textOrNull(params, "reason");

		if (summary == null) {
			summary = isCommandApproval ? "" : "file change";
		}

		ApprovalDecision decision = decideApproval(new ApprovalRequest(approvalId, kind, summary, JsonSupport.toMap(params)));
		mapper.emitApproval(approvalId, kind.getValue(), summary, decision.getValue());
		ObjectNode result = JsonSupport.newObject();
		result.put("decision", toWireDecision(decision));
		connection.sendResult(requestId, result);
	}

	private ApprovalDecision decideApproval(ApprovalRequest approvalRequest) {
		ApprovalHandler handler = request.getApprovalHandler();

		if (handler == null) {
			return ApprovalDecision.DECLINE;
		}

		try {
			ApprovalDecision decision = handler.decide(approvalRequest);
			return decision == null ? ApprovalDecision.DECLINE : decision;
		} catch (RuntimeException exception) {
			CallLogger.debug("审批处理器抛出异常，按拒绝处理 runId={}", runId);
			return ApprovalDecision.DECLINE;
		}
	}

	private static String toWireDecision(ApprovalDecision decision) {
		if (decision == ApprovalDecision.APPROVE) {
			return "accept";
		}

		if (decision == ApprovalDecision.APPROVE_FOR_SESSION) {
			return "acceptForSession";
		}

		if (decision == ApprovalDecision.CANCEL) {
			return "cancel";
		}

		return "decline";
	}

	void onInvalidLine(String line) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("line", TextTruncator.truncateCodePoints(line, 1000));
		emitter.emit(AgentEvent.builder(AgentEventType.RAW_VENDOR_EVENT).field("vendorType", "invalid_json").field("payload", payload));
	}

	void onOutputViolation(ErrorInfo error) {
		violationError = error;
	}

	void onExit(int exitCode, String stderrTail) {
		if (!emitter.hasTerminalEvent()) {
			emitter.emit(buildExitEvent(exitCode, stderrTail));
		}

		exitFuture.complete(exitCode);
		runTracker.release(runId);
		CallLogger.logAgentRun(CodexEventMapper.AGENT, runId, Duration.ofNanos(System.nanoTime() - startNanos).toMillis(), emitter.getTraceId(), null, null);
	}

	private AgentEvent.Builder buildExitEvent(int exitCode, String stderrTail) {
		if (isCancelRequested || isForcedCancel) {
			return AgentEvent.builder(AgentEventType.CANCELLED);
		}

		ErrorInfo error;

		if (isTimedOut) {
			error = ErrorInfo.builder(ErrorCategory.TIMEOUT, "Codex 运行超过 timeout_seconds 上限").provider(CodexEventMapper.AGENT).retriable(true).build();
		} else if (violationError != null) {
			error = violationError;
		} else if (exitCode != 0) {
			error = ErrorInfo.builder(ErrorCategory.CLI_PROCESS_FAILED, "Codex App Server 进程非零退出：" + exitCode + "，stderr：" + stderrTail.trim()).provider(CodexEventMapper.AGENT).statusCode(exitCode).build();
		} else {
			error = ErrorInfo.builder(ErrorCategory.CLI_PROTOCOL_ERROR, "Codex App Server 在 turn 完成前退出").provider(CodexEventMapper.AGENT).statusCode(exitCode).build();
		}

		return AgentEvent.builder(AgentEventType.FAILED).error(error);
	}

	/**
	 * 终止事件之后关闭 stdin 让 App Server 退出，超过 kill_grace 仍存活则结束进程树
	 */
	private void finishAfterTerminal() {
		process.closeStdin();
		scheduler.schedule(this::terminateIfAlive, killGrace.toMillis(), TimeUnit.MILLISECONDS);
	}

	private void terminateIfAlive() {
		if (!exitFuture.isDone()) {
			terminateAsync();
		}
	}

	private void onTimeout() {
		if (emitter.hasTerminalEvent() || exitFuture.isDone()) {
			return;
		}

		isTimedOut = true;
		terminateAsync();
	}

	/**
	 * 已知 turnId 时发送 turn/interrupt，kill_grace 内没有结束则结束进程
	 */
	void cancel() {
		if (emitter.hasTerminalEvent() || exitFuture.isDone()) {
			return;
		}

		isCancelRequested = true;

		if (mapper.getTurnId() != null) {
			sendInterrupt();
		}

		scheduler.schedule(this::forceCancel, killGrace.toMillis(), TimeUnit.MILLISECONDS);
	}

	private synchronized void sendInterrupt() {
		if (isInterruptSent || mapper.getTurnId() == null || process == null) {
			return;
		}

		isInterruptSent = true;
		ObjectNode params = JsonSupport.newObject();
		params.put("threadId", emitter.getSessionId());
		params.put("turnId", mapper.getTurnId());
		connection.sendRequest("turn/interrupt", params);
	}

	private void forceCancel() {
		if (emitter.hasTerminalEvent() || exitFuture.isDone()) {
			return;
		}

		isForcedCancel = true;
		terminateAsync();
	}

	private void terminateAsync() {
		if (process == null) {
			return;
		}

		Thread terminateThread = new Thread(process::terminate, "common-llm-codex-terminate-" + runId);
		terminateThread.setDaemon(true);
		terminateThread.start();
	}

	/**
	 * 运行开始前解析好的设置
	 */
	static final class CodexRunSettings {

		private final Path workingDirectory;
		private final String sandboxMode;
		private final String approvalPolicy;
		private final String effort;
		private final Map<String, Object> threadConfig;

		CodexRunSettings(Path workingDirectory, String sandboxMode, String approvalPolicy, String effort, Map<String, Object> threadConfig) {
			this.workingDirectory = workingDirectory;
			this.sandboxMode = sandboxMode;
			this.approvalPolicy = approvalPolicy;
			this.effort = effort;
			this.threadConfig = threadConfig;
		}
	}
}
