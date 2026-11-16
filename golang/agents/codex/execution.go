// 单次 Codex run 的 JSON-RPC 流程驱动与通知映射
// 创建日期：2026-11-16
// 修改日期：2026-11-16

package codex

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"sync"
	"time"

	"github.com/CharlesHYF/common-llm/golang/config"
	"github.com/CharlesHYF/common-llm/golang/core"
	"github.com/CharlesHYF/common-llm/golang/internal/agentruntime"
)

// App Server 通知与服务端请求方法名
const (
	requestFileChangeApproval = "item/fileChange/requestApproval"
)

// App Server 条目类型、状态与工具名
const (
	itemTypeUserMessage      = "userMessage"
	itemTypeCommandExecution = "commandExecution"
	itemTypeMcpToolCall      = "mcpToolCall"
	roleAssistant            = "assistant"
	stopReasonNone           = ""
	stopReasonCancelled      = "cancelled"
	stopReasonTimeout        = "timeout"
	stopReasonFatal          = "fatal"
)

// codexDecisions 统一审批结果到 App Server 审批结果的映射
var codexDecisions = map[core.ApprovalDecision]string{
	core.DecisionApprove:           "accept",
	core.DecisionApproveForSession: "acceptForSession",
	core.DecisionDecline:           "decline",
	core.DecisionCancel:            "cancel",
}

// threadResult thread/start 与 thread/resume 响应中适配器关心的字段
type threadResult struct {
	Thread struct {
		ID string `json:"id"`
	} `json:"thread"`
	Model *string `json:"model"`
	Cwd   string  `json:"cwd"`
}

// turnResult turn/start 响应
type turnResult struct {
	Turn turnPayload `json:"turn"`
}

// turnPayload turn 对象
type turnPayload struct {
	ID     string          `json:"id"`
	Status string          `json:"status"`
	Error  json.RawMessage `json:"error"`
}

// turnNotification turn/started 与 turn/completed 的参数
type turnNotification struct {
	Turn turnPayload `json:"turn"`
}

// tokenUsageNotification thread/tokenUsage/updated 的参数
type tokenUsageNotification struct {
	TurnID     string `json:"turnId"`
	TokenUsage struct {
		Total *tokenCounts `json:"total"`
	} `json:"tokenUsage"`
}

// tokenCounts 累计用量
type tokenCounts struct {
	InputTokens           *int64 `json:"inputTokens"`
	CachedInputTokens     *int64 `json:"cachedInputTokens"`
	OutputTokens          *int64 `json:"outputTokens"`
	ReasoningOutputTokens *int64 `json:"reasoningOutputTokens"`
	TotalTokens           *int64 `json:"totalTokens"`
}

// itemNotification item/started 与 item/completed 的参数
type itemNotification struct {
	Item codexItem `json:"item"`
}

// codexItem 条目中适配器关心的字段
type codexItem struct {
	Type             string            `json:"type"`
	ID               string            `json:"id"`
	Text             *string           `json:"text"`
	Command          *string           `json:"command"`
	Cwd              *string           `json:"cwd"`
	Server           *string           `json:"server"`
	Tool             *string           `json:"tool"`
	Arguments        json.RawMessage   `json:"arguments"`
	Query            *string           `json:"query"`
	Status           string            `json:"status"`
	AggregatedOutput *string           `json:"aggregatedOutput"`
	ExitCode         *int64            `json:"exitCode"`
	Changes          []codexFileChange `json:"changes"`
}

// codexFileChange fileChange 条目中的单处变更
type codexFileChange struct {
	Path string          `json:"path"`
	Kind json.RawMessage `json:"kind"`
}

// deltaNotification item/agentMessage/delta 的参数
type deltaNotification struct {
	Delta string `json:"delta"`
}

// approvalParams 审批请求参数
type approvalParams struct {
	ItemID     string  `json:"itemId"`
	ApprovalID *string `json:"approvalId"`
	Command    *string `json:"command"`
	Reason     *string `json:"reason"`
}

// accountReadResult account/read 响应
type accountReadResult struct {
	Account            json.RawMessage `json:"account"`
	RequiresOpenaiAuth bool            `json:"requiresOpenaiAuth"`
}

// runExecution 单次 run 的状态，只在执行协程内修改
type runExecution struct {
	client             *Client
	plan               *runPlan
	builder            *core.AgentEventBuilder
	queue              *agentruntime.EventQueue
	ctx                context.Context
	interruptOnce      sync.Once
	interrupted        chan struct{}
	process            *agentruntime.Process
	connection         *rpcConnection
	threadID           string
	threadModel        *string
	threadCwd          string
	turnID             string
	baselineTotal      *tokenCounts
	turnTotal          *tokenCounts
	finalText          string
	stopReason         string
	fatalErr           *core.Error
	hasTurnCompleted   bool
	turnStatus         string
	turnError          json.RawMessage
	stopDeadline       <-chan time.Time
	isInterruptPending bool
}

// newRunExecution 创建 run 状态
func newRunExecution(client *Client, plan *runPlan, builder *core.AgentEventBuilder, ctx context.Context) *runExecution {
	return &runExecution{
		client:      client,
		plan:        plan,
		builder:     builder,
		queue:       agentruntime.NewEventQueue(client.config.MaxBufferedEvents),
		ctx:         ctx,
		interrupted: make(chan struct{}),
	}
}

// interrupt 请求中断 run，可重复调用
func (r *runExecution) interrupt() {
	r.interruptOnce.Do(func() {
		close(r.interrupted)
	})
}

// errProcessClosed 进程输出结束，用于在等待响应时退出
var errProcessClosed = core.NewError(core.CategoryCLIProcessFailed, "App Server 进程输出已结束", core.AgentCodex)

// execute 启动 App Server，依次完成握手、认证、thread 与 turn，并发出唯一的终止事件
func (r *runExecution) execute() {
	defer r.queue.Close()

	process, err := agentruntime.StartProcess(agentruntime.ProcessOptions{
		ExecutablePath: r.plan.executablePath,
		Args:           []string{commandAppServer, flagListen, listenStdio},
		Dir:            r.plan.workingDirectory,
		Env:            r.plan.env,
		KillGrace:      agentruntime.SecondsToDuration(r.client.config.KillGraceSeconds),
		MaxLineBytes:   r.client.config.MaxLineBytes,
		MaxOutputBytes: r.client.config.MaxOutputBytes,
		MaxStderrBytes: r.client.config.MaxStderrBytes,
		Provider:       core.AgentCodex,
	})
	if err != nil {
		r.queue.Push(agentruntime.TerminalFailed(r.builder, core.AsError(err, core.AgentCodex), "", nil, nil))
		return
	}

	r.process = process
	r.connection = newRPCConnection(process)

	flowErr := r.runProtocol()
	r.shutdown()
	exitCode := process.Wait()

	r.queue.Push(r.buildTerminalEvent(flowErr, exitCode))
}

// runProtocol 按协议顺序发送请求，返回值为 nil 表示收到了本 turn 的 turn/completed
func (r *runExecution) runProtocol() error {
	if _, err := r.call(methodInitialize, buildInitializeParams()); err != nil {
		return r.asProtocolError(err, "App Server initialize 握手失败")
	}

	if err := r.connection.sendNotification("initialized"); err != nil {
		return err
	}

	if err := r.authenticate(); err != nil {
		return err
	}

	if err := r.openThread(); err != nil {
		return err
	}

	turnParams := map[string]any{
		"threadId": r.threadID,
		"input": []any{
			map[string]any{
				"type": "text",
				"text": r.plan.prompt,
			},
		},
	}

	if r.plan.model != "" {
		turnParams["model"] = r.plan.model
	}

	rawTurn, err := r.call("turn/start", turnParams)
	if err != nil {
		return err
	}

	var started turnResult
	if json.Unmarshal(rawTurn, &started) == nil && started.Turn.ID != "" && r.turnID == "" {
		r.turnID = started.Turn.ID
	}

	return r.waitTurnCompleted()
}

// asProtocolError 握手阶段的失败统一为 CLI_PROTOCOL_ERROR，取消与超时保持原分类
func (r *runExecution) asProtocolError(err error, message string) error {
	if r.stopReason != stopReasonNone {
		return err
	}

	return core.WrapError(core.CategoryCLIProtocolError, message, core.AgentCodex, err)
}

// authenticate api_key 模式在独立 CODEX_HOME 中登录，复用本机登录时用 account/read 校验
func (r *runExecution) authenticate() error {
	switch r.client.config.AuthMode {
	case config.AuthModeAPIKey:
		_, err := r.call("account/login/start", map[string]any{
			"type":   "apiKey",
			"apiKey": r.client.config.APIKey,
		})
		if err != nil {
			return r.asAuthError(err, "使用 API key 登录 Codex 失败")
		}

		return nil
	case config.AuthModeCLIExistingSession:
		rawAccount, err := r.call("account/read", map[string]any{})
		if err != nil {
			return r.asAuthError(err, "读取 Codex 登录状态失败")
		}

		var account accountReadResult
		if json.Unmarshal(rawAccount, &account) != nil {
			return nil
		}

		hasAccount := len(account.Account) > 0 && string(account.Account) != "null"
		if !hasAccount && account.RequiresOpenaiAuth {
			return core.NewError(core.CategoryAuthMissing, "Codex CLI 未登录，请先执行 codex login 或改用 auth_mode=api_key", core.AgentCodex)
		}

		return nil
	default:
		return nil
	}
}

// asAuthError 认证请求被拒绝时报 AUTH_FAILED，取消与超时保持原分类
func (r *runExecution) asAuthError(err error, message string) error {
	if r.stopReason != stopReasonNone {
		return err
	}

	return core.WrapError(core.CategoryAuthFailed, message, core.AgentCodex, err)
}

// openThread 新建或恢复 thread，记录 sessionId、模型与工作目录
func (r *runExecution) openThread() error {
	params := map[string]any{}
	for key, value := range r.plan.threadOptions {
		params[key] = value
	}

	params["cwd"] = r.plan.workingDirectory
	params["sandbox"] = r.plan.sandboxMode

	if _, exists := params["approvalPolicy"]; !exists {
		params["approvalPolicy"] = "on-request"
	}

	if _, exists := params["approvalsReviewer"]; !exists {
		params["approvalsReviewer"] = "user"
	}

	if r.plan.model != "" {
		params["model"] = r.plan.model
	}

	method := "thread/start"
	if r.plan.resumeThreadID != "" {
		method = "thread/resume"
		params["threadId"] = r.plan.resumeThreadID
		params["excludeTurns"] = true
	}

	rawThread, err := r.call(method, params)
	if err != nil {
		return err
	}

	var opened threadResult
	if err := json.Unmarshal(rawThread, &opened); err != nil || opened.Thread.ID == "" {
		return core.NewError(core.CategoryCLIProtocolError, method+" 响应缺少 thread.id", core.AgentCodex)
	}

	r.threadID = opened.Thread.ID
	r.threadModel = opened.Model
	r.threadCwd = core.FirstNonEmpty(opened.Cwd, r.plan.workingDirectory)
	r.builder.SetSessionID(r.threadID)

	return nil
}

// call 发送请求并等待对应响应，等待期间按顺序处理通知与服务端请求
func (r *runExecution) call(method string, params any) (json.RawMessage, error) {
	requestID, err := r.connection.sendRequest(method, params)
	if err != nil {
		return nil, err
	}

	for {
		message, readErr := r.nextMessage()
		if readErr != nil {
			return nil, readErr
		}

		if message == nil {
			continue
		}

		if message.isResponse() && matchesRequestID(message.ID, requestID) {
			if message.Error != nil {
				return nil, core.NewError(core.CategoryProviderError, method+" 返回错误："+message.Error.Message, core.AgentCodex).WithVendorCode(fmt.Sprint(message.Error.Code))
			}

			return message.Result, nil
		}

		r.handleMessage(message)
	}
}

// waitTurnCompleted 处理通知直到本 turn 的 turn/completed
func (r *runExecution) waitTurnCompleted() error {
	for !r.hasTurnCompleted {
		message, err := r.nextMessage()
		if err != nil {
			return err
		}

		if message != nil {
			r.handleMessage(message)
		}
	}

	return nil
}

// nextMessage 读取下一条消息，同时响应中断、超时与结束等待超时，非法 JSON 行返回 nil
func (r *runExecution) nextMessage() (*rpcMessage, error) {
	interrupted := r.interrupted
	done := r.ctx.Done()

	if r.stopReason != stopReasonNone {
		interrupted = nil
		done = nil
	}

	select {
	case lineResult, isOpen := <-r.process.Lines():
		if !isOpen {
			return nil, errProcessClosed
		}

		if lineResult.Err != nil {
			r.requestStop(stopReasonFatal, lineResult.Err)
			r.process.Terminate()
			return nil, lineResult.Err
		}

		message, err := parseRPCLine(lineResult.Line)
		if err != nil {
			r.queue.Push(agentruntime.RawVendorEvent(r.builder, "invalid_json", map[string]any{
				"line":  core.TruncateRunes(string(lineResult.Line), 2000),
				"error": err.Error(),
			}))
			return nil, nil
		}

		return message, nil
	case <-interrupted:
		r.requestStop(stopReasonCancelled, nil)
		return nil, nil
	case <-done:
		reason := stopReasonCancelled
		if r.ctx.Err() == context.DeadlineExceeded {
			reason = stopReasonTimeout
		}

		r.requestStop(reason, nil)
		return nil, nil
	case <-r.stopDeadline:
		r.stopDeadline = nil
		r.process.Terminate()
		return nil, nil
	}
}

// requestStop 记录结束原因，turn 已开始时先发送 turn/interrupt，等待 kill_grace 后结束进程组
func (r *runExecution) requestStop(reason string, fatalErr *core.Error) {
	if r.stopReason != stopReasonNone {
		return
	}

	r.stopReason = reason
	r.fatalErr = fatalErr

	if reason == stopReasonFatal {
		return
	}

	if r.turnID != "" && r.threadID != "" {
		_, err := r.connection.sendRequest("turn/interrupt", map[string]any{
			"threadId": r.threadID,
			"turnId":   r.turnID,
		})

		if err == nil {
			r.isInterruptPending = true
			r.stopDeadline = time.After(agentruntime.SecondsToDuration(r.client.config.KillGraceSeconds))
			return
		}
	}

	r.process.Terminate()
}

// shutdown 关闭标准输入让 App Server 退出，超过 kill_grace 仍未退出则结束进程组
func (r *runExecution) shutdown() {
	r.process.CloseStdin()

	if _, hasExited := r.process.WaitTimeout(agentruntime.SecondsToDuration(r.client.config.KillGraceSeconds)); !hasExited {
		r.process.Terminate()
	}

	agentruntime.DrainLines(r.process.Lines())
}

// buildTerminalEvent 按结束原因、turn/completed 与进程状态组装终止事件
func (r *runExecution) buildTerminalEvent(flowErr error, exitCode int) core.AgentEvent {
	exitCodePtr := core.IntPtr(exitCode)
	usage := r.buildUsage()

	switch r.stopReason {
	case stopReasonCancelled:
		return agentruntime.TerminalCancelled(r.builder, r.finalText, usage, exitCodePtr, core.AgentCodex)
	case stopReasonTimeout:
		timeoutErr := core.NewError(core.CategoryTimeout, "run 超过 timeout_seconds 上限", core.AgentCodex)
		return agentruntime.TerminalFailed(r.builder, timeoutErr, r.finalText, usage, exitCodePtr)
	case stopReasonFatal:
		return agentruntime.TerminalFailed(r.builder, r.fatalErr, r.finalText, usage, exitCodePtr)
	}

	if r.hasTurnCompleted {
		switch r.turnStatus {
		case "completed":
			return agentruntime.TerminalCompleted(r.builder, r.finalText, usage, exitCodePtr)
		case "interrupted":
			return agentruntime.TerminalCancelled(r.builder, r.finalText, usage, exitCodePtr, core.AgentCodex)
		default:
			return agentruntime.TerminalFailed(r.builder, buildTurnError(r.turnError), r.finalText, usage, exitCodePtr)
		}
	}

	if flowErr != nil && flowErr != errProcessClosed {
		return agentruntime.TerminalFailed(r.builder, core.AsError(flowErr, core.AgentCodex), r.finalText, usage, exitCodePtr)
	}

	stderrTail := core.SanitizeTextWithSecrets(r.process.StderrTail(), r.plan.secrets)

	if exitCode != 0 {
		processErr := core.NewError(core.CategoryCLIProcessFailed, fmt.Sprintf("App Server 进程以退出码 %d 结束且 turn 未完成：%s", exitCode, stderrTail), core.AgentCodex).WithStatusCode(exitCode)
		return agentruntime.TerminalFailed(r.builder, processErr, r.finalText, usage, exitCodePtr)
	}

	protocolErr := core.NewError(core.CategoryCLIProtocolError, "App Server 进程结束但没有收到 turn/completed", core.AgentCodex)
	return agentruntime.TerminalFailed(r.builder, protocolErr, r.finalText, usage, exitCodePtr)
}

// handleMessage 分发通知与服务端请求，对客户端其他请求的响应忽略
func (r *runExecution) handleMessage(message *rpcMessage) {
	if message.isServerRequest() {
		r.handleServerRequest(message)
		return
	}

	if message.isResponse() || message.Method == "" {
		return
	}

	r.handleNotification(message)
}

// handleNotification 把通知映射为标准事件，turn 开始前只记录用量基线与 turn/started
func (r *runExecution) handleNotification(message *rpcMessage) {
	switch message.Method {
	case "thread/tokenUsage/updated":
		r.handleTokenUsage(message.Params)
		return
	case "turn/started":
		r.handleTurnStarted(message.Params)
		return
	}

	if !r.hasTurnStarted() {
		return
	}

	switch message.Method {
	case "item/agentMessage/delta":
		var delta deltaNotification
		if json.Unmarshal(message.Params, &delta) != nil || delta.Delta == "" {
			return
		}

		event := r.builder.NewEvent(core.AgentEventMessage)
		event.Role = roleAssistant
		event.Text = delta.Delta
		event.IsDelta = true
		r.queue.Push(event)
	case "item/started":
		r.handleItemStarted(message)
	case "item/completed":
		r.handleItemCompleted(message)
	case "turn/completed":
		r.handleTurnCompleted(message.Params)
	default:
		r.queue.Push(agentruntime.RawVendorEvent(r.builder, message.Method, decodeObject(message.Params)))
	}
}

// hasTurnStarted 判断本 run 的 turn 是否已开始，开始前的其他通知不发出
func (r *runExecution) hasTurnStarted() bool {
	return r.turnID != ""
}

// handleTokenUsage 本 turn 的累计值记为当前值，其他 turn 的累计值记为基线
func (r *runExecution) handleTokenUsage(params json.RawMessage) {
	var notification tokenUsageNotification
	if json.Unmarshal(params, &notification) != nil || notification.TokenUsage.Total == nil {
		return
	}

	if r.turnID != "" && notification.TurnID == r.turnID {
		r.turnTotal = notification.TokenUsage.Total
		return
	}

	r.baselineTotal = notification.TokenUsage.Total
}

// handleTurnStarted 发出 started 事件，模型与工作目录取 thread 响应中的值
func (r *runExecution) handleTurnStarted(params json.RawMessage) {
	var notification turnNotification
	if json.Unmarshal(params, &notification) != nil {
		return
	}

	if r.turnID != "" && notification.Turn.ID != r.turnID {
		return
	}

	r.turnID = notification.Turn.ID
	r.finalText = ""

	event := r.builder.NewEvent(core.AgentEventStarted)
	event.Model = r.threadModel
	event.WorkingDirectory = r.threadCwd
	r.queue.Push(event)
}

// handleItemStarted 工具类条目映射为 tool_started，userMessage 不发出
func (r *runExecution) handleItemStarted(message *rpcMessage) {
	var notification itemNotification
	if json.Unmarshal(message.Params, &notification) != nil {
		return
	}

	item := notification.Item
	if isToolItem(item.Type) {
		toolName, toolInput := buildToolInput(item)
		event := r.builder.NewEvent(core.AgentEventToolStarted)
		event.ToolCallID = item.ID
		event.ToolName = core.StringPtr(toolName)
		event.Input = toolInput
		r.queue.Push(event)
		return
	}

	if item.Type == itemTypeUserMessage {
		return
	}

	r.queue.Push(agentruntime.RawVendorEvent(r.builder, message.Method, decodeObject(message.Params)))
}

// handleItemCompleted 映射 agentMessage、工具类与 fileChange 条目
func (r *runExecution) handleItemCompleted(message *rpcMessage) {
	var notification itemNotification
	if json.Unmarshal(message.Params, &notification) != nil {
		return
	}

	item := notification.Item

	switch {
	case item.Type == "agentMessage":
		text := ""
		if item.Text != nil {
			text = *item.Text
		}

		r.finalText = text
		event := r.builder.NewEvent(core.AgentEventMessage)
		event.Role = roleAssistant
		event.Text = text
		event.IsDelta = false
		r.queue.Push(event)
	case isToolItem(item.Type):
		toolName, _ := buildToolInput(item)
		isError := item.Status == "failed" || item.Status == "declined"

		if item.ExitCode != nil && *item.ExitCode != 0 {
			isError = true
		}

		event := r.builder.NewEvent(core.AgentEventToolCompleted)
		event.ToolCallID = item.ID
		event.ToolName = core.StringPtr(toolName)
		event.Output = core.TruncateRunesPtr(item.AggregatedOutput, r.client.config.MaxToolOutputChars)
		event.IsError = isError
		r.queue.Push(event)
	case item.Type == "fileChange":
		changes := []core.FileChange{}

		for _, change := range item.Changes {
			changes = append(changes, core.FileChange{
				Path: change.Path,
				Kind: parseFileChangeKind(change.Kind),
			})
		}

		event := r.builder.NewEvent(core.AgentEventFileChanged)
		event.Changes = changes
		r.queue.Push(event)
	case item.Type == itemTypeUserMessage:
		return
	default:
		r.queue.Push(agentruntime.RawVendorEvent(r.builder, message.Method, decodeObject(message.Params)))
	}
}

// handleTurnCompleted 有用量时先发 usage 并记录 turn 状态，终止事件在进程退出后发出
func (r *runExecution) handleTurnCompleted(params json.RawMessage) {
	var notification turnNotification
	if json.Unmarshal(params, &notification) != nil {
		return
	}

	if notification.Turn.ID != r.turnID {
		return
	}

	usage := r.buildUsage()
	if usage != nil {
		event := r.builder.NewEvent(core.AgentEventUsage)
		event.Usage = usage
		r.queue.Push(event)
	}

	r.hasTurnCompleted = true
	r.turnStatus = notification.Turn.Status
	r.turnError = notification.Turn.Error
}

// buildUsage 用本 turn 最后一次累计值减去开始前的累计值，没有本 turn 用量时返回 nil
func (r *runExecution) buildUsage() *core.AgentUsage {
	if r.turnTotal == nil {
		return nil
	}

	baseline := r.baselineTotal
	if baseline == nil {
		baseline = &tokenCounts{}
	}

	return &core.AgentUsage{
		InputTokens:       subtractCount(r.turnTotal.InputTokens, baseline.InputTokens),
		OutputTokens:      subtractCount(r.turnTotal.OutputTokens, baseline.OutputTokens),
		CachedInputTokens: subtractCount(r.turnTotal.CachedInputTokens, baseline.CachedInputTokens),
		ReasoningTokens:   subtractCount(r.turnTotal.ReasoningOutputTokens, baseline.ReasoningOutputTokens),
		TotalTokens:       subtractCount(r.turnTotal.TotalTokens, baseline.TotalTokens),
	}
}

// handleServerRequest 处理审批请求，未提供审批处理器时一律 decline，其他请求回复方法不支持
func (r *runExecution) handleServerRequest(message *rpcMessage) {
	if message.Method != "item/commandExecution/requestApproval" && message.Method != requestFileChangeApproval {
		_ = r.connection.sendErrorResult(message.ID, "common-llm 不支持该服务端请求："+message.Method)
		r.queue.Push(agentruntime.RawVendorEvent(r.builder, message.Method, decodeObject(message.Params)))
		return
	}

	var params approvalParams
	_ = json.Unmarshal(message.Params, &params)

	kind := core.ApprovalKindCommand
	summary := ""

	if params.Command != nil {
		summary = *params.Command
	}

	if message.Method == requestFileChangeApproval {
		kind = core.ApprovalKindFileChange
		summary = "file change"

		if params.Reason != nil && *params.Reason != "" {
			summary = *params.Reason
		}
	}

	approvalID := params.ItemID
	if params.ApprovalID != nil && *params.ApprovalID != "" {
		approvalID = *params.ApprovalID
	}

	decision := r.decideApproval(core.ApprovalRequest{
		RunID:      r.builder.RunID(),
		SessionID:  r.threadID,
		ApprovalID: approvalID,
		Kind:       kind,
		Summary:    summary,
		Payload:    decodeObject(message.Params),
	})

	event := r.builder.NewEvent(core.AgentEventApprovalRequested)
	event.ApprovalID = approvalID
	event.Kind = kind
	event.Summary = summary
	event.Decision = decision
	r.queue.Push(event)

	_ = r.connection.sendResult(message.ID, map[string]any{"decision": codexDecisions[decision]})
}

// decideApproval 调用审批处理器，未提供、出错或返回未知结果时按 decline 处理
func (r *runExecution) decideApproval(request core.ApprovalRequest) core.ApprovalDecision {
	if r.plan.approvalHandler == nil {
		return core.DecisionDecline
	}

	decision, err := r.plan.approvalHandler(r.ctx, request)
	if err != nil {
		r.client.logger.Warn("审批处理器返回错误，按 decline 处理", slog.String("agent", core.AgentCodex), slog.String("runId", request.RunID))
		return core.DecisionDecline
	}

	if _, isKnown := codexDecisions[decision]; !isKnown {
		return core.DecisionDecline
	}

	return decision
}

// isToolItem 判断条目是否映射为工具事件
func isToolItem(itemType string) bool {
	return itemType == itemTypeCommandExecution || itemType == itemTypeMcpToolCall || itemType == "webSearch"
}

// buildToolInput 返回工具名与输入参数
func buildToolInput(item codexItem) (string, map[string]any) {
	switch item.Type {
	case itemTypeCommandExecution:
		return "command_execution", map[string]any{
			"command": nullableString(item.Command),
			"cwd":     nullableString(item.Cwd),
		}
	case itemTypeMcpToolCall:
		return derefString(item.Server) + "." + derefString(item.Tool), decodeObject(item.Arguments)
	default:
		return "web_search", map[string]any{
			"query": nullableString(item.Query),
		}
	}
}

// parseFileChangeKind 解析 kind，对象形式取 type 字段
func parseFileChangeKind(raw json.RawMessage) string {
	var kindObject struct {
		Type string `json:"type"`
	}

	if json.Unmarshal(raw, &kindObject) == nil && kindObject.Type != "" {
		return kindObject.Type
	}

	var kindText string
	if json.Unmarshal(raw, &kindText) == nil && kindText != "" {
		return kindText
	}

	return "update"
}

// buildTurnError 把失败的 turn 映射为 PROVIDER_ERROR，vendorCode 为 codexErrorInfo 的 JSON
func buildTurnError(raw json.RawMessage) *core.Error {
	var turnError struct {
		Message        string `json:"message"`
		CodexErrorInfo any    `json:"codexErrorInfo"`
	}

	message := "Codex turn 执行失败"
	vendorCode := ""

	if json.Unmarshal(raw, &turnError) == nil {
		message = core.FirstNonEmpty(turnError.Message, message)

		if turnError.CodexErrorInfo != nil {
			encoded, err := core.CompactJSON(turnError.CodexErrorInfo)
			if err == nil {
				vendorCode = encoded
			}
		}
	}

	return core.NewError(core.CategoryProviderError, message, core.AgentCodex).WithVendorCode(vendorCode)
}

// subtractCount 当前累计值减去基线，基线为 nil 时按 0 计
func subtractCount(current *int64, baseline *int64) *int64 {
	if current == nil {
		return nil
	}

	if baseline == nil {
		return core.Int64Ptr(*current)
	}

	return core.Int64Ptr(*current - *baseline)
}

// decodeObject 解析 JSON 对象，失败时返回 nil
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

// nullableString 把可空字符串转换为 JSON 值，nil 输出 null
func nullableString(value *string) any {
	if value == nil {
		return nil
	}

	return *value
}

// derefString 读取可空字符串，nil 返回空字符串
func derefString(value *string) string {
	if value == nil {
		return ""
	}

	return *value
}
