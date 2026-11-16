// Codex App Server 适配器：通过 codex app-server 的 stdio JSON-RPC 执行 run
// 创建日期：2026-11-16
// 修改日期：2026-11-16

package codex

import (
	"context"
	"iter"
	"log/slog"
	"os"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/CharlesHYF/common-llm/golang/config"
	"github.com/CharlesHYF/common-llm/golang/core"
	"github.com/CharlesHYF/common-llm/golang/internal/agentruntime"
)

// MinimumVersion 支持的最低 Codex 版本，App Server 协议按 0.169.0 的 schema 实现
const MinimumVersion = "0.169.0"

// 启动参数与认证相关常量
const (
	commandAppServer = "app-server"
	flagListen       = "--listen"
	listenStdio      = "stdio://"
)

// eventTypes Codex App Server 可能发出的事件类型
var eventTypes = []string{
	string(core.AgentEventStarted),
	string(core.AgentEventMessage),
	string(core.AgentEventToolStarted),
	string(core.AgentEventToolCompleted),
	string(core.AgentEventFileChanged),
	string(core.AgentEventApprovalRequested),
	string(core.AgentEventUsage),
	string(core.AgentEventCompleted),
	string(core.AgentEventFailed),
	string(core.AgentEventCancelled),
	string(core.AgentEventRawVendor),
}

// features Codex App Server 支持的能力，协议不返回成本
var features = []string{
	core.FeatureStreaming,
	core.FeatureSessionResume,
	core.FeatureApprovals,
	core.FeatureInterrupt,
}

// Options 创建适配器所需参数。
type Options struct {
	Config      config.AgentConfig
	Logger      *slog.Logger
	IDGenerator core.IDGenerator
}

// Client Codex 适配器，实现 core.AgentClient。
type Client struct {
	config     config.AgentConfig
	logger     *slog.Logger
	ids        core.IDGenerator
	limiter    *agentruntime.RunLimiter
	registry   *agentruntime.RunRegistry
	probeMutex sync.Mutex
	probe      *probeResult
	closeMutex sync.RWMutex
	isClosed   bool
}

// probeResult 运行时探测结果，缓存到 Close 为止
type probeResult struct {
	executablePath string
	version        string
}

// runPlan 启动 App Server 与发起 turn 所需的全部参数
type runPlan struct {
	executablePath   string
	workingDirectory string
	env              []string
	prompt           string
	model            string
	resumeThreadID   string
	sandboxMode      string
	threadOptions    map[string]any
	approvalHandler  core.ApprovalHandler
	timeout          time.Duration
	secrets          []string
}

// New 创建 Codex 适配器，探测在首次使用时执行。
func New(options Options) *Client {
	logger := options.Logger
	if logger == nil {
		logger = slog.Default()
	}

	ids := options.IDGenerator
	if ids == nil {
		ids = core.UUIDGenerator{}
	}

	return &Client{
		config:   options.Config,
		logger:   logger,
		ids:      ids,
		limiter:  agentruntime.NewRunLimiter(options.Config.MaxConcurrentRuns),
		registry: agentruntime.NewRunRegistry(),
	}
}

// Run 执行一次 run，失败（含预检失败）以 status=failed 且 error 非空的结果返回。
func (c *Client) Run(ctx context.Context, request *core.AgentRequest) *core.AgentResult {
	return agentruntime.CollectResult(c.RunStream(ctx, request), c.config.CollectEvents, core.AgentCodex)
}

// Resume 在指定 thread 上继续执行，等价于 thread/resume。
func (c *Client) Resume(ctx context.Context, sessionID string, request *core.AgentRequest) *core.AgentResult {
	resumed := core.AgentRequest{}
	if request != nil {
		resumed = *request
	}

	resumed.SessionID = sessionID
	return c.Run(ctx, &resumed)
}

// Interrupt 向指定 run 发送 turn/interrupt，run 以 cancelled 结束。
func (c *Client) Interrupt(ctx context.Context, runID string) error {
	if !c.registry.Interrupt(runID) {
		return core.NewError(core.CategoryConfigInvalid, "run 不存在或已结束："+runID, core.AgentCodex)
	}

	return nil
}

// Capabilities 探测可执行文件、版本并完成一次 initialize 握手。
func (c *Client) Capabilities(ctx context.Context) (*core.AgentCapability, error) {
	probe, err := c.ensureProbe(ctx, true)
	if err != nil {
		return nil, err
	}

	return &core.AgentCapability{
		Agent:          core.AgentCodex,
		Implementation: core.ImplementationAppServer,
		ExecutablePath: probe.executablePath,
		Version:        probe.version,
		Features:       slices.Clone(features),
		EventTypes:     slices.Clone(eventTypes),
	}, nil
}

// Close 结束全部进行中的 run 并清除探测缓存。
func (c *Client) Close() error {
	c.closeMutex.Lock()
	c.isClosed = true
	c.closeMutex.Unlock()

	c.registry.InterruptAll()

	c.probeMutex.Lock()
	c.probe = nil
	c.probeMutex.Unlock()

	return nil
}

// RunStream 执行一次 run 并逐个返回事件。
func (c *Client) RunStream(ctx context.Context, request *core.AgentRequest) iter.Seq[core.AgentEvent] {
	traceID := ""
	if request != nil {
		traceID = request.TraceID
	}

	builder := core.NewAgentEventBuilder(core.AgentCodex, core.ResolveTraceID(traceID, c.ids), c.ids.NewRunID())

	c.closeMutex.RLock()
	isClosed := c.isClosed
	c.closeMutex.RUnlock()

	if isClosed {
		return agentruntime.FailedBeforeStart(builder, core.NewError(core.CategoryConfigInvalid, "客户端已关闭", core.AgentCodex), core.AgentCodex)
	}

	plan, err := c.prepareRun(ctx, request)
	if err != nil {
		return agentruntime.FailedBeforeStart(builder, err, core.AgentCodex)
	}

	if err := c.limiter.TryAcquire(core.AgentCodex); err != nil {
		return agentruntime.FailedBeforeStart(builder, err, core.AgentCodex)
	}

	runContext, cancel := context.WithTimeout(ctx, plan.timeout)
	execution := newRunExecution(c, plan, builder, runContext)
	c.registry.Register(builder.RunID(), execution.interrupt)

	go func() {
		defer c.limiter.Release()
		defer c.registry.Unregister(builder.RunID())
		defer cancel()

		execution.execute()
	}()

	return agentruntime.IterateEvents(execution.queue, execution.interrupt)
}

// prepareRun 校验请求、执行探测并组装 thread 参数
func (c *Client) prepareRun(ctx context.Context, request *core.AgentRequest) (*runPlan, error) {
	if err := agentruntime.ValidateAgentRequest(request, core.AgentCodex); err != nil {
		return nil, err
	}

	if request.PermissionMode != "" || len(request.AllowedTools) > 0 {
		return nil, core.NewError(core.CategorySDKUnsupportedFeature, "Codex 不支持 permissionMode 与 allowedTools，请使用 sandboxMode 与审批处理器", core.AgentCodex)
	}

	sandboxMode := core.FirstNonEmpty(request.SandboxMode, c.config.SandboxMode)
	if !slices.Contains([]string{config.SandboxReadOnly, config.SandboxWorkspaceWrite, config.SandboxDangerFullAccess}, sandboxMode) {
		return nil, core.NewError(core.CategoryConfigInvalid, "sandboxMode 取值非法："+sandboxMode, core.AgentCodex)
	}

	if sandboxMode == config.SandboxDangerFullAccess {
		c.logger.Warn("Codex 以 danger-full-access 沙箱模式运行，不限制文件与网络访问", slog.String("agent", core.AgentCodex))
	}

	threadOptions := core.MergeProviderOptions(c.config.NativeOptions, request.NativeOptions)
	conflicts := core.FindConflictingKeys(threadOptions, []string{"cwd", "model", "sandbox", "threadId", "excludeTurns", "input"})

	if len(conflicts) > 0 {
		return nil, core.NewError(core.CategoryConfigInvalid, "nativeOptions 与已映射字段重名："+strings.Join(conflicts, ", "), core.AgentCodex)
	}

	probe, err := c.ensureProbe(ctx, false)
	if err != nil {
		return nil, err
	}

	workingDirectory, err := agentruntime.ResolveWorkingDirectory(core.FirstNonEmpty(request.WorkingDirectory, c.config.WorkingDirectory), core.AgentCodex)
	if err != nil {
		return nil, err
	}

	extraEnv, secrets, err := c.buildAuthEnvironment()
	if err != nil {
		return nil, err
	}

	return &runPlan{
		executablePath:   probe.executablePath,
		workingDirectory: workingDirectory,
		env:              agentruntime.BuildEnvironment(agentruntime.EnvAllowlists(request, c.config), extraEnv),
		prompt:           request.Prompt,
		model:            request.Model,
		resumeThreadID:   request.SessionID,
		sandboxMode:      sandboxMode,
		threadOptions:    threadOptions,
		approvalHandler:  request.ApprovalHandler,
		timeout:          agentruntime.ResolveRunTimeout(request, c.config),
		secrets:          secrets,
	}, nil
}

// buildAuthEnvironment api_key 模式使用独立的 CODEX_HOME，不改写用户 ~/.codex 下的登录状态
func (c *Client) buildAuthEnvironment() (map[string]string, []string, error) {
	extraEnv := map[string]string{}
	secrets := []string{}

	if c.config.HomeDirectory != "" {
		if err := os.MkdirAll(c.config.HomeDirectory, 0o700); err != nil {
			return nil, nil, core.WrapError(core.CategoryConfigInvalid, "无法创建 home_directory："+c.config.HomeDirectory, core.AgentCodex, err)
		}

		extraEnv["CODEX_HOME"] = c.config.HomeDirectory
	}

	if c.config.AuthMode == config.AuthModeAPIKey {
		secrets = append(secrets, c.config.APIKey)
	}

	return extraEnv, secrets, nil
}

// ensureProbe 首次使用时探测可执行文件与版本，withHandshake 为 true 时额外完成 initialize 握手
func (c *Client) ensureProbe(ctx context.Context, withHandshake bool) (*probeResult, error) {
	c.probeMutex.Lock()
	defer c.probeMutex.Unlock()

	if c.probe != nil {
		return c.probe, nil
	}

	executablePath, err := agentruntime.ResolveExecutable(c.config.ExecutablePath, core.AgentCodex)
	if err != nil {
		return nil, err
	}

	probeEnv := agentruntime.BuildEnvironment([][]string{c.config.EnvAllowlist}, nil)
	versionOutput, _, err := agentruntime.RunProbe(ctx, executablePath, []string{"--version"}, probeEnv, core.AgentCodex)
	if err != nil {
		return nil, err
	}

	version := agentruntime.ParseVersion(versionOutput)
	if version == "" {
		return nil, core.NewError(core.CategoryCLIVersionUnsupported, "无法从 --version 输出解析版本号", core.AgentCodex)
	}

	if agentruntime.CompareVersions(version, MinimumVersion) < 0 {
		return nil, core.NewError(core.CategoryCLIVersionUnsupported, "Codex 版本 "+version+" 低于最低支持版本 "+MinimumVersion, core.AgentCodex)
	}

	if withHandshake {
		extraEnv, _, envErr := c.buildAuthEnvironment()
		if envErr != nil {
			return nil, envErr
		}

		handshakeEnv := agentruntime.BuildEnvironment([][]string{c.config.EnvAllowlist}, extraEnv)
		if err := probeHandshake(ctx, executablePath, handshakeEnv, c.config); err != nil {
			return nil, err
		}
	}

	c.probe = &probeResult{
		executablePath: executablePath,
		version:        version,
	}

	return c.probe, nil
}
