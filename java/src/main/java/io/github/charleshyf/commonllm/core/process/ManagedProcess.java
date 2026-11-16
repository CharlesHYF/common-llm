package io.github.charleshyf.commonllm.core.process;

import io.github.charleshyf.commonllm.core.CommonLlmException;
import io.github.charleshyf.commonllm.core.ErrorCategory;
import io.github.charleshyf.commonllm.core.ErrorInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 管理单个 CLI 子进程的输入输出、输出上限与进程树结束
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class ManagedProcess {

	private static final int READ_BUFFER_BYTES = 8192;

	private final Process process;
	private final String agent;
	private final ProcessLimits limits;
	private final ProcessOutputHandler handler;
	private final StderrTail stderrTail;
	private final AtomicBoolean isTerminating = new AtomicBoolean(false);
	private final Object stdinLock = new Object();
	private boolean isStdinClosed;

	private ManagedProcess(Process process, String agent, ProcessLimits limits, ProcessOutputHandler handler) {
		this.process = process;
		this.agent = agent;
		this.limits = limits;
		this.handler = handler;
		this.stderrTail = new StderrTail(limits.getMaxStderrBytes());
	}

	/**
	 * 以参数数组启动子进程并开始读取 stdout 与 stderr，环境变量完全替换为传入值
	 */
	public static ManagedProcess start(List<String> command, Path workingDirectory, Map<String, String> environment, ProcessLimits limits, ProcessOutputHandler handler, String agent) {
		ProcessBuilder processBuilder = new ProcessBuilder(command);
		processBuilder.directory(workingDirectory.toFile());
		processBuilder.environment().clear();
		processBuilder.environment().putAll(environment);
		Process process;

		try {
			process = processBuilder.start();
		} catch (IOException exception) {
			throw new CommonLlmException(ErrorInfo.builder(ErrorCategory.CLI_NOT_FOUND, "无法启动 CLI 进程：" + command.get(0)).provider(agent).cause(exception).build(), exception);
		}

		ManagedProcess managedProcess = new ManagedProcess(process, agent, limits, handler);
		managedProcess.startReaders();
		return managedProcess;
	}

	/**
	 * 写入一行到 stdin 并刷新，stdin 已关闭时返回 false
	 */
	public boolean writeLine(String line) {
		synchronized (stdinLock) {
			if (isStdinClosed) {
				return false;
			}

			try {
				OutputStream stdin = process.getOutputStream();
				stdin.write((line + "\n").getBytes(StandardCharsets.UTF_8));
				stdin.flush();
				return true;
			} catch (IOException exception) {
				isStdinClosed = true;
				return false;
			}
		}
	}

	/**
	 * 写入完整文本后关闭 stdin
	 */
	public void writeAndCloseStdin(String text) {
		synchronized (stdinLock) {
			if (isStdinClosed) {
				return;
			}

			try (OutputStream stdin = process.getOutputStream()) {
				stdin.write(text.getBytes(StandardCharsets.UTF_8));
				stdin.flush();
			} catch (IOException exception) {
				// 进程提前退出时写入会失败，失败原因由退出码与 stderr 体现
			}

			isStdinClosed = true;
		}
	}

	public void closeStdin() {
		synchronized (stdinLock) {
			if (isStdinClosed) {
				return;
			}

			isStdinClosed = true;

			try {
				process.getOutputStream().close();
			} catch (IOException exception) {
				// 关闭失败说明管道已断开，无需处理
			}
		}
	}

	/**
	 * 结束进程及其后代，等待 kill_grace 后强制结束
	 */
	public void terminate() {
		if (!isTerminating.compareAndSet(false, true)) {
			return;
		}

		closeStdin();
		ProcessTreeTerminator.terminate(process.toHandle(), limits.getKillGrace());
	}

	public boolean isAlive() {
		return process.isAlive();
	}

	public long getPid() {
		return process.pid();
	}

	public String getStderrTail() {
		return stderrTail.toText();
	}

	private void startReaders() {
		Thread stdoutThread = new Thread(this::readStdout, "common-llm-" + agent + "-stdout-" + process.pid());
		stdoutThread.setDaemon(true);
		stdoutThread.start();
		Thread stderrThread = new Thread(this::readStderr, "common-llm-" + agent + "-stderr-" + process.pid());
		stderrThread.setDaemon(true);
		stderrThread.start();
	}

	private void readStdout() {
		ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();
		byte[] readBuffer = new byte[READ_BUFFER_BYTES];
		long totalBytes = 0;
		boolean isViolated = false;

		try (InputStream stdout = process.getInputStream()) {
			int readCount = stdout.read(readBuffer);

			while (readCount >= 0 && !isViolated) {
				totalBytes += readCount;

				if (totalBytes > limits.getMaxOutputBytes()) {
					isViolated = true;
					reportViolation(ErrorCategory.CLI_PROCESS_FAILED, "CLI 输出超过 max_output_bytes 上限：" + limits.getMaxOutputBytes());
					break;
				}

				isViolated = splitLines(readBuffer, readCount, lineBuffer);

				if (!isViolated) {
					readCount = stdout.read(readBuffer);
				}
			}

			// 进程结束时最后一行可能没有换行符，仍按完整行处理
			if (!isViolated && lineBuffer.size() > 0) {
				deliverLine(lineBuffer);
			}
		} catch (IOException exception) {
			// 进程被结束时管道关闭，读取异常属于预期
		}

		int exitCode = waitForExit();
		handler.onExit(exitCode, stderrTail.toText());
	}

	private boolean splitLines(byte[] readBuffer, int readCount, ByteArrayOutputStream lineBuffer) {
		int segmentStart = 0;

		for (int index = 0; index < readCount; index++) {
			if (readBuffer[index] != '\n') {
				continue;
			}

			lineBuffer.write(readBuffer, segmentStart, index - segmentStart);
			segmentStart = index + 1;

			if (lineBuffer.size() > limits.getMaxLineBytes()) {
				reportViolation(ErrorCategory.CLI_PROTOCOL_ERROR, "CLI 单行输出超过 max_line_bytes 上限：" + limits.getMaxLineBytes());
				return true;
			}

			deliverLine(lineBuffer);
		}

		lineBuffer.write(readBuffer, segmentStart, readCount - segmentStart);

		if (lineBuffer.size() > limits.getMaxLineBytes()) {
			reportViolation(ErrorCategory.CLI_PROTOCOL_ERROR, "CLI 单行输出超过 max_line_bytes 上限：" + limits.getMaxLineBytes());
			return true;
		}

		return false;
	}

	private void deliverLine(ByteArrayOutputStream lineBuffer) {
		byte[] lineBytes = lineBuffer.toByteArray();
		lineBuffer.reset();
		int lineLength = lineBytes.length;

		if (lineLength > 0 && lineBytes[lineLength - 1] == '\r') {
			lineLength--;
		}

		String line = new String(lineBytes, 0, lineLength, StandardCharsets.UTF_8);

		if (line.isBlank()) {
			return;
		}

		handler.onLine(line);
	}

	private void reportViolation(ErrorCategory category, String message) {
		ErrorInfo error = ErrorInfo.builder(category, message).provider(agent).build();
		terminate();
		handler.onOutputViolation(error);
	}

	private int waitForExit() {
		try {
			return process.waitFor();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			terminate();
			return process.exitValue();
		}
	}

	private void readStderr() {
		byte[] readBuffer = new byte[READ_BUFFER_BYTES];

		try (InputStream stderr = process.getErrorStream()) {
			int readCount = stderr.read(readBuffer);

			while (readCount >= 0) {
				stderrTail.append(readBuffer, readCount);
				readCount = stderr.read(readBuffer);
			}
		} catch (IOException exception) {
			// 进程被结束时管道关闭，读取异常属于预期
		}
	}

	/**
	 * 只保留 stderr 末尾若干字节，用于失败诊断
	 */
	private static final class StderrTail {

		private final int maxBytes;
		private byte[] tail = new byte[0];

		private StderrTail(int maxBytes) {
			this.maxBytes = maxBytes;
		}

		private synchronized void append(byte[] bytes, int length) {
			if (maxBytes <= 0) {
				return;
			}

			int combinedLength = tail.length + length;
			int keepLength = Math.min(combinedLength, maxBytes);
			byte[] combined = new byte[keepLength];
			int skip = combinedLength - keepLength;

			if (skip < tail.length) {
				System.arraycopy(tail, skip, combined, 0, tail.length - skip);
				System.arraycopy(bytes, 0, combined, tail.length - skip, length);
			} else {
				System.arraycopy(bytes, skip - tail.length, combined, 0, keepLength);
			}

			tail = combined;
		}

		private synchronized String toText() {
			return new String(tail, StandardCharsets.UTF_8);
		}
	}
}
