/**
 * 定义 ModelClient 与 AgentClient 的公共接口
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

import type { AgentConfig, ProviderConfig } from "../config/types.js";
import type { CommonLlmLogger } from "../observability/logger.js";
import type { IdGenerator } from "../observability/ids.js";
import type {
	AgentCapability,
	AgentEvent,
	AgentName,
	AgentRequest,
	AgentResult,
	AgentRunOptions,
	CallOptions,
	ImageEvent,
	ImageRequest,
	ImageResponse,
	ModelEvent,
	ModelRequest,
	ModelResponse,
	ProviderCapability,
} from "./types.js";

export interface ProviderClientOptions {
	name: string;
	config: ProviderConfig;
	logger: CommonLlmLogger;
	idGenerator: IdGenerator;
}

export interface AgentClientOptions {
	config: AgentConfig;
	logger: CommonLlmLogger;
	idGenerator: IdGenerator;
	environment?: NodeJS.ProcessEnv;
}

export interface ModelClient {
	readonly provider: string;
	generate(request: ModelRequest, options?: CallOptions): Promise<ModelResponse>;
	stream(request: ModelRequest, options?: CallOptions): AsyncIterable<ModelEvent>;
	supports(feature: string): boolean;
	capability(): ProviderCapability;
	nativeClient(): unknown;
	close(): Promise<void>;
}

export interface ImageClient {
	readonly provider: string;
	generate(request: ImageRequest, options?: CallOptions): Promise<ImageResponse>;
	stream(request: ImageRequest, options?: CallOptions): AsyncIterable<ImageEvent>;
	supports(feature: string): boolean;
	capability(): ProviderCapability;
	nativeClient(): unknown;
	close(): Promise<void>;
}

export interface AgentClient {
	readonly agent: AgentName;
	run(request: AgentRequest, options?: AgentRunOptions): Promise<AgentResult>;
	runStream(request: AgentRequest, options?: AgentRunOptions): AsyncIterable<AgentEvent>;
	resume(sessionId: string, request: AgentRequest, options?: AgentRunOptions): Promise<AgentResult>;
	interrupt(runId: string): Promise<boolean>;
	capabilities(): Promise<AgentCapability>;
	close(): Promise<void>;
}
