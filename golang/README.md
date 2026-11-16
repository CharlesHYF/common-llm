<h1 align="center">common-llm for Go</h1>
<p align="center">用同一套请求、事件与错误结构调用 OpenAI、Anthropic、Gemini 模型 API 与 Claude Code、Codex 两个 Coding Agent 的 Go 库</p>

<div align="center">

![Go](https://img.shields.io/badge/Go-1.26%2B-00ADD8) ![version](https://img.shields.io/badge/version-1.0.0-blue) ![License](https://img.shields.io/badge/License-MIT-green) ![module](https://img.shields.io/badge/module-github.com%2FCharlesHYF%2Fcommon--llm%2Fgolang-informational) ![OpenAI](https://img.shields.io/badge/openai--go-v3.99.0-lightgrey) ![Anthropic](https://img.shields.io/badge/anthropic--sdk--go-v2.9.0-lightgrey)

</div>

## 介绍
common-llm for Go 把三家模型 API 与两个 Coding Agent 封装成统一的 `ModelRequest` / `ModelResponse` / `ModelEvent` 与 `AgentRequest` / `AgentResult` / `AgentEvent`，业务代码切换厂商时只改配置中的 Provider 实例名。
模型调用全部经各厂商官方 Go SDK 完成，Coding Agent 通过本机官方 CLI 子进程完成。
它不是 HTTP 网关，不做自动模型路由、成本优化、Agent 编排或 RAG，也不绕过厂商的登录、沙箱与审批。

## 特性
- 模型 API：OpenAI（Responses API 与 Chat Completions）、Anthropic（Messages API）、Gemini（generateContent），支持非流式、流式、工具调用、结构化输出、图片输入与厂商原生参数透传。
- Coding Agent：Claude Code（`claude -p` stream-json）与 Codex（`codex app-server` JSON-RPC），支持 run、流式事件、resume、interrupt，Codex 支持审批处理器。
- 配置：YAML / JSON 文件、`${ENV}` 引用、运行时覆盖、可注入的 `ConfigProvider`（例如数据库），按字段合并，`Reload` 失败时保留旧配置。
- 错误分类：19 个统一分类，携带是否可重试、HTTP 状态码、厂商错误码与请求 ID，可用 `errors.As` 提取。
- 可观测性：基于 `log/slog` 记录 provider、model、耗时、错误分类、traceId 与用量，默认不记录 prompt、响应正文与密钥。
- 安全默认值：密钥在字符串、日志与 JSON 输出中为 `***`；Agent 子进程只继承运行必需与显式允许的环境变量；Codex 默认 `workspace-write` 沙箱且网络关闭；未提供审批处理器时一律拒绝。

## 环境要求
- Go 1.26 及以上。
- 使用 Coding Agent 时需要本机安装对应 CLI 并完成登录或准备 API key：
  - Claude Code 2.1.325 及以上（`claude --version`）。
  - Codex 0.169.0 及以上（`codex --version`）。
- 未安装 CLI 时模型 API 照常可用，只有调用对应 Agent 时报 `CLI_NOT_FOUND`。

## 安装
从 Git 仓库引用。本 module 位于仓库的 `golang/` 子目录，发布标签写作 `golang/v1.0.0`，`go get` 时版本仍写 `v1.0.0`。仓库为私有仓库时先设置 `GOPRIVATE`，并让 git 能访问该仓库（例如使用 SSH）：
```bash
go env -w GOPRIVATE=github.com/CharlesHYF/*
git config --global url."git@github.com:".insteadOf "https://github.com/"
go get github.com/CharlesHYF/common-llm/golang@v1.0.0
```

复制源码。把本目录整体复制到项目中，例如 `third_party/common-llm-go/`，再用 `replace` 指向它：
```bash
cp -R common-llm/golang third_party/common-llm-go
go mod edit -require=github.com/CharlesHYF/common-llm/golang@v1.0.0
go mod edit -replace=github.com/CharlesHYF/common-llm/golang=./third_party/common-llm-go
go mod tidy
```

## 快速开始
在工作目录创建 `common-llm.yaml`：
```yaml
providers:
  openai:
    enabled: true
    api_key: ${OPENAI_API_KEY}
    default_model: ${OPENAI_MODEL}
```

`main.go`：
```go
package main

import (
	"context"
	"fmt"
	"log"

	commonllm "github.com/CharlesHYF/common-llm/golang"
	"github.com/CharlesHYF/common-llm/golang/core"
)

func main() {
	ctx := context.Background()

	client, err := commonllm.Create(ctx, commonllm.Options{})
	if err != nil {
		log.Fatal(err)
	}
	defer client.Close()

	response, err := client.Generate(ctx, &core.ModelRequest{
		Provider: "openai",
		Messages: []core.Message{core.TextMessage(core.RoleUser, "用一句话介绍上海")},
	})
	if err != nil {
		log.Fatal(err)
	}

	fmt.Println(response.Text)
}
```

运行：
```bash
export OPENAI_API_KEY=sk-...
export OPENAI_MODEL=<模型名>
go run .
```

## 使用说明
以下片段假设已按快速开始创建了 `ctx` 与 `client`，并位于返回 `error` 的函数中。
### 文本生成
`system` 消息分别映射为 OpenAI `instructions`、Anthropic `system`、Gemini `systemInstruction`：
```go
temperature := 0.2
maxOutputTokens := int64(512)

response, err := client.Generate(ctx, &core.ModelRequest{
	Provider: "gemini",
	Messages: []core.Message{
		core.TextMessage(core.RoleSystem, "你是天气助手"),
		core.TextMessage(core.RoleUser, "上海今天天气如何"),
	},
	Temperature:     &temperature,
	MaxOutputTokens: &maxOutputTokens,
	TraceID:         "trace-001",
})
if err != nil {
	return err
}

fmt.Println(response.Text, response.FinishReason)
if response.Usage.TotalTokens != nil {
	fmt.Println("totalTokens:", *response.Usage.TotalTokens)
}
```

### 流式输出
`Stream` 返回 `iter.Seq[core.ModelEvent]`，每个流有且只有一个终止事件（`completed` 或 `error`）；提前跳出 `range` 即取消请求并关闭底层流：
```go
request := &core.ModelRequest{
	Provider: "anthropic",
	Messages: []core.Message{core.TextMessage(core.RoleUser, "用三句话介绍上海")},
}

for event := range client.Stream(ctx, request) {
	switch event.Type {
	case core.ModelEventStarted:
		if event.Model != nil {
			fmt.Println("model:", *event.Model)
		}
	case core.ModelEventTextDelta:
		fmt.Print(event.Text)
	case core.ModelEventError:
		return fmt.Errorf("流式调用失败：%s %s", event.Error.Category, event.Error.Message)
	case core.ModelEventCompleted:
		fmt.Println("\nfinishReason:", event.Response.FinishReason)
	}
}
```

### 工具调用
工具参数是 JSON Schema 对象；模型返回的 `ToolCalls[i].Arguments` 是 JSON 字符串。执行工具后，把调用与结果分别作为 assistant 与 tool 消息追加，再请求一次：
```go
request := &core.ModelRequest{
	Provider: "openai",
	Messages: []core.Message{core.TextMessage(core.RoleUser, "上海今天天气如何")},
	Tools: []core.ToolDefinition{{
		Name:        "get_weather",
		Description: "查询城市天气",
		Parameters: map[string]any{
			"type":       "object",
			"properties": map[string]any{"city": map[string]any{"type": "string"}},
			"required":   []any{"city"},
		},
	}},
	ToolChoice: &core.ToolChoice{Type: core.ToolChoiceAuto},
}

response, err := client.Generate(ctx, request)
if err != nil {
	return err
}

if response.FinishReason == core.FinishToolCalls {
	assistant := core.Message{Role: core.RoleAssistant}
	results := core.Message{Role: core.RoleTool}

	for _, call := range response.ToolCalls {
		assistant.Content = append(assistant.Content, core.ToolCallPart(call.ID, call.Name, call.Arguments))
		results.Content = append(results.Content, core.ToolResultPart(call.ID, `{"weather":"晴","temperature":22}`, false))
	}

	request.Messages = append(request.Messages, assistant, results)
	response, err = client.Generate(ctx, request)
	if err != nil {
		return err
	}
}

fmt.Println(response.Text)
```

`ToolChoice` 取值：`auto`、`none`、`required`，或 `{Type: core.ToolChoiceTool, Name: "get_weather"}` 指定工具。流式时工具参数以 `tool_call_delta` 事件分段返回，`completed` 事件中的 `Response.ToolCalls` 为拼接后的完整结果。

### 结构化输出
`json_schema` 分别映射为 OpenAI `text.format` / `response_format`、Anthropic `output_config.format`、Gemini `responseJsonSchema`：
```go
isStrict := true

response, err := client.Generate(ctx, &core.ModelRequest{
	Provider: "openai",
	Messages: []core.Message{core.TextMessage(core.RoleUser, "给出上海今天的天气")},
	ResponseFormat: &core.ResponseFormat{
		Type: core.ResponseFormatJSONSchema,
		Name: "weather",
		Schema: map[string]any{
			"type": "object",
			"properties": map[string]any{
				"city":    map[string]any{"type": "string"},
				"weather": map[string]any{"type": "string"},
			},
			"required":             []any{"city", "weather"},
			"additionalProperties": false,
		},
		Strict: &isStrict,
	},
})
if err != nil {
	return err
}

var weather map[string]any
if err := json.Unmarshal([]byte(response.Text), &weather); err != nil {
	return err
}
```

### 图片输入
图片片段只能放在 user 消息中，二选一提供 URL 或 base64 + MIME 类型：
```go
response, err := client.Generate(ctx, &core.ModelRequest{
	Provider: "anthropic",
	Messages: []core.Message{{
		Role: core.RoleUser,
		Content: []core.ContentPart{
			core.TextPart("描述这两张图片"),
			core.ImageURLPart("https://example.com/cat.png"),
			core.ImageBase64Part("iVBORw0KGgo...", "image/png"),
		},
	}},
})
if err != nil {
	return err
}

fmt.Println(response.Text)
```

### 厂商原生参数
配置中的 `native_options` 与请求的 `ProviderOptions` 合并（请求级优先）后，经 SDK 官方 extra body 机制写入请求体：OpenAI 与 Anthropic 为 `option.WithJSONSet`，Gemini 为 `HTTPOptions.ExtraBody`。键名与适配器已映射的字段（如 `model`、`messages`、`tools`）重名时报 `CONFIG_INVALID`：
```go
response, err := client.Generate(ctx, &core.ModelRequest{
	Provider: "openai",
	Messages: []core.Message{core.TextMessage(core.RoleUser, "解释量子纠缠")},
	ProviderOptions: map[string]any{
		"reasoning":    map[string]any{"effort": "low"},
		"service_tier": "flex",
	},
})
if err != nil {
	return err
}

fmt.Println(response.Text)
```

### 多个 Provider 实例
`providers` 的键是实例名，`type` 指定厂商；OpenAI 兼容端点用 `type: openai` 加 `api: chat_completions`，同样经官方 OpenAI SDK 调用：
```yaml
providers:
  openai:
    enabled: true
    api_key: ${OPENAI_API_KEY}
    default_model: ${OPENAI_MODEL}
  deepseek:
    type: openai
    api: chat_completions
    enabled: true
    api_key: ${DEEPSEEK_API_KEY}
    base_url: https://api.deepseek.com/v1
    default_model: deepseek-chat
  local_vllm:
    type: openai
    api: chat_completions
    enabled: true
    api_key: ${LOCAL_VLLM_API_KEY:-none}
    base_url: http://127.0.0.1:8000/v1
    default_model: ${LOCAL_VLLM_MODEL}
```

请求里的 `Provider` 填实例名：
```go
for _, capability := range client.ListProviders() {
	fmt.Println(capability.Provider, capability.Features)
}

for _, provider := range []string{"openai", "deepseek", "local_vllm"} {
	response, err := client.Generate(ctx, &core.ModelRequest{
		Provider: provider,
		Messages: []core.Message{core.TextMessage(core.RoleUser, "只回复 ok")},
	})
	if err != nil {
		fmt.Println(provider, "失败：", err)
		continue
	}

	fmt.Println(provider, response.Text)
}
```

### Coding Agent
在配置中启用 Agent：
```yaml
agents:
  claude_code:
    enabled: true
    auth_mode: api_key
    api_key: ${ANTHROPIC_API_KEY}
    working_directory: ./workspace
  codex:
    enabled: true
    auth_mode: cli_existing_session
    working_directory: ./workspace
```

`Run` 不返回 error，失败（包括 CLI 不存在、工作目录非法等预检失败）以 `Status = failed` 且 `Error` 非空的结果返回：
```go
agent, err := client.Agent(core.AgentClaudeCode)
if err != nil {
	return err
}

result := agent.Run(ctx, &core.AgentRequest{
	Prompt:           "读取 README.md 并用一句话总结",
	WorkingDirectory: "./workspace",
	AllowedTools:     []string{"Read", "Grep"},
})
if result.Status != core.AgentStatusCompleted {
	return fmt.Errorf("run 失败：%s %s", result.Error.Category, result.Error.Message)
}

fmt.Println(result.FinalText)
if result.Usage != nil && result.Usage.CostUSD != nil {
	fmt.Println("costUsd:", *result.Usage.CostUSD)
}
```

流式执行、中断与续接。`RunStream` 以唯一的 `completed` / `failed` / `cancelled` 终止事件结束；`Interrupt` 对 Claude Code 结束进程组，对 Codex 发送 `turn/interrupt`：
```go
agent, err := client.Agent(core.AgentCodex)
if err != nil {
	return err
}

sessionID := ""
for event := range agent.RunStream(ctx, &core.AgentRequest{Prompt: "列出当前目录的文件并解释用途"}) {
	switch event.Type {
	case core.AgentEventMessage:
		if !event.IsDelta {
			fmt.Println("assistant:", event.Text)
		}
	case core.AgentEventToolStarted:
		fmt.Println("tool:", *event.ToolName)
	case core.AgentEventFileChanged:
		fmt.Println("changes:", event.Changes)
	case core.AgentEventCompleted, core.AgentEventFailed, core.AgentEventCancelled:
		if event.Result.SessionID != nil {
			sessionID = *event.Result.SessionID
		}
	}
}

if sessionID != "" {
	followUp := agent.Resume(ctx, sessionID, &core.AgentRequest{Prompt: "刚才读了哪些文件？"})
	fmt.Println(followUp.Status, followUp.FinalText)
}
```

中断进行中的 run：
```go
for event := range agent.RunStream(ctx, &core.AgentRequest{Prompt: "重构整个项目"}) {
	if event.Type == core.AgentEventStarted {
		if err := agent.Interrupt(ctx, event.RunID); err != nil {
			return err
		}
	}

	if event.Type == core.AgentEventCancelled {
		fmt.Println("已取消：", event.RunID)
	}
}
```

审批处理器只对 Codex 生效。未提供时一律回复 `decline`，同时发出 `approval_requested` 事件；Claude Code CLI 不支持运行中审批，传入时报 `SDK_UNSUPPORTED_FEATURE`：
```go
result := agent.Run(ctx, &core.AgentRequest{
	Prompt:      "运行单元测试并修复失败用例",
	SandboxMode: "workspace-write",
	ApprovalHandler: func(ctx context.Context, approval core.ApprovalRequest) (core.ApprovalDecision, error) {
		if approval.Kind == core.ApprovalKindCommand && strings.HasPrefix(approval.Summary, "go test") {
			return core.DecisionApprove, nil
		}

		return core.DecisionDecline, nil
	},
})
fmt.Println(result.Status)
```

权限与沙箱默认值：

| 项 | 默认 | 说明 |
|---|---|---|
| Claude Code `permission_mode` | `default` | 当前 CLI 的 `--permission-mode` 没有 `default` 取值时不传参；其他不支持的取值报 `SDK_UNSUPPORTED_FEATURE` |
| Claude Code `allowed_tools` | `[]` | 未列入的写操作与 shell 被 CLI 拒绝，体现为 `approval_requested`（`decision: decline`） |
| Codex `sandbox_mode` | `workspace-write` | 只允许写工作目录 |
| Codex `approvalPolicy` | `on-request` | 可经 `nativeOptions` 覆盖 |
| Codex 网络访问 | 关闭 | 需经 `nativeOptions` 透传 `thread/start` 的 `config` 显式开启 |
| 审批处理器 | 未提供时一律 `decline` | |
| `bypassPermissions` / `danger-full-access` | 不作为默认值 | 仅在配置或请求中显式指定时使用，并输出 WARN 日志 |

其他行为：工作目录必须存在且是目录；子进程只继承 `PATH`、`HOME` 等运行必需变量与 `env_allowlist` / `EnvAllowlist` 中的变量；超时或取消时先向进程组发送 SIGTERM，`kill_grace_seconds` 后发送 SIGKILL；单个客户端同时运行数超过 `max_concurrent_runs` 时报 `RATE_LIMITED`。Claude Code 的 `NativeOptions` 键为不带 `--` 的长参数名（如 `max-budget-usd`），必须出现在 `claude -p --help` 中。

### 自定义配置来源
实现 `config.ConfigProvider`（或使用 `config.ConfigProviderFunc`）即可从数据库等来源加载配置。文档键名与配置文件一致，可用 `SetPath` 按点分路径写入：
```go
provider := config.ConfigProviderFunc(func(ctx context.Context) (config.Document, error) {
	document := config.Document{}
	document.SetPath("providers.openai.enabled", true)
	document.SetPath("providers.openai.api_key", os.Getenv("OPENAI_API_KEY"))
	document.SetPath("providers.openai.default_model", "gpt-5")
	document.SetPath("common.max_retries", 1)

	return document, nil
})

client, err := commonllm.Create(ctx, commonllm.Options{ConfigProvider: provider})
if err != nil {
	return err
}
defer client.Close()

// 配置来源更新后重新加载，失败时继续使用旧配置
if err := client.Reload(ctx); err != nil {
	fmt.Println("reload 失败：", err)
}

fmt.Println(client.Config().Providers["openai"])
```

`Reload` 重新执行全部来源加载与校验；失败时保留旧配置并返回 `CONFIG_INVALID`。成功后旧模型客户端在进行中的门面调用结束后关闭，旧 Agent 客户端上进行中的 run 继续运行至结束。

### 访问原生客户端
尚未统一的新能力可以直接使用官方 SDK 客户端。`NativeClient()` 返回值类型：OpenAI 为 `openai.Client`，Anthropic 为 `anthropic.Client`，Gemini 为 `*genai.Client`：
```go
modelClient, err := client.Model("openai")
if err != nil {
	return err
}

native, isOpenAI := modelClient.NativeClient().(openai.Client)
if !isOpenAI {
	return fmt.Errorf("openai 实例的原生客户端类型不符")
}

models, err := native.Models.List(ctx)
if err != nil {
	return err
}

fmt.Println(len(models.Data))
```

## 配置
配置文件查找顺序：`Options.ConfigPath` > 环境变量 `COMMON_LLM_CONFIG` > 当前目录 `common-llm.yaml`；按扩展名识别 YAML（`.yaml` / `.yml`）或 JSON。三者都不存在且没有其他来源时报 `CONFIG_INVALID`。

优先级从高到低：`Options.Overrides`（运行时覆盖）> `Options.ConfigProvider` > 配置文件 > 默认值。按字段合并：高优先级来源中出现的字段覆盖低优先级同名字段，未出现的字段保留。

环境变量只通过引用生效，只替换字符串值，不替换键名：

| 写法 | 结果 |
|---|---|
| `${NAME}` | 替换为变量值；变量不存在时为空字符串，必填字段为空时报错并写出缺失的变量名 |
| `${NAME:-default}` | 变量不存在或为空时使用 `default` |
| `$${NAME}` | 字面量 `${NAME}` |

常用字段（完整示例见本目录 `common-llm.example.yaml`）：

| 字段 | 默认 | 说明 |
|---|---|---|
| `common.timeout_seconds` | `120` | 单次模型请求超时 |
| `common.connect_timeout_seconds` | `10` | 连接超时 |
| `common.max_retries` | `2` | 交给 SDK 的重试次数，Gemini 映射为 `attempts = max_retries + 1` |
| `common.log_level` | `INFO` | `DEBUG` / `INFO` / `WARN` / `ERROR` |
| `providers.<实例名>.type` | 实例名 | `openai` / `anthropic` / `gemini`，实例名不是三者之一时必填 |
| `providers.<实例名>.api` | `responses` | 仅 `type: openai`：`responses` / `chat_completions` |
| `providers.<实例名>.enabled` | `false` | |
| `providers.<实例名>.api_key` | 无 | 启用时必填，建议写成 `${...}` 引用 |
| `providers.<实例名>.base_url` | SDK 默认值 | |
| `providers.<实例名>.default_model` | 无 | 请求未指定 `Model` 时使用 |
| `providers.<实例名>.timeout_seconds` / `max_retries` | 继承 `common` | |
| `providers.<实例名>.native_options` | `{}` | 每次请求附加的原生参数；Anthropic 的 `max_tokens` 作为 `MaxOutputTokens` 的默认值 |
| `agents.<claude_code\|codex>.enabled` | `false` | |
| `agents.*.auth_mode` | `cli_existing_session` | `api_key` / `cli_existing_session` / `vendor_supported` |
| `agents.*.api_key` | 无 | `auth_mode: api_key` 时必填 |
| `agents.*.executable_path` | `claude` / `codex` | 在 `PATH` 中查找，也可写绝对路径 |
| `agents.codex.home_directory` | 无 | Codex 的 `CODEX_HOME`，`auth_mode: api_key` 时必填 |
| `agents.*.working_directory` | 无 | 请求未指定时使用，两者都没有报 `CONFIG_INVALID` |
| `agents.*.timeout_seconds` | `600` | 整次 run 的总时长 |
| `agents.*.permission_mode` | `default` | Claude Code |
| `agents.*.allowed_tools` | `[]` | Claude Code |
| `agents.*.sandbox_mode` | `workspace-write` | Codex |
| `agents.*.env_allowlist` | `[]` | 允许子进程继承的环境变量 |
| `agents.*.max_concurrent_runs` | `4` | |
| `agents.*.max_output_bytes` | `10485760` | 单次 run 标准输出累计上限 |
| `agents.*.max_line_bytes` | `8388608` | 单行 JSONL 上限 |
| `agents.*.max_tool_output_chars` | `4000` | `tool_completed.output` 截断长度（按码点） |
| `agents.*.kill_grace_seconds` | `5` | SIGTERM 到 SIGKILL 的等待时间 |
| `agents.*.collect_events` | `false` | `Run` 的结果是否附带全部事件 |

认证：

| 场景 | 方式 |
|---|---|
| 模型 API | 各 Provider 的 `api_key`；Anthropic 客户端不读取 `ANTHROPIC_*` 环境变量 |
| Claude Code `api_key` | 子进程环境注入 `ANTHROPIC_API_KEY` |
| Claude Code `cli_existing_session` | 复用本机 Claude Code 登录 |
| Codex `api_key` | 以 `home_directory` 作为 `CODEX_HOME`，在其中调用 `account/login/start` 登录，不改写用户 `~/.codex` |
| Codex `cli_existing_session` | 复用本机 Codex 登录，启动时用 `account/read` 校验，未登录报 `AUTH_MISSING` |

第三方产品不得复用用户的个人订阅登录（Claude.ai、ChatGPT 订阅），对外提供服务时请使用 API key；Claude Agent 按 Anthropic Commercial Terms 使用 API key。真实的 `common-llm.yaml` 与 `common-llm.json` 已在本目录 `.gitignore` 中忽略。

## 错误处理
`Generate`、`Model`、`Agent`、`Interrupt`、`Capabilities`、`Reload` 返回 `error`，类型为 `*commonllm.Error`（即 `*core.Error`），`Info` 字段为跨语言统一的 `ErrorInfo`：
```go
_, err := client.Generate(ctx, &core.ModelRequest{
	Provider: "openai",
	Messages: []core.Message{core.TextMessage(core.RoleUser, "hello")},
})

var commonErr *commonllm.Error
if errors.As(err, &commonErr) {
	fmt.Println(commonErr.Info.Category, commonErr.Info.Retriable, commonErr.Info.Message)

	if commonErr.Info.StatusCode != nil {
		fmt.Println("statusCode:", *commonErr.Info.StatusCode)
	}

	if commonErr.Category() == core.CategoryRateLimited {
		fmt.Println("稍后重试")
	}
}
```

`Stream`、`Run`、`RunStream` 不返回 error：`Stream` 以唯一的 `error` 终止事件结束（`event.Error`），`Run` 返回 `Status = failed` 且 `Error` 非空的结果，`RunStream` 以唯一的 `failed` 终止事件结束，取消时为 `cancelled`。预检失败（Provider 不存在、配置非法、CLI 不存在、工作目录非法）同样按此返回。错误消息与 `cause` 已脱敏，不含 key、token 与 prompt 正文。

| category | 触发条件 | 可重试 |
|---|---|---|
| `CONFIG_INVALID` | 配置缺失、取值非法、`ProviderOptions` 键冲突、reload 失败 | 否 |
| `AUTH_MISSING` | 需要的 key 为空、CLI 未登录 | 否 |
| `AUTH_FAILED` | HTTP 401；Gemini 400 且详情含 `API_KEY_INVALID` | 否 |
| `PERMISSION_DENIED` | HTTP 403 | 否 |
| `MODEL_UNAVAILABLE` | HTTP 404 | 否 |
| `RATE_LIMITED` | HTTP 429（非额度类）、本地并发上限 | 是 |
| `QUOTA_EXCEEDED` | 429 且 OpenAI `error.code = insufficient_quota` | 否 |
| `TIMEOUT` | HTTP 408、请求或 run 超时 | 是 |
| `CANCELLED` | 调用方取消 | 否 |
| `NETWORK_ERROR` | 连接失败、DNS、连接中断 | 是 |
| `PROVIDER_ERROR` | 5xx（可重试）、其余 4xx 与 Agent 报告的失败（不可重试） | 视状态码 |
| `SDK_UNSUPPORTED_FEATURE` | 请求了 Provider、Agent 或 CLI 版本不支持的能力 | 否 |
| `CLI_NOT_FOUND` | 可执行文件不存在 | 否 |
| `CLI_VERSION_UNSUPPORTED` | CLI 版本低于最低支持版本 | 否 |
| `CLI_PROCESS_FAILED` | 非零退出且无可解析的结果、输出超限 | 否 |
| `CLI_PROTOCOL_ERROR` | JSONL / JSON-RPC 不符合协议、握手失败、单行超限 | 否 |
| `AGENT_APPROVAL_REQUIRED` | 操作需要审批但被拒绝导致 run 失败 | 否 |
| `SANDBOX_DENIED` | 沙箱拒绝的操作导致 run 失败 | 否 |
| `INTERNAL_ERROR` | 本库自身缺陷 | 否 |

## 依赖版本
与本目录 `go.mod` 一致，间接依赖以 `go.sum` 为准：

| 用途 | 依赖 | 版本 |
|---|---|---|
| 运行时 | Go | 1.26 及以上（`go.mod`：`go 1.26.0`） |
| OpenAI | `github.com/openai/openai-go/v3` | v3.99.0 |
| Anthropic | `github.com/anthropics/anthropic-sdk-go/v2` | v2.9.0 |
| Gemini | `google.golang.org/genai` | v1.77.0 |
| YAML | `go.yaml.in/yaml/v3` | v3.0.6 |
| JSON | 标准库 `encoding/json` | 随 Go |
| 日志 | 标准库 `log/slog` | 随 Go |
| HTTP | 标准库 `net/http` | 随 Go |
| Claude Code CLI | `claude` | 2.1.325 及以上（本机实测 2.1.325） |
| Codex CLI | `codex` | 0.169.0 及以上（本机实测 0.169.0） |

## 已知限制
- Claude Code 通过 CLI 子进程调用：不支持运行中审批回调（`ApprovalHandler` 报 `SDK_UNSUPPORTED_FEATURE`），只能预设 `permission_mode` 与 `allowed_tools`；CLI 不提供文件变更事件，不发出 `file_changed`；中断方式为结束进程组。
- Codex 只走 `codex app-server`，不实现 `codex exec` 路径；App Server 不返回成本，`costUsd` 为 `nil`。
- Anthropic 已弃用 temperature（anthropic-sdk-go v2.9.0 的 `MessageNewParams` 已移除该字段）：请求 `Temperature` 非空时报 `SDK_UNSUPPORTED_FEATURE`，确需传递时写入 `ProviderOptions`（例如 `{"temperature": 0.2}`）。
- Gemini Go SDK 的用量字段是带 `omitempty` 的整数，无法区分 0 与缺失：`cachedInputTokens`、`reasoningTokens` 为 0 时返回 `nil`。
- Gemini 没有请求 ID，`RequestID` 为 `nil`；`IncludeRawResponse` 返回的是 SDK 响应结构重新序列化的 JSON，不是原始响应体。
- openai-go 创建客户端时会读取 `OPENAI_ORG_ID`、`OPENAI_PROJECT_ID`、`OPENAI_CUSTOM_HEADERS` 等环境变量；`api_key` 与 `base_url` 以配置为准。

## 示例
示例在 `examples/` 下，运行前按需准备 `common-llm.yaml` 或环境变量：

| 示例 | 用途 | 运行 |
|---|---|---|
| `text-generation` | 读取 `common-llm.yaml`，发起一次非流式文本生成并处理错误分类 | `go run ./examples/text-generation` |
| `streaming` | 流式生成，逐段输出文本增量 | `go run ./examples/streaming` |
| `coding-agent` | 调用 Codex 执行任务，审批处理器只批准只读命令 | `go run ./examples/coding-agent` |
| `config-switch` | 运行时覆盖配置，同时启用官方 OpenAI 与 OpenAI 兼容端点并切换调用 | `go run ./examples/config-switch` |
| `database-config-provider` | 把配置表映射为 `ConfigProvider`（`database/sql` 实现与内存实现），演示 `Reload` | `go run ./examples/database-config-provider` |

## 许可证
MIT，见仓库根目录 [LICENSE](../LICENSE)。
