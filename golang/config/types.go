// 合并与校验之后的配置结构、字段默认值与密钥脱敏输出
// 创建日期：2026-11-16
// 修改日期：2026-11-16

package config

import (
	"encoding/json"
	"fmt"
	"log/slog"

	"github.com/CharlesHYF/common-llm/golang/core"
)

// Provider 厂商类型
const (
	ProviderTypeOpenAI    = "openai"
	ProviderTypeAnthropic = "anthropic"
	ProviderTypeGemini    = "gemini"
)

// OpenAI 协议
const (
	OpenAIAPIResponses       = "responses"
	OpenAIAPIChatCompletions = "chat_completions"
)

// Agent 认证方式
const (
	AuthModeAPIKey             = "api_key"
	AuthModeCLIExistingSession = "cli_existing_session"
	AuthModeVendorSupported    = "vendor_supported"
)

// Codex 沙箱模式
const (
	SandboxReadOnly         = "read-only"
	SandboxWorkspaceWrite   = "workspace-write"
	SandboxDangerFullAccess = "danger-full-access"
)

// 日志级别
const (
	LogLevelDebug = "DEBUG"
	LogLevelInfo  = "INFO"
	LogLevelWarn  = "WARN"
	LogLevelError = "ERROR"
)

// common 字段默认值
const (
	DefaultTimeoutSeconds        = 120
	DefaultConnectTimeoutSeconds = 10
	DefaultMaxRetries            = 2
	DefaultLogLevel              = LogLevelInfo
)

// agents 字段默认值
const (
	DefaultAgentTimeoutSeconds    = 600
	DefaultPermissionMode         = "default"
	DefaultSandboxMode            = SandboxWorkspaceWrite
	DefaultMaxConcurrentRuns      = 4
	DefaultMaxOutputBytes         = 10485760
	DefaultMaxLineBytes           = 8388608
	DefaultMaxStderrBytes         = 65536
	DefaultMaxBufferedEvents      = 10000
	DefaultMaxToolOutputChars     = 4000
	DefaultKillGraceSeconds       = 5
	DefaultClaudeCodeExecutable   = "claude"
	DefaultCodexExecutable        = "codex"
	DefaultAuthMode               = AuthModeCLIExistingSession
	DefaultOpenAIAPI              = OpenAIAPIResponses
	DefaultConfigFileName         = "common-llm.yaml"
	ConfigPathEnvironmentVariable = "COMMON_LLM_CONFIG"
)

// CommonLlmConfig 合并、校验之后的完整配置。
type CommonLlmConfig struct {
	Common    CommonConfig
	Providers map[string]ProviderConfig
	Agents    map[string]AgentConfig
}

// CommonConfig 全局公共配置。
type CommonConfig struct {
	TimeoutSeconds        float64
	ConnectTimeoutSeconds float64
	MaxRetries            int
	LogLevel              string
}

// ProviderConfig 单个 Provider 实例的配置，超时与重试已继承 common。
type ProviderConfig struct {
	Name                  string
	Type                  string
	API                   string
	Enabled               bool
	APIKey                string
	BaseURL               string
	DefaultModel          string
	TimeoutSeconds        float64
	ConnectTimeoutSeconds float64
	MaxRetries            int
	NativeOptions         map[string]any
}

// AgentConfig 单个 Coding Agent 的配置。
type AgentConfig struct {
	Name               string
	Enabled            bool
	AuthMode           string
	APIKey             string
	ExecutablePath     string
	HomeDirectory      string
	WorkingDirectory   string
	TimeoutSeconds     float64
	PermissionMode     string
	AllowedTools       []string
	SandboxMode        string
	EnvAllowlist       []string
	MaxConcurrentRuns  int
	MaxOutputBytes     int64
	MaxLineBytes       int64
	MaxStderrBytes     int64
	MaxBufferedEvents  int
	MaxToolOutputChars int
	KillGraceSeconds   float64
	CollectEvents      bool
	NativeOptions      map[string]any
}

// providerConfigView 脱敏输出用的别名类型，避免 MarshalJSON 递归
type providerConfigView ProviderConfig

// agentConfigView 脱敏输出用的别名类型，避免 MarshalJSON 递归
type agentConfigView AgentConfig

// redactedProvider 返回密钥替换为 *** 的副本
func (c ProviderConfig) redactedProvider() providerConfigView {
	view := providerConfigView(c)
	view.APIKey = core.RedactSecret(c.APIKey)

	return view
}

// String 输出脱敏后的配置。
func (c ProviderConfig) String() string {
	return fmt.Sprintf("%+v", c.redactedProvider())
}

// GoString 输出脱敏后的配置。
func (c ProviderConfig) GoString() string {
	return c.String()
}

// LogValue 让 slog 输出脱敏后的配置。
func (c ProviderConfig) LogValue() slog.Value {
	return slog.StringValue(c.String())
}

// MarshalJSON 序列化时把密钥输出为 ***。
func (c ProviderConfig) MarshalJSON() ([]byte, error) {
	return json.Marshal(c.redactedProvider())
}

// redactedAgent 返回密钥替换为 *** 的副本
func (c AgentConfig) redactedAgent() agentConfigView {
	view := agentConfigView(c)
	view.APIKey = core.RedactSecret(c.APIKey)

	return view
}

// String 输出脱敏后的配置。
func (c AgentConfig) String() string {
	return fmt.Sprintf("%+v", c.redactedAgent())
}

// GoString 输出脱敏后的配置。
func (c AgentConfig) GoString() string {
	return c.String()
}

// LogValue 让 slog 输出脱敏后的配置。
func (c AgentConfig) LogValue() slog.Value {
	return slog.StringValue(c.String())
}

// MarshalJSON 序列化时把密钥输出为 ***。
func (c AgentConfig) MarshalJSON() ([]byte, error) {
	return json.Marshal(c.redactedAgent())
}

// EnabledProviderNames 返回已启用的 Provider 实例名。
func (c *CommonLlmConfig) EnabledProviderNames() []string {
	names := []string{}

	for name, provider := range c.Providers {
		if provider.Enabled {
			names = append(names, name)
		}
	}

	return names
}

// IsSecretField 判断字段名是否为密钥字段。
func IsSecretField(fieldName string) bool {
	return fieldName == "api_key"
}
