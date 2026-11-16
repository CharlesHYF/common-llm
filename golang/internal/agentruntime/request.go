// Agent 请求的公共解析：超时、工作目录、环境变量允许列表与启动前失败事件
// 创建日期：2026-11-16
// 修改日期：2026-11-16

package agentruntime

import (
	"iter"
	"strings"
	"time"

	"github.com/CharlesHYF/common-llm/golang/config"
	"github.com/CharlesHYF/common-llm/golang/core"
)

// ValidateAgentRequest 校验请求必填字段与 agent 名称。
func ValidateAgentRequest(request *core.AgentRequest, agent string) error {
	if request == nil {
		return core.NewError(core.CategoryConfigInvalid, "AgentRequest 不能为空", agent)
	}

	if request.Agent != "" && request.Agent != agent {
		return core.NewError(core.CategoryConfigInvalid, "request.agent 与客户端不一致："+request.Agent, agent)
	}

	if strings.TrimSpace(request.Prompt) == "" {
		return core.NewError(core.CategoryConfigInvalid, "prompt 不能为空", agent)
	}

	if request.TimeoutSeconds != nil && *request.TimeoutSeconds <= 0 {
		return core.NewError(core.CategoryConfigInvalid, "timeoutSeconds 必须大于 0", agent)
	}

	return nil
}

// ResolveRunTimeout 请求 timeoutSeconds 优先，其次配置 timeout_seconds。
func ResolveRunTimeout(request *core.AgentRequest, agentConfig config.AgentConfig) time.Duration {
	if request.TimeoutSeconds != nil {
		return SecondsToDuration(*request.TimeoutSeconds)
	}

	return SecondsToDuration(agentConfig.TimeoutSeconds)
}

// EnvAllowlists 返回配置与请求中的环境变量允许列表。
func EnvAllowlists(request *core.AgentRequest, agentConfig config.AgentConfig) [][]string {
	return [][]string{
		agentConfig.EnvAllowlist,
		request.EnvAllowlist,
	}
}

// FailedBeforeStart 启动前失败（配置非法、CLI 不存在、工作目录非法等）时返回只含一个 failed 事件的事件流。
func FailedBeforeStart(builder *core.AgentEventBuilder, err error, agent string) iter.Seq[core.AgentEvent] {
	commonErr := core.AsError(err, agent)
	event := builder.NewEvent(core.AgentEventFailed)
	info := commonErr.Info
	event.Error = &info
	event.Result = builder.BuildResult(core.AgentStatusFailed, "", nil, nil, &info)

	return func(yield func(core.AgentEvent) bool) {
		yield(event)
	}
}

// TerminalFailed 组装 failed 终止事件，事件内同时携带结果供 Run 汇总。
func TerminalFailed(builder *core.AgentEventBuilder, err *core.Error, finalText string, usage *core.AgentUsage, exitCode *int) core.AgentEvent {
	event := builder.NewEvent(core.AgentEventFailed)
	info := err.Info
	event.Error = &info
	event.Result = builder.BuildResult(core.AgentStatusFailed, finalText, usage, exitCode, &info)

	return event
}

// TerminalCancelled 组装 cancelled 终止事件。
func TerminalCancelled(builder *core.AgentEventBuilder, finalText string, usage *core.AgentUsage, exitCode *int, agent string) core.AgentEvent {
	event := builder.NewEvent(core.AgentEventCancelled)
	info := core.NewError(core.CategoryCancelled, "run 已取消", agent).Info
	event.Result = builder.BuildResult(core.AgentStatusCancelled, finalText, usage, exitCode, &info)

	return event
}

// TerminalCompleted 组装 completed 终止事件。
func TerminalCompleted(builder *core.AgentEventBuilder, finalText string, usage *core.AgentUsage, exitCode *int) core.AgentEvent {
	event := builder.NewEvent(core.AgentEventCompleted)
	event.Result = builder.BuildResult(core.AgentStatusCompleted, finalText, usage, exitCode, nil)

	return event
}

// RawVendorEvent 组装 raw_vendor_event。
func RawVendorEvent(builder *core.AgentEventBuilder, vendorType string, payload map[string]any) core.AgentEvent {
	event := builder.NewEvent(core.AgentEventRawVendor)
	event.VendorType = vendorType
	event.Payload = payload

	if event.Payload == nil {
		event.Payload = map[string]any{}
	}

	return event
}
