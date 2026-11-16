/**
 * 定义模型调用与 Agent 调用的跨语言契约类型
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

import type { ErrorInfo } from "./errors.js";

export type MessageRole = "system" | "user" | "assistant" | "tool";

export interface TextPart {
	type: "text";
	text: string;
}

export interface ImagePart {
	type: "image";
	url?: string;
	base64?: string;
	mimeType?: string;
}

export interface ToolCallPart {
	type: "tool_call";
	id: string;
	name: string;
	arguments: string;
}

export interface ToolResultPart {
	type: "tool_result";
	toolCallId: string;
	content: string;
	isError?: boolean;
}

export type ContentPart = TextPart | ImagePart | ToolCallPart | ToolResultPart;

export type ResponseContentPart = TextPart | ToolCallPart;

export interface Message {
	role: MessageRole;
	content: ContentPart[];
}

export interface ToolDefinition {
	name: string;
	description?: string | null;
	parameters: Record<string, unknown>;
}

export type ToolChoice =
	| {
		type: "auto" | "none" | "required";
	}
	| {
		type: "tool";
		name: string;
	};

export type ResponseFormat =
	| {
		type: "text";
	}
	| {
		type: "json_schema";
		name: string;
		schema: Record<string, unknown>;
		strict?: boolean | null;
	};

export interface ModelRequest {
	provider: string;
	model?: string | null;
	messages: Message[];
	temperature?: number | null;
	maxOutputTokens?: number | null;
	timeoutSeconds?: number | null;
	tools?: ToolDefinition[] | null;
	toolChoice?: ToolChoice | null;
	responseFormat?: ResponseFormat | null;
	providerOptions?: Record<string, unknown> | null;
	includeRawResponse?: boolean | null;
	traceId?: string | null;
	requestId?: string | null;
	metadata?: Record<string, string> | null;
}

export interface ToolCall {
	id: string;
	name: string;
	arguments: string;
}

export interface Usage {
	inputTokens: number | null;
	outputTokens: number | null;
	cachedInputTokens: number | null;
	reasoningTokens: number | null;
	totalTokens: number | null;
}

export type FinishReason = "stop" | "length" | "tool_calls" | "content_filter" | "other";

export interface ModelResponse {
	provider: string;
	model: string | null;
	id: string | null;
	contentParts: ResponseContentPart[];
	text: string;
	toolCalls: ToolCall[];
	finishReason: FinishReason;
	vendorFinishReason: string | null;
	usage: Usage;
	traceId: string;
	requestId: string | null;
	rawResponse?: Record<string, unknown> | null;
}

interface ModelEventBase {
	traceId: string;
	sequence: number;
	provider: string;
}

export interface ModelStartedEvent extends ModelEventBase {
	type: "started";
	model: string | null;
	id: string | null;
}

export interface ModelTextDeltaEvent extends ModelEventBase {
	type: "text_delta";
	text: string;
}

export interface ModelToolCallDeltaEvent extends ModelEventBase {
	type: "tool_call_delta";
	index: number;
	id: string | null;
	name: string | null;
	argumentsDelta: string;
}

export interface ModelUsageEvent extends ModelEventBase {
	type: "usage";
	usage: Usage;
}

export interface ModelCompletedEvent extends ModelEventBase {
	type: "completed";
	response: ModelResponse;
}

export interface ModelErrorEvent extends ModelEventBase {
	type: "error";
	error: ErrorInfo;
}

export type ModelEvent =
	| ModelStartedEvent
	| ModelTextDeltaEvent
	| ModelToolCallDeltaEvent
	| ModelUsageEvent
	| ModelCompletedEvent
	| ModelErrorEvent;

export type AgentName = "claude_code" | "codex";

export type SandboxMode = "read-only" | "workspace-write" | "danger-full-access";

export interface AgentRequest {
	agent: AgentName;
	prompt: string;
	workingDirectory?: string | null;
	sessionId?: string | null;
	model?: string | null;
	timeoutSeconds?: number | null;
	permissionMode?: string | null;
	sandboxMode?: SandboxMode | null;
	allowedTools?: string[] | null;
	envAllowlist?: string[] | null;
	nativeOptions?: Record<string, unknown> | null;
	traceId?: string | null;
}

export type ApprovalDecision = "approve" | "approve_for_session" | "decline" | "cancel";

export type ApprovalKind = "command" | "file_change" | "tool";

export interface ApprovalRequest {
	approvalId: string;
	kind: ApprovalKind;
	summary: string;
	toolName: string | null;
	input: Record<string, unknown> | null;
	runId: string;
	sessionId: string | null;
}

export type ApprovalHandler = (request: ApprovalRequest) => ApprovalDecision | Promise<ApprovalDecision>;

export interface AgentUsage extends Usage {
	costUsd: number | null;
	numTurns: number | null;
}

export type AgentStatus = "completed" | "failed" | "cancelled";

export interface AgentResult {
	runId: string;
	sessionId: string | null;
	status: AgentStatus;
	finalText: string;
	events?: AgentEvent[] | null;
	usage: AgentUsage | null;
	exitCode: number | null;
	error: ErrorInfo | null;
}

export type FileChangeKind = "add" | "update" | "delete";

export interface FileChange {
	path: string;
	kind: FileChangeKind;
}

interface AgentEventBase {
	traceId: string;
	runId: string;
	sessionId: string | null;
	sequence: number;
	agent: AgentName;
}

export interface AgentStartedEvent extends AgentEventBase {
	type: "started";
	model: string | null;
	workingDirectory: string;
}

export interface AgentMessageEvent extends AgentEventBase {
	type: "message";
	role: "assistant" | "user";
	text: string;
	isDelta: boolean;
}

export interface AgentToolStartedEvent extends AgentEventBase {
	type: "tool_started";
	toolCallId: string;
	toolName: string;
	input: Record<string, unknown> | null;
}

export interface AgentToolCompletedEvent extends AgentEventBase {
	type: "tool_completed";
	toolCallId: string;
	toolName: string | null;
	output: string | null;
	isError: boolean;
}

export interface AgentFileChangedEvent extends AgentEventBase {
	type: "file_changed";
	changes: FileChange[];
}

export interface AgentApprovalRequestedEvent extends AgentEventBase {
	type: "approval_requested";
	approvalId: string;
	kind: ApprovalKind;
	summary: string;
	decision: ApprovalDecision;
}

export interface AgentUsageEvent extends AgentEventBase {
	type: "usage";
	usage: AgentUsage;
}

export interface AgentCompletedEvent extends AgentEventBase {
	type: "completed";
	result: AgentResult;
}

export interface AgentFailedEvent extends AgentEventBase {
	type: "failed";
	error: ErrorInfo;
}

export interface AgentCancelledEvent extends AgentEventBase {
	type: "cancelled";
}

export interface AgentRawVendorEvent extends AgentEventBase {
	type: "raw_vendor_event";
	vendorType: string;
	payload: Record<string, unknown>;
}

export type AgentEvent =
	| AgentStartedEvent
	| AgentMessageEvent
	| AgentToolStartedEvent
	| AgentToolCompletedEvent
	| AgentFileChangedEvent
	| AgentApprovalRequestedEvent
	| AgentUsageEvent
	| AgentCompletedEvent
	| AgentFailedEvent
	| AgentCancelledEvent
	| AgentRawVendorEvent;

export type AgentEventType = AgentEvent["type"];

export type ProviderFeature = "streaming" | "tool_calls" | "structured_output" | "vision" | "reasoning";

export interface ProviderCapability {
	provider: string;
	features: ProviderFeature[];
}

export type AgentFeature = "streaming" | "session_resume" | "approvals" | "interrupt" | "cost";

export type AgentImplementation = "sdk" | "cli" | "app_server";

export interface AgentCapability {
	agent: AgentName;
	implementation: AgentImplementation;
	executablePath: string | null;
	version: string | null;
	features: AgentFeature[];
	eventTypes: AgentEventType[];
	supportedOptions: string[] | null;
}

export interface CallOptions {
	signal?: AbortSignal;
}

export interface AgentRunOptions {
	signal?: AbortSignal;
	approvalHandler?: ApprovalHandler;
}
