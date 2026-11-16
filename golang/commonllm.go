// CommonLlm 门面：按配置装配模型与 Agent 客户端，并按 provider 与 agent 名称路由调用
// 创建日期：2026-11-16
// 修改日期：2026-11-16

package commonllm

import (
	"context"
	"errors"
	"iter"
	"log/slog"
	"os"
	"slices"
	"sync"

	"github.com/CharlesHYF/common-llm/golang/agents/claudecode"
	"github.com/CharlesHYF/common-llm/golang/agents/codex"
	"github.com/CharlesHYF/common-llm/golang/config"
	"github.com/CharlesHYF/common-llm/golang/core"
	"github.com/CharlesHYF/common-llm/golang/observability"
	"github.com/CharlesHYF/common-llm/golang/providers/anthropic"
	"github.com/CharlesHYF/common-llm/golang/providers/gemini"
	"github.com/CharlesHYF/common-llm/golang/providers/openai"
)

// Version 本库版本号。
const Version = core.Version

// Error 本库返回的错误类型，可用 errors.As 提取。
type Error = core.Error

// Options 创建 CommonLlm 的参数，全部可选。
type Options struct {
	// ConfigPath 配置文件路径，优先于环境变量 COMMON_LLM_CONFIG 与 ./common-llm.yaml
	ConfigPath string
	// ConfigProvider 注入的配置来源，例如数据库配置
	ConfigProvider config.ConfigProvider
	// Overrides 运行时显式覆盖，优先级最高
	Overrides config.Document
	// Logger 为空时按配置 log_level 输出到标准错误
	Logger *slog.Logger
	// IDGenerator 生成 runId 与 traceId，测试可注入固定值
	IDGenerator core.IDGenerator
	// LookupEnv 读取环境变量，为空时使用 os.LookupEnv
	LookupEnv config.LookupEnvFunc
}

// CommonLlm 统一调用入口，可并发使用。
type CommonLlm struct {
	options Options
	mutex   sync.RWMutex
	current *clientSet
}

// clientSet 一份配置对应的全部客户端
type clientSet struct {
	config   *config.CommonLlmConfig
	logger   *slog.Logger
	models   map[string]core.ModelClient
	agents   map[string]core.AgentClient
	inFlight sync.WaitGroup
}

// Create 加载全部配置来源并装配客户端，配置非法时返回 *Error。
func Create(ctx context.Context, options Options) (*CommonLlm, error) {
	if options.IDGenerator == nil {
		options.IDGenerator = core.UUIDGenerator{}
	}

	instance := &CommonLlm{options: options}
	clients, err := instance.buildClients(ctx)

	if err != nil {
		return nil, err
	}

	instance.current = clients
	return instance, nil
}

// buildClients 解析配置并为已启用的 Provider 与 Agent 创建客户端
func (c *CommonLlm) buildClients(ctx context.Context) (*clientSet, error) {
	resolver := config.Resolver{
		FilePath:  c.options.ConfigPath,
		Provider:  c.options.ConfigProvider,
		Overrides: c.options.Overrides,
		LookupEnv: c.options.LookupEnv,
	}

	resolved, err := resolver.Resolve(ctx)
	if err != nil {
		return nil, err
	}

	logger := c.options.Logger
	if logger == nil {
		logger = observability.NewLogger(os.Stderr, resolved.Common.LogLevel)
	}

	clients := &clientSet{
		config: resolved,
		logger: logger,
		models: map[string]core.ModelClient{},
		agents: map[string]core.AgentClient{},
	}

	for name, providerConfig := range resolved.Providers {
		if !providerConfig.Enabled {
			continue
		}

		switch providerConfig.Type {
		case config.ProviderTypeOpenAI:
			clients.models[name] = openai.New(openai.Options{Config: providerConfig, Logger: logger, IDGenerator: c.options.IDGenerator})
		case config.ProviderTypeAnthropic:
			clients.models[name] = anthropic.New(anthropic.Options{Config: providerConfig, Logger: logger, IDGenerator: c.options.IDGenerator})
		case config.ProviderTypeGemini:
			clients.models[name] = gemini.New(gemini.Options{Config: providerConfig, Logger: logger, IDGenerator: c.options.IDGenerator})
		}
	}

	for name, agentConfig := range resolved.Agents {
		if !agentConfig.Enabled {
			continue
		}

		if name == core.AgentClaudeCode {
			clients.agents[name] = claudecode.New(claudecode.Options{Config: agentConfig, Logger: logger, IDGenerator: c.options.IDGenerator})
			continue
		}

		clients.agents[name] = codex.New(codex.Options{Config: agentConfig, Logger: logger, IDGenerator: c.options.IDGenerator})
	}

	return clients, nil
}

// acquire 取得当前客户端集合并登记一次进行中的调用
func (c *CommonLlm) acquire() *clientSet {
	c.mutex.RLock()
	defer c.mutex.RUnlock()

	c.current.inFlight.Add(1)
	return c.current
}

// Generate 按 request.provider 路由到对应 ModelClient 发起非流式调用。
func (c *CommonLlm) Generate(ctx context.Context, request *core.ModelRequest) (*core.ModelResponse, error) {
	clients := c.acquire()
	defer clients.inFlight.Done()

	if request == nil {
		return nil, core.NewError(core.CategoryConfigInvalid, "ModelRequest 不能为空", "")
	}

	client, err := clients.model(request.Provider)
	if err != nil {
		return nil, err
	}

	return client.Generate(ctx, request)
}

// Stream 按 request.provider 路由到对应 ModelClient 发起流式调用，失败（含 Provider 不存在）以唯一的 error 终止事件返回。
func (c *CommonLlm) Stream(ctx context.Context, request *core.ModelRequest) iter.Seq[core.ModelEvent] {
	return func(yield func(core.ModelEvent) bool) {
		clients := c.acquire()
		defer clients.inFlight.Done()

		provider := ""
		if request != nil {
			provider = request.Provider
		}

		client, err := clients.model(provider)
		if err != nil {
			commonErr := core.AsError(err, provider)
			builder := core.NewModelEventBuilder(provider, core.ResolveTraceID(traceIDOf(request), c.options.IDGenerator))
			yield(builder.ErrorEvent(commonErr))
			return
		}

		for event := range client.Stream(ctx, request) {
			if !yield(event) {
				return
			}
		}
	}
}

// traceIDOf 读取请求中的 traceId
func traceIDOf(request *core.ModelRequest) string {
	if request == nil {
		return ""
	}

	return request.TraceID
}

// Model 获取 Provider 实例的 ModelClient，未启用或未配置时报 CONFIG_INVALID。
func (c *CommonLlm) Model(provider string) (core.ModelClient, error) {
	c.mutex.RLock()
	defer c.mutex.RUnlock()

	return c.current.model(provider)
}

// Agent 获取 AgentClient，未启用或未配置时报 CONFIG_INVALID。
func (c *CommonLlm) Agent(name string) (core.AgentClient, error) {
	c.mutex.RLock()
	defer c.mutex.RUnlock()

	client, exists := c.current.agents[name]
	if !exists {
		return nil, core.NewError(core.CategoryConfigInvalid, "Agent 未启用或未配置："+name, name)
	}

	return client, nil
}

// model 查找 ModelClient
func (s *clientSet) model(provider string) (core.ModelClient, error) {
	client, exists := s.models[provider]
	if !exists {
		return nil, core.NewError(core.CategoryConfigInvalid, "Provider 未启用或未配置："+provider, provider)
	}

	return client, nil
}

// ListProviders 返回已启用 Provider 的能力列表，按实例名排序。
func (c *CommonLlm) ListProviders() []core.ProviderCapability {
	c.mutex.RLock()
	defer c.mutex.RUnlock()

	names := []string{}
	for name := range c.current.models {
		names = append(names, name)
	}

	slices.Sort(names)
	capabilities := []core.ProviderCapability{}

	for _, name := range names {
		capabilities = append(capabilities, c.current.models[name].Capability())
	}

	return capabilities
}

// ListAgents 探测并返回已启用 Agent 的能力列表，任一探测失败时一并返回错误。
func (c *CommonLlm) ListAgents(ctx context.Context) ([]core.AgentCapability, error) {
	c.mutex.RLock()
	agents := c.current.agents
	c.mutex.RUnlock()

	names := []string{}
	for name := range agents {
		names = append(names, name)
	}

	slices.Sort(names)
	capabilities := []core.AgentCapability{}
	probeErrors := []error{}

	for _, name := range names {
		capability, err := agents[name].Capabilities(ctx)
		if err != nil {
			probeErrors = append(probeErrors, err)
			continue
		}

		capabilities = append(capabilities, *capability)
	}

	return capabilities, errors.Join(probeErrors...)
}

// Config 返回当前生效的配置，密钥字段在字符串与序列化输出中为 ***。
func (c *CommonLlm) Config() *config.CommonLlmConfig {
	c.mutex.RLock()
	defer c.mutex.RUnlock()

	return c.current.config
}

// Reload 重新加载全部配置来源，失败时保留旧配置并返回 CONFIG_INVALID；成功后旧模型客户端在进行中的调用结束后关闭。
func (c *CommonLlm) Reload(ctx context.Context) error {
	clients, err := c.buildClients(ctx)
	if err != nil {
		commonErr := core.AsError(err, "")
		if commonErr.Info.Category == core.CategoryConfigInvalid {
			return commonErr
		}

		return core.WrapError(core.CategoryConfigInvalid, "重新加载配置失败，继续使用旧配置："+commonErr.Info.Message, "", err)
	}

	c.mutex.Lock()
	previous := c.current
	c.current = clients
	c.mutex.Unlock()

	go func() {
		previous.inFlight.Wait()

		for _, model := range previous.models {
			_ = model.Close()
		}
	}()

	return nil
}

// Close 释放全部 SDK 客户端并结束进行中的 Agent run。
func (c *CommonLlm) Close() error {
	c.mutex.Lock()
	defer c.mutex.Unlock()

	closeErrors := []error{}

	for _, model := range c.current.models {
		closeErrors = append(closeErrors, model.Close())
	}

	for _, agent := range c.current.agents {
		closeErrors = append(closeErrors, agent.Close())
	}

	return errors.Join(closeErrors...)
}
