/**
 * 导出契约类型、错误、能力与脱敏工具等核心公开 API
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

export { AGENT_FEATURES, hasFeature, PROVIDER_FEATURES } from "./capabilities.js";
export {
	classifyHttpStatus,
	CommonLlmError,
	createError,
	ERROR_CATEGORIES,
	isCommonLlmError,
} from "./errors.js";
export {
	createImageInputFromBytes,
	createImageInputFromFile,
	serializeImageEvent,
	serializeImageResponse,
} from "./image-support.js";
export { redactObject, redactText, REDACTED_VALUE } from "./redaction.js";

export type { AgentClient, ImageClient, ModelClient } from "./clients.js";
export type { ErrorCategory, ErrorInfo, ErrorOptions } from "./errors.js";
export type {
	AgentCapability,
	AgentEvent,
	AgentEventType,
	AgentFeature,
	AgentImplementation,
	AgentName,
	AgentRequest,
	AgentResult,
	AgentRunOptions,
	AgentStatus,
	AgentUsage,
	ApprovalDecision,
	ApprovalHandler,
	ApprovalKind,
	ApprovalRequest,
	CallOptions,
	ContentPart,
	FileChange,
	FileChangeKind,
	FinishReason,
	GeneratedImage,
	ImageEvent,
	ImageInput,
	ImageRequest,
	ImageResponse,
	ImagePart,
	Message,
	MessageRole,
	ModelEvent,
	ModelRequest,
	ModelResponse,
	ProviderCapability,
	ProviderFeature,
	ResponseContentPart,
	ResponseFormat,
	SandboxMode,
	TextPart,
	ToolCall,
	ToolCallPart,
	ToolChoice,
	ToolDefinition,
	ToolResultPart,
	Usage,
} from "./types.js";
