// 把合并后的配置文档转换为带默认值的配置结构并逐字段校验
// 创建日期：2026-11-16
// 修改日期：2026-11-16

package config

import (
	"bytes"
	"encoding/json"
	"fmt"
	"slices"
	"strings"

	"github.com/CharlesHYF/common-llm/golang/core"
)

// configDocument 配置文档的解码形态，指针字段用于区分未提供与零值
type configDocument struct {
	Common    *commonDocument              `json:"common"`
	Providers map[string]*providerDocument `json:"providers"`
	Agents    *agentsDocument              `json:"agents"`
}

// commonDocument common 节点
type commonDocument struct {
	TimeoutSeconds        *float64 `json:"timeout_seconds"`
	ConnectTimeoutSeconds *float64 `json:"connect_timeout_seconds"`
	MaxRetries            *int     `json:"max_retries"`
	LogLevel              *string  `json:"log_level"`
}

// providerDocument providers.<实例名> 节点
type providerDocument struct {
	Type           *string        `json:"type"`
	API            *string        `json:"api"`
	Enabled        *bool          `json:"enabled"`
	APIKey         *string        `json:"api_key"`
	BaseURL        *string        `json:"base_url"`
	DefaultModel   *string        `json:"default_model"`
	TimeoutSeconds *float64       `json:"timeout_seconds"`
	MaxRetries     *int           `json:"max_retries"`
	NativeOptions  map[string]any `json:"native_options"`
}

// agentsDocument agents 节点，只允许 claude_code 与 codex
type agentsDocument struct {
	ClaudeCode *agentDocument `json:"claude_code"`
	Codex      *agentDocument `json:"codex"`
}

// agentDocument agents.<名称> 节点
type agentDocument struct {
	Enabled            *bool          `json:"enabled"`
	AuthMode           *string        `json:"auth_mode"`
	APIKey             *string        `json:"api_key"`
	ExecutablePath     *string        `json:"executable_path"`
	HomeDirectory      *string        `json:"home_directory"`
	WorkingDirectory   *string        `json:"working_directory"`
	TimeoutSeconds     *float64       `json:"timeout_seconds"`
	PermissionMode     *string        `json:"permission_mode"`
	AllowedTools       []string       `json:"allowed_tools"`
	SandboxMode        *string        `json:"sandbox_mode"`
	EnvAllowlist       []string       `json:"env_allowlist"`
	MaxConcurrentRuns  *int           `json:"max_concurrent_runs"`
	MaxOutputBytes     *int64         `json:"max_output_bytes"`
	MaxLineBytes       *int64         `json:"max_line_bytes"`
	MaxStderrBytes     *int64         `json:"max_stderr_bytes"`
	MaxBufferedEvents  *int           `json:"max_buffered_events"`
	MaxToolOutputChars *int           `json:"max_tool_output_chars"`
	KillGraceSeconds   *float64       `json:"kill_grace_seconds"`
	CollectEvents      *bool          `json:"collect_events"`
	NativeOptions      map[string]any `json:"native_options"`
}

// 枚举字段允许的取值
var (
	validLogLevels     = []string{LogLevelDebug, LogLevelInfo, LogLevelWarn, LogLevelError}
	validProviderTypes = []string{ProviderTypeOpenAI, ProviderTypeAnthropic, ProviderTypeGemini}
	validOpenAIAPIs    = []string{OpenAIAPIResponses, OpenAIAPIChatCompletions}
	validAuthModes     = []string{AuthModeAPIKey, AuthModeCLIExistingSession, AuthModeVendorSupported}
	validSandboxModes  = []string{SandboxReadOnly, SandboxWorkspaceWrite, SandboxDangerFullAccess}
)

// buildConfig 解码合并后的文档，填充默认值并校验
func buildConfig(merged Document, missing missingVariables) (*CommonLlmConfig, error) {
	encoded, err := json.Marshal(merged)
	if err != nil {
		return nil, core.WrapError(core.CategoryConfigInvalid, "配置无法序列化", "", err)
	}

	decoder := json.NewDecoder(bytes.NewReader(encoded))
	decoder.DisallowUnknownFields()

	var document configDocument
	if err := decoder.Decode(&document); err != nil {
		return nil, core.WrapError(core.CategoryConfigInvalid, "配置结构或字段类型非法："+err.Error(), "", err)
	}

	common, err := buildCommon(document.Common)
	if err != nil {
		return nil, err
	}

	result := &CommonLlmConfig{
		Common:    common,
		Providers: map[string]ProviderConfig{},
		Agents:    map[string]AgentConfig{},
	}

	for name, providerNode := range document.Providers {
		provider, buildErr := buildProvider(name, providerNode, common, missing)

		if buildErr != nil {
			return nil, buildErr
		}

		result.Providers[name] = provider
	}

	if document.Agents != nil {
		if document.Agents.ClaudeCode != nil {
			agent, buildErr := buildAgent(core.AgentClaudeCode, document.Agents.ClaudeCode, missing)
			if buildErr != nil {
				return nil, buildErr
			}

			result.Agents[core.AgentClaudeCode] = agent
		}

		if document.Agents.Codex != nil {
			agent, buildErr := buildAgent(core.AgentCodex, document.Agents.Codex, missing)
			if buildErr != nil {
				return nil, buildErr
			}

			result.Agents[core.AgentCodex] = agent
		}
	}

	return result, nil
}

// buildCommon 填充 common 默认值并校验
func buildCommon(node *commonDocument) (CommonConfig, error) {
	common := CommonConfig{
		TimeoutSeconds:        DefaultTimeoutSeconds,
		ConnectTimeoutSeconds: DefaultConnectTimeoutSeconds,
		MaxRetries:            DefaultMaxRetries,
		LogLevel:              DefaultLogLevel,
	}

	if node == nil {
		return common, nil
	}

	common.TimeoutSeconds = valueOrFloat(node.TimeoutSeconds, common.TimeoutSeconds)
	common.ConnectTimeoutSeconds = valueOrFloat(node.ConnectTimeoutSeconds, common.ConnectTimeoutSeconds)
	common.MaxRetries = valueOrInt(node.MaxRetries, common.MaxRetries)
	common.LogLevel = strings.ToUpper(valueOrString(node.LogLevel, common.LogLevel))

	if common.TimeoutSeconds <= 0 {
		return common, invalidField("common.timeout_seconds 必须大于 0")
	}

	if common.ConnectTimeoutSeconds <= 0 {
		return common, invalidField("common.connect_timeout_seconds 必须大于 0")
	}

	if common.MaxRetries < 0 {
		return common, invalidField("common.max_retries 不能小于 0")
	}

	if !slices.Contains(validLogLevels, common.LogLevel) {
		return common, invalidField("common.log_level 取值非法：" + common.LogLevel)
	}

	return common, nil
}

// buildProvider 填充 Provider 默认值、继承 common 并校验
func buildProvider(name string, node *providerDocument, common CommonConfig, missing missingVariables) (ProviderConfig, error) {
	if node == nil {
		node = &providerDocument{}
	}

	path := "providers." + name
	provider := ProviderConfig{
		Name:                  name,
		Type:                  valueOrString(node.Type, ""),
		API:                   valueOrString(node.API, DefaultOpenAIAPI),
		Enabled:               valueOrBool(node.Enabled, false),
		APIKey:                valueOrString(node.APIKey, ""),
		BaseURL:               valueOrString(node.BaseURL, ""),
		DefaultModel:          valueOrString(node.DefaultModel, ""),
		TimeoutSeconds:        valueOrFloat(node.TimeoutSeconds, common.TimeoutSeconds),
		ConnectTimeoutSeconds: common.ConnectTimeoutSeconds,
		MaxRetries:            valueOrInt(node.MaxRetries, common.MaxRetries),
		NativeOptions:         copyOptions(node.NativeOptions),
	}

	if provider.Type == "" {
		if !slices.Contains(validProviderTypes, name) {
			return provider, invalidField(path + ".type 必填：实例名不是 openai、anthropic、gemini 之一")
		}

		provider.Type = name
	}

	if !slices.Contains(validProviderTypes, provider.Type) {
		return provider, invalidField(path + ".type 取值非法：" + provider.Type)
	}

	if node.API != nil && provider.Type != ProviderTypeOpenAI {
		return provider, invalidField(path + ".api 只适用于 type=openai")
	}

	if !slices.Contains(validOpenAIAPIs, provider.API) {
		return provider, invalidField(path + ".api 取值非法：" + provider.API)
	}

	if provider.TimeoutSeconds <= 0 {
		return provider, invalidField(path + ".timeout_seconds 必须大于 0")
	}

	if provider.MaxRetries < 0 {
		return provider, invalidField(path + ".max_retries 不能小于 0")
	}

	if provider.Enabled && strings.TrimSpace(provider.APIKey) == "" {
		return provider, core.NewError(core.CategoryAuthMissing, describeMissing(path+".api_key", "启用时必填", missing), name)
	}

	return provider, nil
}

// buildAgent 填充 Agent 默认值并校验
func buildAgent(name string, node *agentDocument, missing missingVariables) (AgentConfig, error) {
	path := "agents." + name
	defaultExecutable := DefaultClaudeCodeExecutable

	if name == core.AgentCodex {
		defaultExecutable = DefaultCodexExecutable
	}

	agent := AgentConfig{
		Name:               name,
		Enabled:            valueOrBool(node.Enabled, false),
		AuthMode:           valueOrString(node.AuthMode, DefaultAuthMode),
		APIKey:             valueOrString(node.APIKey, ""),
		ExecutablePath:     valueOrString(node.ExecutablePath, defaultExecutable),
		HomeDirectory:      valueOrString(node.HomeDirectory, ""),
		WorkingDirectory:   valueOrString(node.WorkingDirectory, ""),
		TimeoutSeconds:     valueOrFloat(node.TimeoutSeconds, DefaultAgentTimeoutSeconds),
		PermissionMode:     valueOrString(node.PermissionMode, DefaultPermissionMode),
		AllowedTools:       copyStrings(node.AllowedTools),
		SandboxMode:        valueOrString(node.SandboxMode, DefaultSandboxMode),
		EnvAllowlist:       copyStrings(node.EnvAllowlist),
		MaxConcurrentRuns:  valueOrInt(node.MaxConcurrentRuns, DefaultMaxConcurrentRuns),
		MaxOutputBytes:     valueOrInt64(node.MaxOutputBytes, DefaultMaxOutputBytes),
		MaxLineBytes:       valueOrInt64(node.MaxLineBytes, DefaultMaxLineBytes),
		MaxStderrBytes:     valueOrInt64(node.MaxStderrBytes, DefaultMaxStderrBytes),
		MaxBufferedEvents:  valueOrInt(node.MaxBufferedEvents, DefaultMaxBufferedEvents),
		MaxToolOutputChars: valueOrInt(node.MaxToolOutputChars, DefaultMaxToolOutputChars),
		KillGraceSeconds:   valueOrFloat(node.KillGraceSeconds, DefaultKillGraceSeconds),
		CollectEvents:      valueOrBool(node.CollectEvents, false),
		NativeOptions:      copyOptions(node.NativeOptions),
	}

	if err := validateAgent(path, agent, missing); err != nil {
		return agent, err
	}

	return agent, nil
}

// validateAgent 校验 Agent 字段取值与启用时的必填项
func validateAgent(path string, agent AgentConfig, missing missingVariables) error {
	if !slices.Contains(validAuthModes, agent.AuthMode) {
		return invalidField(path + ".auth_mode 取值非法：" + agent.AuthMode)
	}

	if !slices.Contains(validSandboxModes, agent.SandboxMode) {
		return invalidField(path + ".sandbox_mode 取值非法：" + agent.SandboxMode)
	}

	if agent.TimeoutSeconds <= 0 {
		return invalidField(path + ".timeout_seconds 必须大于 0")
	}

	if strings.TrimSpace(agent.ExecutablePath) == "" {
		return invalidField(path + ".executable_path 不能为空")
	}

	numericChecks := []struct {
		isValid bool
		message string
	}{
		{agent.MaxConcurrentRuns >= 1, ".max_concurrent_runs 不能小于 1"},
		{agent.MaxOutputBytes >= 1, ".max_output_bytes 不能小于 1"},
		{agent.MaxLineBytes >= 1, ".max_line_bytes 不能小于 1"},
		{agent.MaxStderrBytes >= 0, ".max_stderr_bytes 不能小于 0"},
		{agent.MaxBufferedEvents >= 1, ".max_buffered_events 不能小于 1"},
		{agent.MaxToolOutputChars >= 0, ".max_tool_output_chars 不能小于 0"},
		{agent.KillGraceSeconds >= 0, ".kill_grace_seconds 不能小于 0"},
	}

	for _, check := range numericChecks {
		if !check.isValid {
			return invalidField(path + check.message)
		}
	}

	if !agent.Enabled {
		return nil
	}

	if agent.AuthMode == AuthModeAPIKey && strings.TrimSpace(agent.APIKey) == "" {
		return core.NewError(core.CategoryAuthMissing, describeMissing(path+".api_key", "auth_mode=api_key 时必填", missing), agent.Name)
	}

	if agent.Name == core.AgentCodex && agent.AuthMode == AuthModeAPIKey && strings.TrimSpace(agent.HomeDirectory) == "" {
		return invalidField(path + ".home_directory 在 auth_mode=api_key 时必填，避免改写用户登录状态")
	}

	return nil
}

// describeMissing 组装缺失字段的错误信息，写出缺失的环境变量名但不写值
func describeMissing(path string, reason string, missing missingVariables) string {
	message := path + " 为空（" + reason + "）"
	names := missing[path]

	if len(names) > 0 {
		message += fmt.Sprintf("，缺少环境变量：%s", strings.Join(names, ", "))
	}

	return message
}

// invalidField 组装 CONFIG_INVALID 错误
func invalidField(message string) *core.Error {
	return core.NewError(core.CategoryConfigInvalid, message, "")
}

// valueOrString 返回指针值，nil 时返回默认值
func valueOrString(value *string, fallback string) string {
	if value == nil {
		return fallback
	}

	return *value
}

// valueOrFloat 返回指针值，nil 时返回默认值
func valueOrFloat(value *float64, fallback float64) float64 {
	if value == nil {
		return fallback
	}

	return *value
}

// valueOrInt 返回指针值，nil 时返回默认值
func valueOrInt(value *int, fallback int) int {
	if value == nil {
		return fallback
	}

	return *value
}

// valueOrInt64 返回指针值，nil 时返回默认值
func valueOrInt64(value *int64, fallback int64) int64 {
	if value == nil {
		return fallback
	}

	return *value
}

// valueOrBool 返回指针值，nil 时返回默认值
func valueOrBool(value *bool, fallback bool) bool {
	if value == nil {
		return fallback
	}

	return *value
}

// copyOptions 复制原生参数，nil 时返回空对象
func copyOptions(options map[string]any) map[string]any {
	copied := map[string]any{}

	for key, value := range options {
		copied[key] = value
	}

	return copied
}

// copyStrings 复制字符串切片，nil 时返回空切片
func copyStrings(values []string) []string {
	copied := make([]string, 0, len(values))
	copied = append(copied, values...)

	return copied
}
