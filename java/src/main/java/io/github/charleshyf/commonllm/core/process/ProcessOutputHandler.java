package io.github.charleshyf.commonllm.core.process;

import io.github.charleshyf.commonllm.core.ErrorInfo;

/**
 * 接收子进程 stdout 行与结束通知的回调
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public interface ProcessOutputHandler {

	/**
	 * 收到一行完整输出，不含行尾换行符
	 */
	void onLine(String line);

	/**
	 * 输出超过上限，进程已被结束
	 */
	void onOutputViolation(ErrorInfo error);

	/**
	 * stdout 读取完毕且进程已退出，附带 stderr 末尾内容用于诊断
	 */
	void onExit(int exitCode, String stderrTail);
}
