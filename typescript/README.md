<h1 align="center">common-llm for TypeScript</h1>
<p align="center">面向 Node.js 服务端的模型 API 与 Coding Agent 统一调用库</p>

<div align="center">

![Node.js](https://img.shields.io/badge/Node.js-22%2B-339933) ![TypeScript](https://img.shields.io/badge/TypeScript-7.0.2-3178C6) ![version](https://img.shields.io/badge/version-1.0.0-blue) ![module](https://img.shields.io/badge/module-ESM%20%7C%20CJS-informational) ![pnpm](https://img.shields.io/badge/pnpm-11-F69220) ![License](https://img.shields.io/badge/License-MIT-green)

</div>

## 介绍
`@charleshyf/common-llm` 用同一套请求、响应、事件与错误结构调用 OpenAI、Anthropic、Gemini 三家模型 API，以及 Claude Code、Codex 两个 Coding Agent，底层全部走官方 SDK。业务代码只依赖统一的 `ModelRequest` / `AgentRequest` 契约，切换厂商或实例只改配置。它是一个嵌入业务进程的库，不是 HTTP 网关，也不做自动模型路由、成本优化或 Agent 编排。

## 特性
- 模型 API：OpenAI Responses 与 Chat Completions、Anthropic Messages、Gemini generateContent；支持非流式与流式、工具调用、结构化输出、图片输入。
- Coding Agent：Claude Code（`@anthropic-ai/claude-agent-sdk`）与 Codex（`@openai/codex-sdk`）；支持 run、流式事件、会话恢复、中断、审批回调（Claude Code）。
- 配置：YAML / JSON 文件、`${ENV}` 引用、注入 `ConfigProvider`、运行时覆盖，按字段合并；`reload()` 失败时保留旧配置。
- 错误分类：19 个统一分类，携带 `retriable`、`statusCode`、`vendorCode`、`requestId`。
- 可观测性：可注入 logger 与 traceId / runId 生成器，所有事件带同一 traceId；默认不输出日志，日志不含 prompt 与密钥。
- 安全默认值：审批默认拒绝，Codex 默认 `workspace-write` 沙箱且关闭网络，子进程只继承白名单环境变量，配置与错误序列化时密钥输出 `***`。

## 环境要求
- Node.js 22 及以上，仅支持服务端运行。
- 构建需要 pnpm（开发验证环境为 Node 24.16.0、pnpm 11.10.0）。
- Coding Agent 需要本机安装对应 CLI：Claude Code 2.1.325 及以上、Codex 0.169.0 及以上，默认在 `PATH` 中查找 `claude` / `codex`。
- 未安装 CLI 时模型 API 照常可用，只有调用对应 Agent 时才会报 `CLI_NOT_FOUND`。

## 安装
包暂未发布到 npm，构建产物 `dist/` 也不进仓库，因此通过复制源码目录安装：

```bash
# 1. 复制本目录到你的项目
cp -R common-llm/typescript your-project/vendor/common-llm

# 2. 安装依赖并构建
cd your-project/vendor/common-llm
pnpm install
pnpm run build

# 3. 在你的项目中以本地路径引用
cd your-project
pnpm add file:./vendor/common-llm
```

`pnpm-workspace.yaml` 中的 `allowBuilds` 允许 esbuild 执行安装脚本，构建时需要保留。包同时提供 ESM 与 CJS 入口：

```ts
import { CommonLlm } from "@charleshyf/common-llm";
```

```js
const { CommonLlm } = require("@charleshyf/common-llm");
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

调用一次文本生成：

```ts
import { CommonLlm } from "@charleshyf/common-llm";

const llm = await CommonLlm.create();

const response = await llm.generate({
	provider: "openai",
	messages: [
		{
			role: "user",
			content: [
				{
					type: "text",
					text: "用一句话介绍上海",
				},
			],
		},
	],
});

console.log(response.text);
console.log(response.usage);
await llm.close();
```

```bash
export OPENAI_API_KEY=sk-...
export OPENAI_MODEL=<模型名>
node quickstart.ts
```

## 使用说明
### 文本生成
`generate()` 返回 `ModelResponse`。`system` 消息会分别映射为 OpenAI `instructions`、Anthropic `system`、Gemini `systemInstruction`；`model` 缺省时取配置的 `default_model`。

```ts
import { CommonLlm } from "@charleshyf/common-llm";

const llm = await CommonLlm.create();

const response = await llm.generate({
	provider: "anthropic",
	model: "claude-model-name",
	maxOutputTokens: 1024,
	timeoutSeconds: 60,
	traceId: "order-42",
	messages: [
		{
			role: "system",
			content: [
				{
					type: "text",
					text: "你是简洁的助手",
				},
			],
		},

		{
			role: "user",
			content: [
				{
					type: "text",
					text: "总结这段话",
				},
			],
		},
	],
});

console.log(response.text, response.finishReason, response.requestId);
await llm.close();
```

Anthropic 的 `max_tokens` 是必填参数：请求未传 `maxOutputTokens` 时取配置 `native_options.max_tokens`，两者都没有报 `CONFIG_INVALID`。

### 流式输出
`stream()` 返回 `AsyncIterable<ModelEvent>`，事件依次为 `started`、`text_delta` / `tool_call_delta`、`usage`（厂商返回过用量时才有），最后以 `completed` 或 `error` 中的一个结束。提前 `break` 会关闭底层流；传入 `AbortSignal` 可取消，取消后以 `CANCELLED` 错误事件结束。

```ts
import { CommonLlm } from "@charleshyf/common-llm";

const llm = await CommonLlm.create();
const controller = new AbortController();

for await (const event of llm.stream({
	provider: "gemini",
	messages: [
		{
			role: "user",
			content: [
				{
					type: "text",
					text: "写一首四行短诗",
				},
			],
		},
	],
}, {
	signal: controller.signal,
})) {
	if (event.type === "text_delta") {
		process.stdout.write(event.text);
	} else if (event.type === "completed") {
		console.log("\n", event.response.usage);
	} else if (event.type === "error") {
		console.error(event.error.category, event.error.message);
	}
}

await llm.close();
```

### 工具调用
工具参数用 JSON Schema 描述；`toolChoice` 取 `auto` / `none` / `required` 或指定工具。模型返回的工具调用在 `response.toolCalls` 中，`arguments` 是 JSON 字符串。执行工具后把结果作为 `tool` 消息传回：

```ts
import { CommonLlm } from "@charleshyf/common-llm";

import type { Message, ToolDefinition } from "@charleshyf/common-llm";

const llm = await CommonLlm.create();
const tools: ToolDefinition[] = [
	{
		name: "get_weather",
		description: "查询城市天气",
		parameters: {
			type: "object",
			properties: {
				city: {
					type: "string",
				},
			},
			required: [
				"city",
			],
		},
	},
];
const messages: Message[] = [
	{
		role: "user",
		content: [
			{
				type: "text",
				text: "上海今天天气如何",
			},
		],
	},
];

const first = await llm.generate({
	provider: "openai",
	messages,
	tools,
	toolChoice: {
		type: "auto",
	},
});

for (const toolCall of first.toolCalls) {
	const toolArguments = JSON.parse(toolCall.arguments) as {
		city: string;
	};

	messages.push({
		role: "assistant",
		content: [
			{
				type: "tool_call",
				id: toolCall.id,
				name: toolCall.name,
				arguments: toolCall.arguments,
			},
		],
	});
	messages.push({
		role: "tool",
		content: [
			{
				type: "tool_result",
				toolCallId: toolCall.id,
				content: `${toolArguments.city}：晴，18 到 25 度`,
			},
		],
	});
}

const final = await llm.generate({
	provider: "openai",
	messages,
	tools,
});

console.log(final.text);
await llm.close();
```

### 结构化输出
`responseFormat` 设为 `json_schema` 后，模型按 schema 输出 JSON 文本，映射为 OpenAI `text.format` / `response_format`、Anthropic `output_config.format`、Gemini `responseJsonSchema`：

```ts
import { CommonLlm } from "@charleshyf/common-llm";

const llm = await CommonLlm.create();

const response = await llm.generate({
	provider: "openai",
	messages: [
		{
			role: "user",
			content: [
				{
					type: "text",
					text: "提取：张三，32 岁",
				},
			],
		},
	],
	responseFormat: {
		type: "json_schema",
		name: "person",
		strict: true,
		schema: {
			type: "object",
			properties: {
				name: {
					type: "string",
				},
				age: {
					type: "integer",
				},
			},
			required: [
				"name",
				"age",
			],
			additionalProperties: false,
		},
	},
});

const person = JSON.parse(response.text) as {
	name: string;
	age: number;
};

console.log(person.name, person.age);
await llm.close();
```

### 图片输入
`image` 片段二选一：`url`，或 `base64` 加 `mimeType`。

```ts
import { readFile } from "node:fs/promises";

import { CommonLlm } from "@charleshyf/common-llm";

const llm = await CommonLlm.create();
const imageBase64 = (await readFile("chart.png")).toString("base64");

const response = await llm.generate({
	provider: "anthropic",
	maxOutputTokens: 1024,
	messages: [
		{
			role: "user",
			content: [
				{
					type: "text",
					text: "描述这两张图",
				},

				{
					type: "image",
					base64: imageBase64,
					mimeType: "image/png",
				},

				{
					type: "image",
					url: "https://example.com/photo.jpg",
				},
			],
		},
	],
});

console.log(response.text);
await llm.close();
```

### 厂商原生参数
未统一的厂商参数用请求级 `providerOptions` 或配置级 `native_options` 透传，两者合并且请求级优先。OpenAI 与 Anthropic 作为请求体的额外字段发送，Gemini 经 `httpOptions.extraBody` 合并进请求体。与本库已映射的字段（如 `model`、`messages`、`tools`）重名时报 `CONFIG_INVALID`，不会静默覆盖。

```ts
import { CommonLlm } from "@charleshyf/common-llm";

const llm = await CommonLlm.create();

const response = await llm.generate({
	provider: "anthropic",
	maxOutputTokens: 512,
	messages: [
		{
			role: "user",
			content: [
				{
					type: "text",
					text: "给我三个产品名",
				},
			],
		},
	],
	providerOptions: {
		temperature: 0.8,
		metadata: {
			user_id: "user-001",
		},
	},
});

console.log(response.text);
await llm.close();
```

Anthropic 的 `ModelRequest.temperature` 会报 `SDK_UNSUPPORTED_FEATURE`（见已知限制），确需传递时按上例通过 `providerOptions` 透传。需要返回厂商原始响应时设置 `includeRawResponse: true`，结果在 `response.rawResponse`。

### 多个 Provider 实例
`providers` 的键是实例名，`type` 指定厂商；实例名本身是 `openai` / `anthropic` / `gemini` 时可省略 `type`。`type: openai` 的实例用 `api` 选择协议：`responses`（默认）或 `chat_completions`，后者用于只支持 Chat Completions 的兼容端点：

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
    default_model: ${DEEPSEEK_MODEL}
  local_vllm:
    type: openai
    api: chat_completions
    enabled: true
    api_key: ${LOCAL_VLLM_API_KEY:-none}
    base_url: http://127.0.0.1:8000/v1
    default_model: ${LOCAL_VLLM_MODEL}
```

请求中的 `provider` 填实例名：

```ts
import { CommonLlm } from "@charleshyf/common-llm";

const llm = await CommonLlm.create();

for (const capability of llm.listProviders()) {
	console.log(capability.provider, capability.features);
}

const response = await llm.generate({
	provider: "deepseek",
	messages: [
		{
			role: "user",
			content: [
				{
					type: "text",
					text: "你好",
				},
			],
		},
	],
});

console.log(response.provider, response.text);
console.log(llm.model("deepseek").supports("structured_output"));
await llm.close();
```

兼容端点对工具、结构化输出与 usage 的支持程度不一，本库不做假设：端点返回错误时按错误分类映射，缺失的 usage 字段为 `null`。

### Coding Agent
先在配置中启用 Agent：

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

`run()` 返回汇总后的 `AgentResult`，`runStream()` 逐个返回 `AgentEvent`，`resume()` 按会话 ID 继续，`interrupt()` 中断运行中的 run：

```ts
import { CommonLlm } from "@charleshyf/common-llm";

const llm = await CommonLlm.create();
const codex = llm.agent("codex");

const result = await codex.run({
	agent: "codex",
	prompt: "阅读 README 并总结",
	workingDirectory: "/path/to/repo",
	timeoutSeconds: 300,
});

console.log(result.status, result.finalText, result.usage);

if (result.sessionId) {
	const followUp = await codex.resume(result.sessionId, {
		agent: "codex",
		prompt: "再列出三个改进建议",
	});
	console.log(followUp.finalText);
}

const claude = llm.agent("claude_code");

for await (const event of claude.runStream({
	agent: "claude_code",
	prompt: "检查测试为什么失败",
	allowedTools: [
		"Read",
		"Grep",
	],
	envAllowlist: [
		"NODE_ENV",
	],
})) {
	if (event.type === "started") {
		setTimeout(() => {
			void claude.interrupt(event.runId);
		}, 60_000);
	} else if (event.type === "message" && !event.isDelta) {
		console.log(event.text);
	} else if (event.type === "tool_started") {
		console.log("tool:", event.toolName, event.input);
	}
}

await llm.close();
```

审批处理器只有 Claude Code 支持，作为 `run` / `runStream` 的第二个参数传入，返回 `approve`、`approve_for_session`、`decline` 或 `cancel`；每次审批都会发出 `approval_requested` 事件。未提供处理器时一律拒绝：

```ts
import { CommonLlm } from "@charleshyf/common-llm";

import type { ApprovalHandler } from "@charleshyf/common-llm";

const llm = await CommonLlm.create();
const approvalHandler: ApprovalHandler = async (request) => {
	if (request.toolName === "Read") {
		return "approve";
	}

	return "decline";
};

const result = await llm.agent("claude_code").run({
	agent: "claude_code",
	prompt: "整理 docs 目录",
}, {
	approvalHandler,
});

console.log(result.status);
await llm.close();
```

权限与沙箱默认值：

| 项 | 默认 | 说明 |
|---|---|---|
| Claude Code `permission_mode` | `default` | 本机 CLI 的 `--permission-mode` 取值不含 `default` 时不传该参数；未列入 `allowed_tools` 的写操作与 shell 被拒绝 |
| 审批回调 | 未提供时一律拒绝 | 拒绝的工具调用发出 `approval_requested`（`decision: decline`） |
| Codex `sandbox_mode` | `workspace-write` | 只允许写工作目录；`approvalPolicy` 固定为 `never` |
| Codex 网络访问 | 关闭 | `nativeOptions.networkAccessEnabled: true` 才开启 |
| 子进程环境变量 | `PATH`、`HOME` 等必需变量 | 其余变量需列入 `env_allowlist` / `envAllowlist` |
| `bypassPermissions` / `danger-full-access` | 不使用 | 只在显式指定时使用，并输出 WARN 日志 |

`capabilities()` 返回探测到的可执行文件路径、版本、支持的能力与事件类型；单个客户端同时运行的 run 超过 `max_concurrent_runs` 时报 `RATE_LIMITED`。

### 自定义配置来源
实现 `ConfigProvider` 的 `load()` 即可从数据库、配置中心等来源提供配置，返回的字段会覆盖配置文件中的同名字段。`reload()` 重新执行全部来源的加载与校验，失败时保留旧配置并抛出 `CONFIG_INVALID`：

```ts
import { CommonLlm, isCommonLlmError } from "@charleshyf/common-llm";

import type { CommonLlmConfigInput, ConfigProvider } from "@charleshyf/common-llm";

const configProvider: ConfigProvider = {
	load: async (): Promise<CommonLlmConfigInput> => ({
		providers: {
			openai: {
				enabled: true,
				api_key: process.env.OPENAI_API_KEY ?? "",
				default_model: "model-from-database",
			},
		},
	}),
};

const llm = await CommonLlm.create({
	configProvider,
	overrides: {
		common: {
			log_level: "WARN",
		},
	},
});

try {
	await llm.reload();
} catch (error) {
	if (isCommonLlmError(error)) {
		console.error("reload 失败，继续使用旧配置：", error.message);
	}
}

console.log(JSON.stringify(llm.config));
await llm.close();
```

`CommonLlm.create()` 还接受 `configPath`、`logger`、`idGenerator`、`environment`（替代 `process.env` 用于 `${ENV}` 替换与 Agent 子进程环境）与 `workingDirectory`。内置的 `FileConfigProvider` 也可以直接注入。

### 访问原生客户端
统一接口未覆盖的能力可以通过 `nativeClient()` 直接使用官方 SDK 客户端，返回值类型由具体的 ModelClient 决定：

```ts
import { CommonLlm, OpenAiModelClient } from "@charleshyf/common-llm";

const llm = await CommonLlm.create();
const client = llm.model("openai");

if (client instanceof OpenAiModelClient) {
	const openai = client.nativeClient();
	const models = await openai.models.list();
	console.log(models.data.length);
}

await llm.close();
```

`AnthropicModelClient` 与 `GeminiModelClient` 的 `nativeClient()` 分别返回 `Anthropic` 与 `GoogleGenAI` 实例。

## 配置
配置文件查找顺序：`CommonLlm.create({ configPath })` > 环境变量 `COMMON_LLM_CONFIG` > 当前目录 `common-llm.yaml`，按扩展名识别 `.yaml` / `.yml` / `.json`。都不存在且没有注入其他来源时报 `CONFIG_INVALID`。

优先级从高到低：运行时覆盖（`overrides`）> `ConfigProvider` > 配置文件 > 默认值。合并按字段进行，高优先级来源中出现的字段覆盖低优先级同名字段，未出现的字段保留。

环境变量只通过引用生效，不会自动覆盖任何字段：

| 写法 | 含义 |
|---|---|
| `${NAME}` | 替换为变量值；变量不存在时替换为空字符串 |
| `${NAME:-default}` | 变量不存在或为空时使用 `default` |
| `$${NAME}` | 字面量 `${NAME}`，不替换 |

启用项的 `api_key` 为空时报 `AUTH_MISSING`，错误信息写出缺失的变量名，不写值。

`common` 与 `providers.<实例名>` 常用字段：

| 字段 | 默认 | 说明 |
|---|---|---|
| `common.timeout_seconds` | `120` | 单次模型请求超时 |
| `common.max_retries` | `2` | SDK 内置重试次数，本库不再叠加重试 |
| `common.log_level` | `INFO` | `DEBUG` / `INFO` / `WARN` / `ERROR` |
| `type` | 实例名 | `openai` / `anthropic` / `gemini`，实例名不是三者之一时必填 |
| `api` | `responses` | 仅 `type: openai`：`responses` / `chat_completions` |
| `enabled` | `false` | 未启用的实例不会创建客户端 |
| `api_key` | 无 | 启用时必填 |
| `base_url` | SDK 默认值 | 兼容端点或代理地址 |
| `default_model` | 无 | 请求未指定 `model` 时使用 |
| `timeout_seconds` / `max_retries` | 继承 `common` | 实例级覆盖 |
| `native_options` | `{}` | 每次请求附加的原生参数 |

`agents.claude_code` 与 `agents.codex` 常用字段：

| 字段 | 默认 | 说明 |
|---|---|---|
| `enabled` | `false` | |
| `auth_mode` | `cli_existing_session` | `api_key` / `cli_existing_session` / `vendor_supported` |
| `api_key` | 无 | `auth_mode: api_key` 时必填 |
| `executable_path` | `claude` / `codex` | 在 `PATH` 中查找，或写绝对路径 |
| `home_directory` | 无 | Codex 的 `CODEX_HOME`；Codex 使用 `api_key` 时必填，避免改写用户登录状态 |
| `working_directory` | 无 | 请求未指定时使用，必须存在且是目录 |
| `timeout_seconds` | `600` | 整次 run 的总时长 |
| `permission_mode` / `allowed_tools` | `default` / `[]` | Claude Code |
| `sandbox_mode` | `workspace-write` | Codex |
| `env_allowlist` | `[]` | 允许子进程继承的环境变量 |
| `max_concurrent_runs` | `4` | 单个客户端的并发 run 上限 |
| `max_output_bytes` / `max_line_bytes` | `10485760` / `8388608` | stdout 累计上限与单行上限 |
| `max_tool_output_chars` | `4000` | `tool_completed.output` 截断长度（按 Unicode 码点） |
| `kill_grace_seconds` | `5` | SIGTERM 到 SIGKILL 的等待时间 |
| `collect_events` | `false` | `run()` 结果是否附带全部事件 |
| `native_options` | `{}` | 透传给 Agent SDK 的原生选项 |

完整示例见本目录的 `common-llm.example.yaml`。真实配置文件 `common-llm.yaml` 可能含密钥，已在 `.gitignore` 中忽略；配置对象在 `JSON.stringify`、`String()` 与 `util.inspect` 时密钥输出 `***`。

认证方面，Claude Code 在 `auth_mode: api_key` 时向子进程注入 `ANTHROPIC_API_KEY`，Codex 使用 SDK 的 `apiKey` 选项。第三方产品不得复用用户的个人订阅登录，Claude Agent SDK 按 Anthropic Commercial Terms 使用 API key。

## 错误处理
`generate()` 失败时抛出 `CommonLlmError`，`error.info` 是 `ErrorInfo`（`category`、`message`、`provider`、`retriable`、`requestId`、`vendorCode`、`statusCode`、`cause`），消息与 `cause` 已做密钥脱敏。`stream()` 与 `runStream()` 不抛异常，以唯一的 `error` / `failed` / `cancelled` 终止事件结束；`run()` 不抛异常，返回 `status: "failed"` 或 `"cancelled"` 的 `AgentResult` 并附 `error`。配置错误（如 Provider 未启用）在调用入口直接抛出。

```ts
import { CommonLlm, isCommonLlmError } from "@charleshyf/common-llm";

const llm = await CommonLlm.create();

try {
	await llm.generate({
		provider: "openai",
		messages: [
			{
				role: "user",
				content: [
					{
						type: "text",
						text: "你好",
					},
				],
			},
		],
	});
} catch (error) {
	if (isCommonLlmError(error) && error.retriable) {
		console.warn("可重试：", error.category, error.info.requestId);
	} else if (isCommonLlmError(error)) {
		console.error(error.category, error.info.statusCode, error.info.vendorCode);
	} else {
		throw error;
	}
}

const result = await llm.agent("codex").run({
	agent: "codex",
	prompt: "运行测试",
});

if (result.status === "failed") {
	console.error(result.error?.category, result.error?.message, result.exitCode);
}

await llm.close();
```

| category | 触发条件 | 可重试 |
|---|---|---|
| `CONFIG_INVALID` | 配置缺失或非法、`providerOptions` / `nativeOptions` 键冲突、工作目录无效 | 否 |
| `AUTH_MISSING` | 需要的 key 为空 | 否 |
| `AUTH_FAILED` | HTTP 401；Gemini 400 且详情含 `API_KEY_INVALID` | 否 |
| `PERMISSION_DENIED` | HTTP 403 | 否 |
| `MODEL_UNAVAILABLE` | HTTP 404 | 否 |
| `RATE_LIMITED` | HTTP 429（非额度类）、Agent 并发上限 | 是 |
| `QUOTA_EXCEEDED` | 429 且 OpenAI 错误码为 `insufficient_quota` | 否 |
| `TIMEOUT` | 请求或 run 超时、HTTP 408 | 是 |
| `CANCELLED` | 调用方取消 | 否 |
| `NETWORK_ERROR` | 连接失败 | 是 |
| `PROVIDER_ERROR` | 5xx（可重试）、其余 4xx、Agent 结果失败 | 视状态码 |
| `SDK_UNSUPPORTED_FEATURE` | 请求了 Provider / Agent / CLI 不支持的能力 | 否 |
| `CLI_NOT_FOUND` | 可执行文件不存在 | 否 |
| `CLI_VERSION_UNSUPPORTED` | CLI 版本低于最低支持版本 | 否 |
| `CLI_PROCESS_FAILED` | 非零退出且没有结果、输出超过上限 | 否 |
| `CLI_PROTOCOL_ERROR` | 单行超过上限、Codex 输出非法 JSON、进程结束但没有结果 | 否 |
| `AGENT_APPROVAL_REQUIRED` | 预留，本实现当前不产生 | 否 |
| `SANDBOX_DENIED` | 预留，本实现当前不产生 | 否 |
| `INTERNAL_ERROR` | 本库自身缺陷 | 否 |

## 依赖版本
所有依赖版本写死，与 `package.json` 和 `pnpm-lock.yaml` 一致。

| 依赖 | 版本 | 用途 |
|---|---|---|
| Node.js | 22 及以上 | 运行时 |
| `openai` | 7.57.0 | OpenAI Responses 与 Chat Completions |
| `@anthropic-ai/sdk` | 1.8.0 | Anthropic Messages |
| `@google/genai` | 3.3.0 | Gemini generateContent |
| `@anthropic-ai/claude-agent-sdk` | 0.3.325 | Claude Code |
| `@openai/codex-sdk` | 0.169.0 | Codex（内部执行 `codex exec --experimental-json`） |
| `zod` | 4.6.7 | Claude Agent SDK 的 peer 依赖 |
| `yaml` | 2.9.1 | YAML 配置解析 |
| `typescript`（开发） | 7.0.2 | 类型检查与声明文件生成 |
| `tsup`（开发） | 9.0.0 | 输出 ESM 与 CJS |
| `@types/node`（开发） | 22.20.5 | Node 22 类型 |

`@modelcontextprotocol/sdk` 是 Claude Agent SDK 的必需 peer 依赖，未在 `package.json` 中声明，由 pnpm 自动安装，锁文件中为 1.32.2。

## 已知限制
- Anthropic 已弃用 `temperature`，`@anthropic-ai/sdk` 1.8.0 的 Messages 参数已移除该字段：`ModelRequest.temperature` 非空时报 `SDK_UNSUPPORTED_FEATURE`（流式以 `error` 事件结束），需要时通过 `providerOptions` 透传。
- `@anthropic-ai/sdk` 1.8.0 以 `Authorization: Bearer` 发送 API key，而不是 `x-api-key` 请求头，经代理转发时需注意。
- `common.connect_timeout_seconds` 在本实现中不生效，三个模型 SDK 都不支持单独设置连接超时。
- Codex TypeScript SDK 没有审批回调：`approvalPolicy` 固定为 `never`，`capabilities().features` 不含 `approvals`，传入 `approvalHandler` 报 `SDK_UNSUPPORTED_FEATURE`。
- Codex SDK 自行创建并管理 `codex exec` 子进程：没有 turn 级中断，`interrupt()` 与超时通过 `AbortSignal` 结束进程（SIGTERM）；不支持按进程组清理和 `kill_grace_seconds`；输出上限按事件序列化后的字节数近似统计；输出非法 JSON 行时 SDK 直接报错，映射为 `CLI_PROTOCOL_ERROR`。
- Claude Code 的子进程由本库通过 SDK 的 `spawnClaudeCodeProcess` 创建并放入独立进程组；SDK 会跳过非 JSON 的 stdout 行，本库把这类行转为 `raw_vendor_event`（`vendorType: invalid_json`），run 不中断。
- Claude Code 不发出 `file_changed` 事件（从 Edit / Write 工具推断属于猜测）；本机 CLI 2.1.325 的帮助中没有 `--max-turns`，`nativeOptions.maxTurns` 报 `SDK_UNSUPPORTED_FEATURE`。
- `executable_path` 默认在 `PATH` 中查找 `claude`，不使用 Agent SDK 自带的 CLI 二进制。
- 两个 Agent SDK 只提供 ESM，CJS 入口通过动态 `import()` 加载它们。

## 示例
示例需先执行 `pnpm run build`。Node 24 可以直接运行 `.ts` 文件，Node 22.18 之前的版本需加 `--experimental-strip-types`。

| 文件 | 用途 | 运行命令 |
|---|---|---|
| `examples/text-generation.ts` | 从 `common-llm.yaml` 加载配置并生成文本 | `node examples/text-generation.ts openai` |
| `examples/stream-generation.ts` | 流式生成并处理增量、用量与错误事件 | `node examples/stream-generation.ts anthropic` |
| `examples/coding-agent.ts` | 运行 Coding Agent 并打印事件 | `node examples/coding-agent.ts codex /path/to/repo` |
| `examples/multi-provider-config.ts` | 同时配置 OpenAI 与兼容端点并按实例名调用 | `node examples/multi-provider-config.ts` |
| `examples/database-config-provider.ts` | 用内存表实现 `ConfigProvider` 并 reload | `node examples/database-config-provider.ts` |

## 许可证
[MIT](../LICENSE)
