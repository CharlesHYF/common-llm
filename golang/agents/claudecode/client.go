// Claude Code CLI 适配器：以 claude -p stream-json 子进程执行 run 并映射为统一事件
// 创建日期：2026-11-16
// 修改日期：2026-11-16

package claudecode

import (
	"context"
	"iter"
	"log/slog"
	"regexp"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/CharlesHYF/common-llm/golang/config"
	"github.com/CharlesHYF/common-llm/golang/core"
	"github.com/CharlesHYF/common-llm/golang/internal/agentruntime"
)

// MinimumVersion 支持的最低 Claude Code 版本，与 api-contract.md 2.1 节一致
const MinimumVersion = "2.1.325"

// 固定传给 CLI 的参数
const (
	flagPrint                  = "-p"
	flagIncludePartialMessages = "--include-partial-messages"
)

// managedOptionNames 由适配器管理的 CLI 参数，nativeOptions 不得覆盖
var managedOptionNames = []string{
	"print",
	"p",
	"output-format",
	"verbose",
	"input-format",
	"model",
	"permission-mode",
	"allowedTools",
	"allowed-tools",
	"resume",
	"r",
	"continue",
	"c",
	"include-partial-messages",
}

// helpOptionPattern 从 --help 输出中提取长参数名
var helpOptionPattern = regexp.MustCompile(`(?m)^\s+(?:-[A-Za-z],\s+)?--([A-Za-z][A-Za-z0-9-]*)(?:,\s+--([A-Za-z][A-Za-z0-9-]*))?`)

// helpChoicesPattern 提取 --permission-mode 的 choices 列表
var helpChoicesPattern = regexp.MustCompile(`--permission-mode[^(]*\(choices:([^)]*)\)`)

// quotedValuePattern 提取 choices 中的带引号取值
var quotedValuePattern = regexp.MustCompile(`"([^"]+)"`)

// eventTypes Claude Code CLI 可能发出的事件类型，CLI 不提供 file_changed
var eventTypes = []string{
	string(core.AgentEventStarted),
	string(core.AgentEventMessage),
	string(core.AgentEventToolStarted),
	string(core.AgentEventToolCompleted),
	string(core.AgentEventApprovalRequested),
	string(core.AgentEventUsage),
	string(core.AgentEventCompleted),
	string(core.AgentEventFailed),
	string(core.AgentEventCancelled),
	string(core.AgentEventRawVendor),
}

// features Claude Code CLI 支持的能力，CLI 不支持运行中审批回调
var features = []string{
	core.FeatureStreaming,
	core.FeatureSessionResume,
	core.FeatureInterrupt,
	core.FeatureCost,
}

// Options 创建适配器所需参数。
type Options struct {
	Config      config.AgentConfig
	Logger      *slog.Logger
	IDGenerator core.IDGenerator
}

// Client Claude Code 适配器，实现 core.AgentClient。
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
	executablePath   string
	version          string
	supportedOptions []string
	permissionModes  []string
}

// New 创建 Claude Code 适配器，探测在首次使用时执行。
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
	return agentruntime.CollectResult(c.RunStream(ctx, request), c.config.CollectEvents, core.AgentClaudeCode)
}

// Resume 在指定会话上继续执行，等价于 --resume <sessionId>。
func (c *Client) Resume(ctx context.Context, sessionID string, request *core.AgentRequest) *core.AgentResult {
	resumed := core.AgentRequest{}
	if request != nil {
		resumed = *request
	}

	resumed.SessionID = sessionID
	return c.Run(ctx, &resumed)
}

// Interrupt 结束指定 run 的子进程，run 以 cancelled 结束。
func (c *Client) Interrupt(ctx context.Context, runID string) error {
	if !c.registry.Interrupt(runID) {
		return core.NewError(core.CategoryConfigInvalid, "run 不存在或已结束："+runID, core.AgentClaudeCode)
	}

	return nil
}

// Capabilities 探测可执行文件、版本与 -p --help 参数集合。
func (c *Client) Capabilities(ctx context.Context) (*core.AgentCapability, error) {
	probe, err := c.ensureProbe(ctx)
	if err != nil {
		return nil, err
	}

	return &core.AgentCapability{
		Agent:            core.AgentClaudeCode,
		Implementation:   core.ImplementationCLI,
		ExecutablePath:   probe.executablePath,
		Version:          probe.version,
		Features:         slices.Clone(features),
		EventTypes:       slices.Clone(eventTypes),
		SupportedOptions: slices.Clone(probe.supportedOptions),
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

	builder := core.NewAgentEventBuilder(core.AgentClaudeCode, core.ResolveTraceID(traceID, c.ids), c.ids.NewRunID())

	c.closeMutex.RLock()
	isClosed := c.isClosed
	c.closeMutex.RUnlock()

	if isClosed {
		return agentruntime.FailedBeforeStart(builder, core.NewError(core.CategoryConfigInvalid, "客户端已关闭", core.AgentClaudeCode), core.AgentClaudeCode)
	}

	plan, err := c.prepareRun(ctx, request)
	if err != nil {
		return agentruntime.FailedBeforeStart(builder, err, core.AgentClaudeCode)
	}

	if err := c.limiter.TryAcquire(core.AgentClaudeCode); err != nil {
		return agentruntime.FailedBeforeStart(builder, err, core.AgentClaudeCode)
	}

	runContext, cancel := context.WithTimeout(ctx, plan.timeout)
	execution := newRunExecution(c, plan, builder, runContext, cancel)
	c.registry.Register(builder.RunID(), execution.interrupt)

	go func() {
		defer c.limiter.Release()
		defer c.registry.Unregister(builder.RunID())
		defer cancel()

		execution.execute()
	}()

	return agentruntime.IterateEvents(execution.queue, execution.interrupt)
}

// runPlan 启动子进程所需的全部参数
type runPlan struct {
	executablePath   string
	args             []string
	workingDirectory string
	env              []string
	prompt           string
	timeout          time.Duration
	secrets          []string
}

// prepareRun 校验请求、执行探测并组装命令行参数
func (c *Client) prepareRun(ctx context.Context, request *core.AgentRequest) (*runPlan, error) {
	if err := agentruntime.ValidateAgentRequest(request, core.AgentClaudeCode); err != nil {
		return nil, err
	}

	if request.ApprovalHandler != nil {
		return nil, core.NewError(core.CategorySDKUnsupportedFeature, "Claude Code CLI 不支持运行中审批回调，请改用 permissionMode 与 allowedTools 预设权限", core.AgentClaudeCode)
	}

	if request.SandboxMode != "" {
		return nil, core.NewError(core.CategorySDKUnsupportedFeature, "Claude Code 不支持 sandboxMode，该字段只适用于 Codex", core.AgentClaudeCode)
	}

	probe, err := c.ensureProbe(ctx)
	if err != nil {
		return nil, err
	}

	workingDirectory, err := agentruntime.ResolveWorkingDirectory(core.FirstNonEmpty(request.WorkingDirectory, c.config.WorkingDirectory), core.AgentClaudeCode)
	if err != nil {
		return nil, err
	}

	args, err := c.buildArgs(request, probe)
	if err != nil {
		return nil, err
	}

	extraEnv := map[string]string{}
	secrets := []string{}

	if c.config.AuthMode == config.AuthModeAPIKey {
		extraEnv["ANTHROPIC_API_KEY"] = c.config.APIKey
		secrets = append(secrets, c.config.APIKey)
	}

	return &runPlan{
		executablePath:   probe.executablePath,
		args:             args,
		workingDirectory: workingDirectory,
		env:              agentruntime.BuildEnvironment(agentruntime.EnvAllowlists(request, c.config), extraEnv),
		prompt:           request.Prompt,
		timeout:          agentruntime.ResolveRunTimeout(request, c.config),
		secrets:          secrets,
	}, nil
}

// buildArgs 按探测到的参数集合组装命令行，不支持的参数报 SDK_UNSUPPORTED_FEATURE
func (c *Client) buildArgs(request *core.AgentRequest, probe *probeResult) ([]string, error) {
	args := []string{flagPrint, "--output-format", "stream-json", "--verbose"}

	if slices.Contains(probe.supportedOptions, strings.TrimPrefix(flagIncludePartialMessages, "--")) {
		args = append(args, flagIncludePartialMessages)
	}

	if request.Model != "" {
		args = append(args, "--model", request.Model)
	}

	permissionArgs, err := c.buildPermissionArgs(request, probe)
	if err != nil {
		return nil, err
	}

	args = append(args, permissionArgs...)

	allowedTools := request.AllowedTools
	if allowedTools == nil {
		allowedTools = c.config.AllowedTools
	}

	if len(allowedTools) > 0 {
		args = append(args, "--allowedTools", strings.Join(allowedTools, ","))
	}

	if request.SessionID != "" {
		args = append(args, "--resume", request.SessionID)
	}

	nativeArgs, err := buildNativeArgs(core.MergeProviderOptions(c.config.NativeOptions, request.NativeOptions), probe)
	if err != nil {
		return nil, err
	}

	return append(args, nativeArgs...), nil
}

// buildPermissionArgs 解析权限模式，CLI 不支持 default 时不传参
func (c *Client) buildPermissionArgs(request *core.AgentRequest, probe *probeResult) ([]string, error) {
	permissionMode := core.FirstNonEmpty(request.PermissionMode, c.config.PermissionMode)

	if permissionMode == "" {
		return nil, nil
	}

	if !slices.Contains(probe.supportedOptions, "permission-mode") {
		if permissionMode == config.DefaultPermissionMode {
			return nil, nil
		}

		return nil, core.NewError(core.CategorySDKUnsupportedFeature, "当前 Claude Code 版本不支持 --permission-mode", core.AgentClaudeCode)
	}

	if len(probe.permissionModes) > 0 && !slices.Contains(probe.permissionModes, permissionMode) {
		if permissionMode == config.DefaultPermissionMode {
			return nil, nil
		}

		return nil, core.NewError(core.CategorySDKUnsupportedFeature, "当前 Claude Code 版本不支持权限模式："+permissionMode, core.AgentClaudeCode)
	}

	if permissionMode == "bypassPermissions" {
		c.logger.Warn("Claude Code 以 bypassPermissions 模式运行，所有权限检查被跳过", slog.String("agent", core.AgentClaudeCode))
	}

	return []string{"--permission-mode", permissionMode}, nil
}

// buildNativeArgs 把 nativeOptions 转换为 CLI 参数，键名为不带 -- 的长参数名
func buildNativeArgs(nativeOptions map[string]any, probe *probeResult) ([]string, error) {
	names := make([]string, 0, len(nativeOptions))
	for name := range nativeOptions {
		names = append(names, name)
	}

	slices.Sort(names)
	args := []string{}

	for _, name := range names {
		optionName := strings.TrimLeft(name, "-")

		if slices.Contains(managedOptionNames, optionName) {
			return nil, core.NewError(core.CategoryConfigInvalid, "nativeOptions 不能覆盖适配器管理的参数："+optionName, core.AgentClaudeCode)
		}

		if !slices.Contains(probe.supportedOptions, optionName) {
			return nil, core.NewError(core.CategorySDKUnsupportedFeature, "当前 Claude Code 版本不支持参数：--"+optionName, core.AgentClaudeCode)
		}

		optionArgs, err := formatNativeOption(optionName, nativeOptions[name])
		if err != nil {
			return nil, err
		}

		args = append(args, optionArgs...)
	}

	return args, nil
}

// formatNativeOption 按值类型转换为参数：true 只传开关，false 不传，数组展开为多个值
func formatNativeOption(optionName string, value any) ([]string, error) {
	flag := "--" + optionName

	switch typed := value.(type) {
	case bool:
		if typed {
			return []string{flag}, nil
		}

		return nil, nil
	case string:
		return []string{flag, typed}, nil
	case []string:
		return append([]string{flag}, typed...), nil
	case []any:
		args := []string{flag}

		for _, element := range typed {
			args = append(args, formatScalar(element))
		}

		return args, nil
	case nil:
		return nil, nil
	default:
		return []string{flag, formatScalar(typed)}, nil
	}
}

// ensureProbe 首次使用时探测可执行文件、版本与参数集合
func (c *Client) ensureProbe(ctx context.Context) (*probeResult, error) {
	c.probeMutex.Lock()
	defer c.probeMutex.Unlock()

	if c.probe != nil {
		return c.probe, nil
	}

	executablePath, err := agentruntime.ResolveExecutable(c.config.ExecutablePath, core.AgentClaudeCode)
	if err != nil {
		return nil, err
	}

	probeEnv := agentruntime.BuildEnvironment([][]string{c.config.EnvAllowlist}, nil)
	versionOutput, _, err := agentruntime.RunProbe(ctx, executablePath, []string{"--version"}, probeEnv, core.AgentClaudeCode)
	if err != nil {
		return nil, err
	}

	version := agentruntime.ParseVersion(versionOutput)
	if version == "" {
		return nil, core.NewError(core.CategoryCLIVersionUnsupported, "无法从 --version 输出解析版本号", core.AgentClaudeCode)
	}

	if agentruntime.CompareVersions(version, MinimumVersion) < 0 {
		return nil, core.NewError(core.CategoryCLIVersionUnsupported, "Claude Code 版本 "+version+" 低于最低支持版本 "+MinimumVersion, core.AgentClaudeCode)
	}

	helpOutput, _, err := agentruntime.RunProbe(ctx, executablePath, []string{flagPrint, "--help"}, probeEnv, core.AgentClaudeCode)
	if err != nil {
		return nil, err
	}

	c.probe = &probeResult{
		executablePath:   executablePath,
		version:          version,
		supportedOptions: parseHelpOptions(helpOutput),
		permissionModes:  parsePermissionModes(helpOutput),
	}

	return c.probe, nil
}

// parseHelpOptions 解析 --help 输出中的长参数名，结果去重并排序
func parseHelpOptions(helpOutput string) []string {
	options := []string{}

	for _, matched := range helpOptionPattern.FindAllStringSubmatch(helpOutput, -1) {
		for _, name := range matched[1:] {
			if name == "" || slices.Contains(options, name) {
				continue
			}

			options = append(options, name)
		}
	}

	slices.Sort(options)
	return options
}

// parsePermissionModes 解析 --permission-mode 的可选值，换行与缩进先合并
func parsePermissionModes(helpOutput string) []string {
	flattened := strings.Join(strings.Fields(helpOutput), " ")
	matched := helpChoicesPattern.FindStringSubmatch(flattened)

	if len(matched) < 2 {
		return nil
	}

	modes := []string{}
	for _, quoted := range quotedValuePattern.FindAllStringSubmatch(matched[1], -1) {
		modes = append(modes, quoted[1])
	}

	return modes
}
