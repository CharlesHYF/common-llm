package io.github.charleshyf.commonllm.observability;

import java.util.UUID;

/**
 * 生成 traceId 与 runId，测试可注入固定实现
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public interface IdGenerator {

	String newTraceId();

	String newRunId();

	/**
	 * 默认实现：两者都使用随机 UUID
	 */
	static IdGenerator uuid() {
		return new IdGenerator() {
			@Override
			public String newTraceId() {
				return UUID.randomUUID().toString();
			}

			@Override
			public String newRunId() {
				return UUID.randomUUID().toString();
			}
		};
	}
}
