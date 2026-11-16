package io.github.charleshyf.commonllm.agents.codex;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.charleshyf.commonllm.core.CommonLlmException;
import io.github.charleshyf.commonllm.core.ErrorCategory;
import io.github.charleshyf.commonllm.core.ErrorInfo;
import io.github.charleshyf.commonllm.core.JsonSupport;
import io.github.charleshyf.commonllm.core.process.ManagedProcess;
import io.github.charleshyf.commonllm.core.process.ProcessOutputHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Codex App Server 的 stdio JSON-RPC 连接：请求 id 关联、通知与服务端请求分发
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
final class CodexRpcConnection implements ProcessOutputHandler {

	// JSON-RPC 规范中"方法不存在"的错误码，用于回复本库不处理的服务端请求
	static final int METHOD_NOT_FOUND_CODE = -32601;

	private final CodexRun listener;
	private final AtomicLong nextRequestId = new AtomicLong(1);
	private final Map<Long, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();
	private volatile ManagedProcess process;
	private volatile boolean isExited;

	CodexRpcConnection(CodexRun listener) {
		this.listener = listener;
	}

	void attach(ManagedProcess managedProcess) {
		this.process = managedProcess;
	}

	/**
	 * 发送请求并返回等待响应的 Future，消息不带 jsonrpc 字段
	 */
	CompletableFuture<JsonNode> sendRequest(String method, JsonNode params) {
		long requestId = nextRequestId.getAndIncrement();
		CompletableFuture<JsonNode> future = new CompletableFuture<>();
		pendingRequests.put(requestId, future);
		ObjectNode message = JsonSupport.newObject();
		message.put("id", requestId);
		message.put("method", method);
		message.set("params", params);

		if (isExited || !process.writeLine(JsonSupport.toJson(message))) {
			pendingRequests.remove(requestId);
			future.completeExceptionally(new CommonLlmException(ErrorInfo.builder(ErrorCategory.CLI_PROCESS_FAILED, "Codex App Server 进程已退出，无法发送 " + method).provider(CodexEventMapper.AGENT).build()));
		}

		return future;
	}

	/**
	 * 发送请求并同步等待结果，JSON-RPC error 转为 errorCategory 指定的异常
	 */
	JsonNode call(String method, JsonNode params, ErrorCategory errorCategory) {
		JsonNode response;

		try {
			response = sendRequest(method, params).get();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new CommonLlmException(ErrorInfo.builder(ErrorCategory.CANCELLED, "等待 " + method + " 响应时被中断").provider(CodexEventMapper.AGENT).build());
		} catch (ExecutionException exception) {
			if (exception.getCause() instanceof CommonLlmException) {
				throw (CommonLlmException) exception.getCause();
			}

			throw new CommonLlmException(ErrorInfo.builder(ErrorCategory.INTERNAL_ERROR, "等待 " + method + " 响应失败").provider(CodexEventMapper.AGENT).cause(exception.getCause()).build());
		}

		JsonNode errorNode = JsonSupport.fieldOrNull(response, "error");

		if (errorNode != null) {
			String message = JsonSupport.textOrNull(errorNode, "message");
			throw new CommonLlmException(ErrorInfo.builder(errorCategory, method + " 失败：" + message).provider(CodexEventMapper.AGENT).vendorCode(JsonSupport.textOrNull(errorNode, "code")).build());
		}

		return JsonSupport.fieldOrNull(response, "result");
	}

	void sendNotification(String method, JsonNode params) {
		ObjectNode message = JsonSupport.newObject();
		message.put("method", method);

		if (params != null) {
			message.set("params", params);
		}

		process.writeLine(JsonSupport.toJson(message));
	}

	/**
	 * 回复服务端请求，原样带回服务端的 id
	 */
	void sendResult(JsonNode requestId, JsonNode result) {
		ObjectNode message = JsonSupport.newObject();
		message.set("id", requestId);
		message.set("result", result);
		process.writeLine(JsonSupport.toJson(message));
	}

	void sendError(JsonNode requestId, int code, String errorMessage) {
		ObjectNode message = JsonSupport.newObject();
		message.set("id", requestId);
		ObjectNode errorNode = message.putObject("error");
		errorNode.put("code", code);
		errorNode.put("message", errorMessage);
		process.writeLine(JsonSupport.toJson(message));
	}

	@Override
	public void onLine(String line) {
		JsonNode message;

		try {
			message = JsonSupport.parseTree(line);
		} catch (JsonProcessingException exception) {
			listener.onInvalidLine(line);
			return;
		}

		if (message == null || !message.isObject()) {
			listener.onInvalidLine(line);
			return;
		}

		String method = JsonSupport.textOrNull(message, "method");
		JsonNode idNode = JsonSupport.fieldOrNull(message, "id");

		if (method == null && idNode != null) {
			CompletableFuture<JsonNode> future = pendingRequests.remove(idNode.asLong());

			if (future != null) {
				future.complete(message);
			}

			return;
		}

		JsonNode params = JsonSupport.fieldOrNull(message, "params");

		if (idNode != null) {
			listener.onServerRequest(method, idNode, params == null ? JsonSupport.newObject() : params);
			return;
		}

		listener.onNotification(method, params == null ? JsonSupport.newObject() : params, message);
	}

	@Override
	public void onOutputViolation(ErrorInfo error) {
		listener.onOutputViolation(error);
	}

	/**
	 * 进程退出时让所有等待中的请求失败，避免调用方永久阻塞
	 */
	@Override
	public void onExit(int exitCode, String stderrTail) {
		isExited = true;
		List<CompletableFuture<JsonNode>> futures = new ArrayList<>(pendingRequests.values());
		pendingRequests.clear();

		for (CompletableFuture<JsonNode> future : futures) {
			future.completeExceptionally(new CommonLlmException(ErrorInfo.builder(ErrorCategory.CLI_PROCESS_FAILED, "Codex App Server 进程已退出：" + exitCode).provider(CodexEventMapper.AGENT).statusCode(exitCode).build()));
		}

		listener.onExit(exitCode, stderrTail);
	}
}
