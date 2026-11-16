// ModelClient 与 AgentClient 接口、能力结构与 ID 生成器
// 创建日期：2026-11-16
// 修改日期：2026-11-16

package core

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"iter"
	"slices"
)

// Version 本库版本号
const Version = "1.0.0"

// 模型能力名称
const (
	FeatureStreaming        = "streaming"
	FeatureToolCalls        = "tool_calls"
	FeatureStructuredOutput = "structured_output"
	FeatureVision           = "vision"
	FeatureReasoning        = "reasoning"
)

// Agent 能力名称
const (
	FeatureSessionResume = "session_resume"
	FeatureApprovals     = "approvals"
	FeatureInterrupt     = "interrupt"
	FeatureCost          = "cost"
)

// Agent 实现方式
const (
	ImplementationSDK       = "sdk"
	ImplementationCLI       = "cli"
	ImplementationAppServer = "app_server"
)

// ProviderCapability Provider 实际支持的能力集合。
type ProviderCapability struct {
	Provider string   `json:"provider"`
	Features []string `json:"features"`
}

// AgentCapability Agent 实现方式、版本与实际支持的能力集合。
type AgentCapability struct {
	Agent            string   `json:"agent"`
	Implementation   string   `json:"implementation"`
	ExecutablePath   string   `json:"executablePath"`
	Version          string   `json:"version"`
	Features         []string `json:"features"`
	EventTypes       []string `json:"eventTypes"`
	SupportedOptions []string `json:"supportedOptions,omitempty"`
}

// HasFeature 判断能力集合是否包含指定能力，未知能力返回 false。
func HasFeature(features []string, feature string) bool {
	return slices.Contains(features, feature)
}

// ModelClient 单个 Provider 实例的模型调用客户端。
type ModelClient interface {
	// Generate 发起非流式调用。
	Generate(ctx context.Context, request *ModelRequest) (*ModelResponse, error)
	// Stream 发起流式调用，失败（含预检失败）以唯一的 error 终止事件返回，不另行返回错误。
	Stream(ctx context.Context, request *ModelRequest) iter.Seq[ModelEvent]
	// Supports 判断是否支持指定能力。
	Supports(feature string) bool
	// Capability 返回能力集合。
	Capability() ProviderCapability
	// NativeClient 返回底层官方 SDK 客户端。
	NativeClient() any
	// Close 释放客户端资源。
	Close() error
}

// AgentClient 单个 Coding Agent 的调用客户端。
type AgentClient interface {
	// Run 执行一次 run，失败（含预检失败）以 status=failed 且 error 非空的结果返回。
	Run(ctx context.Context, request *AgentRequest) *AgentResult
	// RunStream 执行一次 run 并逐个返回事件，失败以唯一的 failed 终止事件返回。
	RunStream(ctx context.Context, request *AgentRequest) iter.Seq[AgentEvent]
	// Resume 在指定会话上继续执行。
	Resume(ctx context.Context, sessionID string, request *AgentRequest) *AgentResult
	// Interrupt 中断指定 run。
	Interrupt(ctx context.Context, runID string) error
	// Capabilities 探测并返回能力集合，结果缓存到 Close 为止。
	Capabilities(ctx context.Context) (*AgentCapability, error)
	// Close 结束全部进行中的 run 并释放资源。
	Close() error
}

// IDGenerator 生成 runId 与 traceId，测试可注入固定值。
type IDGenerator interface {
	NewRunID() string
	NewTraceID() string
}

// UUIDGenerator 默认 ID 生成器，生成随机 UUID v4。
type UUIDGenerator struct{}

// NewRunID 生成 runId。
func (UUIDGenerator) NewRunID() string {
	return NewUUID()
}

// NewTraceID 生成 traceId。
func (UUIDGenerator) NewTraceID() string {
	return NewUUID()
}

// NewUUID 生成随机 UUID v4 字符串。
func NewUUID() string {
	raw := make([]byte, 16)
	_, _ = rand.Read(raw)
	// 按 RFC 4122 设置版本 4 与变体位
	raw[6] = (raw[6] & 0x0f) | 0x40
	raw[8] = (raw[8] & 0x3f) | 0x80
	encoded := hex.EncodeToString(raw)

	return encoded[0:8] + "-" + encoded[8:12] + "-" + encoded[12:16] + "-" + encoded[16:20] + "-" + encoded[20:32]
}

// ResolveTraceID 请求未传 traceId 时用生成器生成。
func ResolveTraceID(traceID string, generator IDGenerator) string {
	if traceID != "" {
		return traceID
	}

	if generator == nil {
		return NewUUID()
	}

	return generator.NewTraceID()
}
