package io.github.charleshyf.commonllm.core.process;

import io.github.charleshyf.commonllm.core.CommonLlmException;
import io.github.charleshyf.commonllm.core.ErrorCategory;
import io.github.charleshyf.commonllm.core.ErrorInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 执行探测类短命令并收集输出
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class CommandRunner {

	private CommandRunner() {
	}

	/**
	 * 执行命令并等待结束，超时结束进程并报 TIMEOUT
	 */
	public static CommandOutput run(List<String> command, Path workingDirectory, Map<String, String> environment, Duration timeout, String agent) {
		ProcessBuilder processBuilder = new ProcessBuilder(command);
		processBuilder.redirectInput(ProcessBuilder.Redirect.PIPE);

		if (workingDirectory != null) {
			processBuilder.directory(workingDirectory.toFile());
		}

		processBuilder.environment().clear();
		processBuilder.environment().putAll(environment);
		Process process;

		try {
			process = processBuilder.start();
		} catch (IOException exception) {
			throw new CommonLlmException(ErrorInfo.builder(ErrorCategory.CLI_NOT_FOUND, "无法执行 CLI：" + command.get(0)).provider(agent).cause(exception).build(), exception);
		}

		closeQuietly(process);
		ByteArrayOutputStream stdout = new ByteArrayOutputStream();
		ByteArrayOutputStream stderr = new ByteArrayOutputStream();
		Thread stdoutThread = startCopy(process.getInputStream(), stdout);
		Thread stderrThread = startCopy(process.getErrorStream(), stderr);

		try {
			if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
				ProcessTreeTerminator.terminate(process.toHandle(), Duration.ZERO);
				throw CommonLlmException.of(ErrorCategory.TIMEOUT, "执行 CLI 探测命令超时：" + String.join(" ", command), agent);
			}

			stdoutThread.join(timeout.toMillis());
			stderrThread.join(timeout.toMillis());
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			ProcessTreeTerminator.terminate(process.toHandle(), Duration.ZERO);
			throw CommonLlmException.of(ErrorCategory.CANCELLED, "执行 CLI 探测命令被中断", agent);
		}

		return new CommandOutput(process.exitValue(), stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
	}

	private static void closeQuietly(Process process) {
		try {
			process.getOutputStream().close();
		} catch (IOException exception) {
			// 探测命令不读取 stdin，关闭失败无影响
		}
	}

	private static Thread startCopy(InputStream source, ByteArrayOutputStream target) {
		Thread copyThread = new Thread(() -> copyStream(source, target), "common-llm-probe-reader");
		copyThread.setDaemon(true);
		copyThread.start();
		return copyThread;
	}

	private static void copyStream(InputStream source, ByteArrayOutputStream target) {
		byte[] readBuffer = new byte[4096];

		try (InputStream input = source) {
			int readCount = input.read(readBuffer);

			while (readCount >= 0) {
				synchronized (target) {
					target.write(readBuffer, 0, readCount);
				}

				readCount = input.read(readBuffer);
			}
		} catch (IOException exception) {
			// 进程结束后管道关闭，读取异常属于预期
		}
	}
}
