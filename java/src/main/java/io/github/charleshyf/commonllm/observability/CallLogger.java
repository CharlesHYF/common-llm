package io.github.charleshyf.commonllm.observability;

import io.github.charleshyf.commonllm.core.AgentUsage;
import io.github.charleshyf.commonllm.core.ErrorInfo;
import io.github.charleshyf.commonllm.core.Usage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 记录模型与 Agent 调用的结构化日志，不记录 prompt、正文与密钥
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class CallLogger {

	private static final Logger LOGGER = LoggerFactory.getLogger("io.github.charleshyf.commonllm");

	private CallLogger() {
	}

	/**
	 * 记录一次模型调用的结果，error 为 null 表示成功
	 */
	public static void logModelCall(String provider, String model, long durationMillis, String traceId, Usage usage, ErrorInfo error) {
		if (error == null) {
			LOGGER.info("模型调用完成 provider={} model={} durationMs={} traceId={} usage={}", provider, model, durationMillis, traceId, usage);
			return;
		}

		LOGGER.warn("模型调用失败 provider={} model={} durationMs={} traceId={} category={} statusCode={}", provider, model, durationMillis, traceId, error.getCategory(), error.getStatusCode());
	}

	/**
	 * 记录一次 Agent 运行的结果，error 为 null 表示成功
	 */
	public static void logAgentRun(String agent, String runId, long durationMillis, String traceId, AgentUsage usage, ErrorInfo error) {
		if (error == null) {
			LOGGER.info("Agent 运行结束 agent={} runId={} durationMs={} traceId={} usage={}", agent, runId, durationMillis, traceId, usage);
			return;
		}

		LOGGER.warn("Agent 运行失败 agent={} runId={} durationMs={} traceId={} category={}", agent, runId, durationMillis, traceId, error.getCategory());
	}

	/**
	 * 记录使用高风险权限的警告
	 */
	public static void warnDangerousPermission(String agent, String option) {
		LOGGER.warn("Agent 使用了高风险权限 agent={} option={}", agent, option);
	}

	public static void debug(String message, Object... arguments) {
		LOGGER.debug(message, arguments);
	}
}
