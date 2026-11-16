package io.github.charleshyf.commonllm.agents.codex;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.charleshyf.commonllm.config.AgentConfig;
import io.github.charleshyf.commonllm.core.CommonLlmException;
import io.github.charleshyf.commonllm.core.ErrorCategory;
import io.github.charleshyf.commonllm.core.ErrorInfo;
import io.github.charleshyf.commonllm.core.JsonSupport;
import io.github.charleshyf.commonllm.core.process.ManagedProcess;
import io.github.charleshyf.commonllm.core.process.ProcessLimits;
import io.github.charleshyf.commonllm.core.process.ProcessOutputHandler;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 启动临时 App Server 并执行 initialize 握手，用于能力探测
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
final class CodexHandshakeProbe implements ProcessOutputHandler {

	private final CompletableFuture<JsonNode> responseFuture = new CompletableFuture<>();

	private CodexHandshakeProbe() {
	}

	/**
	 * 握手成功即返回，任何失败都报 CLI_PROTOCOL_ERROR
	 */
	static void verify(List<String> command, Path workingDirectory, Map<String, String> environment, AgentConfig config, Duration timeout) {
		CodexHandshakeProbe probe = new CodexHandshakeProbe();
		ProcessLimits limits = new ProcessLimits(config.getMaxOutputBytes(), config.getMaxLineBytes(), config.getMaxStderrBytes(), Duration.ofMillis(Math.round(config.getKillGraceSeconds() * 1000)));
		ManagedProcess process = ManagedProcess.start(command, workingDirectory, environment, limits, probe, CodexEventMapper.AGENT);

		try {
			ObjectNode request = JsonSupport.newObject();
			request.put("id", 1);
			request.put("method", "initialize");
			ObjectNode params = request.putObject("params");
			ObjectNode clientInfo = params.putObject("clientInfo");
			clientInfo.put("name", CodexRun.CLIENT_NAME);
			clientInfo.put("version", CodexRun.CLIENT_VERSION);
			params.putObject("capabilities").put("experimentalApi", true);
			process.writeLine(JsonSupport.toJson(request));
			JsonNode response = probe.responseFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);

			if (JsonSupport.fieldOrNull(response, "result") == null) {
				throw protocolError("initialize 返回错误：" + JsonSupport.textOrNull(JsonSupport.fieldOrNull(response, "error"), "message"));
			}
		} catch (TimeoutException exception) {
			throw protocolError("initialize 握手超时");
		} catch (ExecutionException exception) {
			throw protocolError("initialize 握手失败：" + exception.getCause().getMessage());
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw protocolError("initialize 握手被中断");
		} finally {
			process.terminate();
		}
	}

	private static CommonLlmException protocolError(String message) {
		return new CommonLlmException(ErrorInfo.builder(ErrorCategory.CLI_PROTOCOL_ERROR, message).provider(CodexEventMapper.AGENT).build());
	}

	@Override
	public void onLine(String line) {
		try {
			JsonNode message = JsonSupport.parseTree(line);

			if (message != null && message.path("id").asInt() == 1 && !message.has("method")) {
				responseFuture.complete(message);
			}
		} catch (JsonProcessingException exception) {
			responseFuture.completeExceptionally(new IllegalStateException("App Server 输出了非法 JSON"));
		}
	}

	@Override
	public void onOutputViolation(ErrorInfo error) {
		responseFuture.completeExceptionally(new IllegalStateException(error.getMessage()));
	}

	@Override
	public void onExit(int exitCode, String stderrTail) {
		responseFuture.completeExceptionally(new IllegalStateException("App Server 在握手前退出，退出码 " + exitCode));
	}
}
