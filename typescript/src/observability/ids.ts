/**
 * 定义 traceId 与 runId 的生成接口，测试可注入固定值
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

import { randomUUID } from "node:crypto";

export interface IdGenerator {
	createTraceId(): string;
	createRunId(): string;
}

export const UUID_ID_GENERATOR: IdGenerator = {
	createTraceId: () => randomUUID(),
	createRunId: () => randomUUID(),
};
