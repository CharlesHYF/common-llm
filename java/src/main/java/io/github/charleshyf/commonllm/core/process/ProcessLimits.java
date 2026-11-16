package io.github.charleshyf.commonllm.core.process;

import java.time.Duration;

/**
 * 子进程的输出上限与结束等待时间
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class ProcessLimits {

	private final long maxOutputBytes;
	private final int maxLineBytes;
	private final int maxStderrBytes;
	private final Duration killGrace;

	public ProcessLimits(long maxOutputBytes, int maxLineBytes, int maxStderrBytes, Duration killGrace) {
		this.maxOutputBytes = maxOutputBytes;
		this.maxLineBytes = maxLineBytes;
		this.maxStderrBytes = maxStderrBytes;
		this.killGrace = killGrace;
	}

	public long getMaxOutputBytes() {
		return maxOutputBytes;
	}

	public int getMaxLineBytes() {
		return maxLineBytes;
	}

	public int getMaxStderrBytes() {
		return maxStderrBytes;
	}

	public Duration getKillGrace() {
		return killGrace;
	}
}
