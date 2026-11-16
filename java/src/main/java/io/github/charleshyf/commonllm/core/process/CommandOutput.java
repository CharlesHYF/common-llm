package io.github.charleshyf.commonllm.core.process;

/**
 * 短命令执行结果
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class CommandOutput {

	private final int exitCode;
	private final String stdout;
	private final String stderr;

	public CommandOutput(int exitCode, String stdout, String stderr) {
		this.exitCode = exitCode;
		this.stdout = stdout;
		this.stderr = stderr;
	}

	public int getExitCode() {
		return exitCode;
	}

	public String getStdout() {
		return stdout;
	}

	public String getStderr() {
		return stderr;
	}
}
