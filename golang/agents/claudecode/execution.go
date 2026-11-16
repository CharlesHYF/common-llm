// 单次 Claude Code run 的子进程驱动与 stream-json 消息映射
// 创建日期：2026-11-16
// 修改日期：2026-11-16

package claudecode

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"strconv"
	"strings"
	"sync"

	"github.com/CharlesHYF/common-llm/golang/core"
	"github.com/CharlesHYF/common-llm/golang/internal/agentruntime"
)

// Claude Code stream-json 消息类型与子类型
const (
	messageTypeSystem   = "system"
	blockTypeText       = "text"
	roleAssistant       = "assistant"
	stopReasonNone      = ""
	stopReasonCancelled = "cancelled"
	stopReasonTimeout   = "timeout"
	stopReasonFatal     = "fatal"
)

// claudeMessage stream-json 一行消息中适配器关心的字段
type claudeMessage struct {
	Type         string             `json:"type"`
	Subtype      string             `json:"subtype"`
	SessionID    string             `json:"session_id"`
	Model        *string            `json:"model"`
	Cwd          string             `json:"cwd"`
	ToolName     string             `json:"tool_name"`
	ToolUseID    string             `json:"tool_use_id"`
	Message      json.RawMessage    `json:"message"`
	Event        *claudeStreamEvent `json:"event"`
	Result       *string            `json:"result"`
	IsError      *bool              `json:"is_error"`
	TotalCostUSD *float64           `json:"total_cost_usd"`
	NumTurns     *int64             `json:"num_turns"`
	Usage        *claudeUsage       `json:"usage"`
	Errors       []any              `json:"errors"`
}

// claudeInnerMessage assistant 与 user 消息的 message 字段
type claudeInnerMessage struct {
	Content json.RawMessage `json:"content"`
}

// claudeContentBlock 消息内容块
type claudeContentBlock struct {
	Type      string          `json:"type"`
	Text      string          `json:"text"`
	ID        string          `json:"id"`
	Name      string          `json:"name"`
	Input     json.RawMessage `json:"input"`
	ToolUseID string          `json:"tool_use_id"`
	Content   json.RawMessage `json:"content"`
	IsError   *bool           `json:"is_error"`
}

// claudeStreamEvent partial 模式下的流式事件
type claudeStreamEvent struct {
	Type  string `json:"type"`
	Delta *struct {
		Type string `json:"type"`
		Text string `json:"text"`
	} `json:"delta"`
}

// claudeUsage result 消息的用量
type claudeUsage struct {
	InputTokens              *int64 `json:"input_tokens"`
	CacheCreationInputTokens *int64 `json:"cache_creation_input_tokens"`
	CacheReadInputTokens     *int64 `json:"cache_read_input_tokens"`
	OutputTokens             *int64 `json:"output_tokens"`
	OutputTokensDetails      *struct {
		ThinkingTokens *int64 `json:"thinking_tokens"`
	} `json:"output_tokens_details"`
}

// runExecution 单次 run 的状态，只在执行协程内修改
type runExecution struct {
	client        *Client
	plan          *runPlan
	builder       *core.AgentEventBuilder
	queue         *agentruntime.EventQueue
	ctx           context.Context
	cancel        context.CancelFunc
	interruptOnce sync.Once
	interrupted   chan struct{}
	toolNames     map[string]string
	result        *claudeMessage
	usage         *core.AgentUsage
	stopReason    string
	fatalErr      *core.Error
}

// newRunExecution 创建 run 状态
func newRunExecution(client *Client, plan *runPlan, builder *core.AgentEventBuilder, ctx context.Context, cancel context.CancelFunc) *runExecution {
	return &runExecution{
		client:      client,
		plan:        plan,
		builder:     builder,
		queue:       agentruntime.NewEventQueue(client.config.MaxBufferedEvents),
		ctx:         ctx,
		cancel:      cancel,
		interrupted: make(chan struct{}),
		toolNames:   map[string]string{},
	}
}

// interrupt 请求结束 run，可重复调用
func (r *runExecution) interrupt() {
	r.interruptOnce.Do(func() {
		close(r.interrupted)
	})
}

// execute 启动子进程、逐行映射输出并发出唯一的终止事件
func (r *runExecution) execute() {
	defer r.queue.Close()

	process, err := agentruntime.StartProcess(agentruntime.ProcessOptions{
		ExecutablePath: r.plan.executablePath,
		Args:           r.plan.args,
		Dir:            r.plan.workingDirectory,
		Env:            r.plan.env,
		KillGrace:      agentruntime.SecondsToDuration(r.client.config.KillGraceSeconds),
		MaxLineBytes:   r.client.config.MaxLineBytes,
		MaxOutputBytes: r.client.config.MaxOutputBytes,
		MaxStderrBytes: r.client.config.MaxStderrBytes,
		Provider:       core.AgentClaudeCode,
	})
	if err != nil {
		r.queue.Push(agentruntime.TerminalFailed(r.builder, core.AsError(err, core.AgentClaudeCode), "", nil, nil))
		return
	}

	go func() {
		_ = process.WriteAndCloseStdin([]byte(r.plan.prompt))
	}()

	r.consumeOutput(process)
	exitCode := process.Wait()
	r.queue.Push(r.buildTerminalEvent(process, exitCode))
}

// consumeOutput 读取全部输出，收到中断、超时或协议错误时结束进程组并继续读完剩余输出
func (r *runExecution) consumeOutput(process *agentruntime.Process) {
	lines := process.Lines()
	interrupted := r.interrupted
	done := r.ctx.Done()

	for {
		select {
		case lineResult, isOpen := <-lines:
			if !isOpen {
				return
			}

			if r.stopReason != stopReasonNone {
				continue
			}

			if lineResult.Err != nil {
				r.stop(process, stopReasonFatal, lineResult.Err)
				agentruntime.DrainLines(lines)
				return
			}

			r.handleLine(lineResult.Line)
		case <-interrupted:
			interrupted = nil
			r.stop(process, stopReasonCancelled, nil)
		case <-done:
			done = nil
			reason := stopReasonCancelled
			if r.ctx.Err() == context.DeadlineExceeded {
				reason = stopReasonTimeout
			}

			r.stop(process, reason, nil)
		}
	}
}

// stop 记录结束原因并结束进程组，只记录第一次原因
func (r *runExecution) stop(process *agentruntime.Process, reason string, fatalErr *core.Error) {
	if r.stopReason != stopReasonNone {
		return
	}

	r.stopReason = reason
	r.fatalErr = fatalErr
	process.Terminate()
}

// buildTerminalEvent 按结束原因、result 消息与退出码组装终止事件
func (r *runExecution) buildTerminalEvent(process *agentruntime.Process, exitCode int) core.AgentEvent {
	exitCodePtr := core.IntPtr(exitCode)
	finalText := ""

	if r.result != nil && r.result.Result != nil {
		finalText = *r.result.Result
	}

	switch r.stopReason {
	case stopReasonCancelled:
		return agentruntime.TerminalCancelled(r.builder, finalText, r.usage, exitCodePtr, core.AgentClaudeCode)
	case stopReasonTimeout:
		timeoutErr := core.NewError(core.CategoryTimeout, "run 超过 timeout_seconds 上限，进程组已结束", core.AgentClaudeCode)
		return agentruntime.TerminalFailed(r.builder, timeoutErr, finalText, r.usage, exitCodePtr)
	case stopReasonFatal:
		return agentruntime.TerminalFailed(r.builder, r.fatalErr, finalText, r.usage, exitCodePtr)
	}

	if r.result != nil {
		isSuccess := r.result.Subtype == "success" && (r.result.IsError == nil || !*r.result.IsError)

		if isSuccess {
			return agentruntime.TerminalCompleted(r.builder, finalText, r.usage, exitCodePtr)
		}

		return agentruntime.TerminalFailed(r.builder, buildResultError(r.result), finalText, r.usage, exitCodePtr)
	}

	stderrTail := core.SanitizeTextWithSecrets(process.StderrTail(), r.plan.secrets)

	if exitCode != 0 {
		processErr := core.NewError(core.CategoryCLIProcessFailed, fmt.Sprintf("Claude Code 进程以退出码 %d 结束且没有 result 消息：%s", exitCode, stderrTail), core.AgentClaudeCode).WithStatusCode(exitCode)
		return agentruntime.TerminalFailed(r.builder, processErr, finalText, r.usage, exitCodePtr)
	}

	protocolErr := core.NewError(core.CategoryCLIProtocolError, "Claude Code 进程结束但没有输出 result 消息", core.AgentClaudeCode)
	return agentruntime.TerminalFailed(r.builder, protocolErr, finalText, r.usage, exitCodePtr)
}

// handleLine 解析一行 stream-json，非法 JSON 转为 raw_vendor_event 且不中断
func (r *runExecution) handleLine(line []byte) {
	var message claudeMessage
	if err := json.Unmarshal(line, &message); err != nil {
		r.queue.Push(agentruntime.RawVendorEvent(r.builder, "invalid_json", map[string]any{
			"line":  core.TruncateRunes(string(line), 2000),
			"error": err.Error(),
		}))
		return
	}

	switch message.Type {
	case messageTypeSystem:
		r.handleSystem(&message, line)
	case "stream_event":
		r.handleStreamEvent(&message)
	case "assistant":
		r.handleAssistant(&message)
	case "user":
		r.handleUser(&message)
	case "result":
		r.handleResult(&message)
	default:
		r.queue.Push(agentruntime.RawVendorEvent(r.builder, message.Type, decodePayload(line)))
	}
}

// handleSystem 处理 init、permission_denied 与其他 system 子类型
func (r *runExecution) handleSystem(message *claudeMessage, line []byte) {
	switch message.Subtype {
	case "init":
		r.builder.SetSessionID(message.SessionID)
		event := r.builder.NewEvent(core.AgentEventStarted)
		event.Model = message.Model
		event.WorkingDirectory = message.Cwd
		r.queue.Push(event)
	case "permission_denied":
		event := r.builder.NewEvent(core.AgentEventApprovalRequested)
		event.ApprovalID = message.ToolUseID
		event.Kind = core.ApprovalKindTool
		event.Summary = message.ToolName
		event.Decision = core.DecisionDecline
		r.queue.Push(event)
	default:
		r.queue.Push(agentruntime.RawVendorEvent(r.builder, messageTypeSystem+"/"+message.Subtype, decodePayload(line)))
	}
}

// handleStreamEvent 只把文本增量映射为 message，其余流式事件与完整消息重复，不发出
func (r *runExecution) handleStreamEvent(message *claudeMessage) {
	if message.Event == nil || message.Event.Type != "content_block_delta" {
		return
	}

	if message.Event.Delta == nil || message.Event.Delta.Type != "text_delta" {
		return
	}

	event := r.builder.NewEvent(core.AgentEventMessage)
	event.Role = roleAssistant
	event.Text = message.Event.Delta.Text
	event.IsDelta = true
	r.queue.Push(event)
}

// handleAssistant 把 text 块映射为 message，tool_use 块映射为 tool_started
func (r *runExecution) handleAssistant(message *claudeMessage) {
	for _, block := range decodeBlocks(message.Message) {
		if block.Type == blockTypeText {
			event := r.builder.NewEvent(core.AgentEventMessage)
			event.Role = roleAssistant
			event.Text = block.Text
			event.IsDelta = false
			r.queue.Push(event)
			continue
		}

		if block.Type == "tool_use" {
			r.toolNames[block.ID] = block.Name
			event := r.builder.NewEvent(core.AgentEventToolStarted)
			event.ToolCallID = block.ID
			event.ToolName = core.StringPtr(block.Name)
			event.Input = decodeObject(block.Input)
			r.queue.Push(event)
		}
	}
}

// handleUser 把 tool_result 块映射为 tool_completed
func (r *runExecution) handleUser(message *claudeMessage) {
	for _, block := range decodeBlocks(message.Message) {
		if block.Type != "tool_result" {
			continue
		}

		event := r.builder.NewEvent(core.AgentEventToolCompleted)
		event.ToolCallID = block.ToolUseID

		if toolName, exists := r.toolNames[block.ToolUseID]; exists {
			event.ToolName = core.StringPtr(toolName)
		}

		event.Output = core.TruncateRunesPtr(stringifyToolResult(block.Content), r.client.config.MaxToolOutputChars)
		event.IsError = block.IsError != nil && *block.IsError
		r.queue.Push(event)
	}
}

// handleResult 记录 result 消息并先发出 usage 事件，终止事件在进程退出后发出
func (r *runExecution) handleResult(message *claudeMessage) {
	r.result = message
	r.usage = buildUsage(message)

	event := r.builder.NewEvent(core.AgentEventUsage)
	event.Usage = r.usage
	r.queue.Push(event)
}

// buildUsage 计算用量，inputTokens 为含缓存创建与缓存命中的总输入量
func buildUsage(message *claudeMessage) *core.AgentUsage {
	usage := &core.AgentUsage{
		CostUSD:  message.TotalCostUSD,
		NumTurns: message.NumTurns,
	}

	if message.Usage == nil {
		return usage
	}

	raw := message.Usage
	if raw.InputTokens != nil {
		totalInput := *raw.InputTokens + valueOrZero(raw.CacheCreationInputTokens) + valueOrZero(raw.CacheReadInputTokens)
		usage.InputTokens = core.Int64Ptr(totalInput)
	}

	usage.OutputTokens = raw.OutputTokens
	usage.CachedInputTokens = raw.CacheReadInputTokens

	if raw.OutputTokensDetails != nil {
		usage.ReasoningTokens = raw.OutputTokensDetails.ThinkingTokens
	}

	usage.TotalTokens = core.SumCounts(usage.InputTokens, usage.OutputTokens)
	return usage
}

// buildResultError 把失败的 result 映射为 PROVIDER_ERROR，vendorCode 为 subtype
func buildResultError(message *claudeMessage) *core.Error {
	errorTexts := []string{}

	for _, item := range message.Errors {
		errorTexts = append(errorTexts, fmt.Sprint(item))
	}

	errorText := strings.Join(errorTexts, "; ")
	if errorText == "" {
		errorText = "Claude Code 执行失败：" + message.Subtype
	}

	return core.NewError(core.CategoryProviderError, errorText, core.AgentClaudeCode).WithVendorCode(message.Subtype)
}

// valueOrZero 可空计数为 nil 时按 0 计
func valueOrZero(value *int64) int64 {
	if value == nil {
		return 0
	}

	return *value
}

// decodeBlocks 解析消息内容块，content 不是数组时返回空
func decodeBlocks(rawMessage json.RawMessage) []claudeContentBlock {
	if len(rawMessage) == 0 {
		return nil
	}

	var inner claudeInnerMessage
	if err := json.Unmarshal(rawMessage, &inner); err != nil {
		return nil
	}

	var blocks []claudeContentBlock
	if err := json.Unmarshal(inner.Content, &blocks); err != nil {
		return nil
	}

	return blocks
}

// decodeObject 解析 JSON 对象，null 或非对象返回 nil
func decodeObject(raw json.RawMessage) map[string]any {
	if len(raw) == 0 {
		return nil
	}

	var decoded map[string]any
	if err := json.Unmarshal(raw, &decoded); err != nil {
		return nil
	}

	return decoded
}

// decodePayload 解析整行为对象，用作 raw_vendor_event 的 payload
func decodePayload(line []byte) map[string]any {
	decoded := decodeObject(json.RawMessage(line))
	if decoded == nil {
		return map[string]any{}
	}

	return decoded
}

// stringifyToolResult 把 tool_result 的 content 统一为字符串，数组形式只拼接 text 元素
func stringifyToolResult(raw json.RawMessage) *string {
	trimmed := bytes.TrimSpace(raw)
	if len(trimmed) == 0 || string(trimmed) == "null" {
		return nil
	}

	var text string
	if err := json.Unmarshal(trimmed, &text); err == nil {
		return &text
	}

	var parts []claudeContentBlock
	if err := json.Unmarshal(trimmed, &parts); err != nil {
		fallback := string(trimmed)
		return &fallback
	}

	texts := []string{}
	for _, part := range parts {
		if part.Type == blockTypeText {
			texts = append(texts, part.Text)
		}
	}

	joined := strings.Join(texts, "\n")
	return &joined
}

// formatScalar 把标量值转换为命令行参数文本
func formatScalar(value any) string {
	switch typed := value.(type) {
	case string:
		return typed
	case float64:
		return strconv.FormatFloat(typed, 'f', -1, 64)
	case int:
		return strconv.Itoa(typed)
	case int64:
		return strconv.FormatInt(typed, 10)
	default:
		return fmt.Sprint(typed)
	}
}
