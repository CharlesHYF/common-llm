<h1 align="center">common-llm for Python</h1>
<p align="center">用同一套 async 接口调用 OpenAI、Anthropic、Gemini 模型 API 与 Claude Code、Codex 两个 Coding Agent</p>

<div align="center">

![Python](https://img.shields.io/badge/Python-3.11%2B-3776AB) ![version](https://img.shields.io/badge/version-1.0.0-blue) ![License](https://img.shields.io/badge/License-MIT-green) ![uv](https://img.shields.io/badge/package%20manager-uv-DE5FE9) ![typed](https://img.shields.io/badge/typing-mypy%20strict-informational)

</div>

## 介绍
common-llm 是一个可直接复制进项目的 Python 库，把三家模型 API 和两个 Coding Agent 的调用、流式事件、错误与配置统一成同一套契约，底层全部经由各厂商官方 SDK 调用。它解决的是每个项目重复封装 SDK、CLI、凭证与错误处理的问题。它不是 HTTP 网关，不做自动模型路由、成本优化或 Agent 编排，调用方始终显式指定 Provider 与模型。

## 特性
- 模型 API：OpenAI（Responses 与 Chat Completions）、Anthropic Messages、Gemini generateContent；支持非流式与流式生成、工具调用、结构化输出、图片输入，厂商原生参数经 SDK 的 extra body 机制透传。
- Coding Agent：Claude Code（`claude-agent-sdk`）与 Codex（`openai-codex`，App Server），支持流式事件、会话恢复、中断与运行中审批。
- 配置：YAML / JSON 文件、`${ENV}` 引用、可注入的 `ConfigProvider`（如数据库），按字段合并，`reload()` 失败时保留旧配置。
- 错误分类：19 个统一分类，携带是否可重试、HTTP 状态码、厂商错误码与请求 ID。
- 可观测性：标准库 `logging` 记录 Provider、模型、耗时、错误分类、traceId 与用量，默认不记录 prompt、响应正文与密钥。
- 安全默认值：审批处理器未提供时一律拒绝，Codex 沙箱默认 `workspace-write`，子进程只继承白名单内的环境变量，配置与错误输出对密钥脱敏。

## 环境要求
- Python 3.11 及以上（`google-genai` 3.3.0 要求 `>=3.11`），推荐用 [uv](https://docs.astral.sh/uv/) 管理依赖。
- 使用 Coding Agent 时需要本机安装对应 CLI，并能在 `PATH` 中找到（或用 `executable_path` 指定路径）：
  - Claude Code：`claude` 2.1.325 及以上
  - Codex：`codex` 0.169.0 及以上
- 未安装 CLI 时模型 API 照常可用，只有调用对应 Agent 时才会返回 `CLI_NOT_FOUND`。

## 安装
从 Git 仓库引用（库位于仓库的 `python/` 子目录）：

```bash
uv add "common-llm @ git+https://github.com/CharlesHYF/common-llm.git#subdirectory=python"
# 或使用 pip
pip install "common-llm @ git+https://github.com/CharlesHYF/common-llm.git#subdirectory=python"
```

复制源码：把本目录整体复制进项目（例如 `third_party/common-llm`），再以可编辑路径依赖安装：

```bash
cp -R common-llm/python third_party/common-llm
uv add --editable ./third_party/common-llm
```

本目录不依赖仓库中的其他目录，可以单独构建：

```bash
uv sync
uv run ruff check . && uv run mypy src
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

导出 `OPENAI_API_KEY` 与 `OPENAI_MODEL` 后运行：

```python
import asyncio

from common_llm import CommonLlm, Message, ModelRequest


async def main() -> None:
    llm = CommonLlm.create()
    try:
        response = await llm.generate(ModelRequest(provider="openai", messages=[Message.from_text("user", "用一句话介绍上海")]))
        print(response.text)
    finally:
        await llm.close()


asyncio.run(main())
```

## 使用说明
### 文本生成
`system` 消息会分别映射为 OpenAI `instructions`、Anthropic `system`、Gemini `systemInstruction`；`model` 缺省时取配置的 `default_model`。

```python
from common_llm import CommonLlm, Message, ModelRequest, ModelResponse


async def summarize(llm: CommonLlm) -> ModelResponse:
    request = ModelRequest(
        provider="openai",
        messages=[
            Message.from_text("system", "你是简洁的助手"),
            Message.from_text("user", "用三句话介绍杭州"),
        ],
        temperature=0.3,
        max_output_tokens=256,
        trace_id="trace-001",
    )
    response = await llm.generate(request)
    print(response.text, response.finish_reason, response.usage.total_tokens)
    return response
```

### 流式输出
`stream()` 返回 async 迭代器，事件依次为 `started`、`text_delta` / `tool_call_delta`、`usage`、`completed`；失败时以唯一的 `error` 事件结束，不抛异常。提前退出循环即取消请求，适配层会关闭底层流。

```python
from common_llm import CommonLlm, Message, ModelCompletedEvent, ModelErrorEvent, ModelRequest, ModelTextDeltaEvent


async def stream_poem(llm: CommonLlm) -> None:
    request = ModelRequest(provider="anthropic", messages=[Message.from_text("user", "写一首四行短诗")])
    async for event in llm.stream(request):
        if isinstance(event, ModelTextDeltaEvent):
            print(event.text, end="", flush=True)
        elif isinstance(event, ModelCompletedEvent):
            print(f"\n完成：{event.response.finish_reason}")
        elif isinstance(event, ModelErrorEvent):
            print(f"\n失败：{event.error.category.value} {event.error.message}")
```

### 工具调用
工具参数是 JSON Schema；响应中的 `tool_calls[].arguments` 是 JSON 字符串。把工具执行结果放进 `tool` 角色消息再请求一次即可完成一轮调用。

```python
import json

from common_llm import (
    CommonLlm,
    Message,
    ModelRequest,
    ToolCallPart,
    ToolChoice,
    ToolDefinition,
    ToolResultPart,
)

WEATHER_TOOL = ToolDefinition(
    name="get_weather",
    description="查询城市天气",
    parameters={
        "type": "object",
        "properties": {"city": {"type": "string"}},
        "required": ["city"],
    },
)


async def ask_weather(llm: CommonLlm) -> str:
    question = Message.from_text("user", "上海今天天气如何")
    first = await llm.generate(ModelRequest(provider="gemini", messages=[question], tools=[WEATHER_TOOL], tool_choice=ToolChoice(type="auto")))
    if not first.tool_calls:
        return first.text

    call = first.tool_calls[0]
    city = json.loads(call.arguments)["city"]
    follow_up = [
        question,
        Message(role="assistant", content=[ToolCallPart(id=call.id, name=call.name, arguments=call.arguments)]),
        Message(role="tool", content=[ToolResultPart(tool_call_id=call.id, content=f"{city} 晴，18 到 25 度")]),
    ]
    second = await llm.generate(ModelRequest(provider="gemini", messages=follow_up, tools=[WEATHER_TOOL]))
    return second.text
```

`ToolChoice` 取值：`auto`、`none`、`required`，或 `ToolChoice(type="tool", name="get_weather")` 指定工具。

### 结构化输出
`ResponseFormat(type="json_schema", ...)` 分别映射为 OpenAI `text.format` / `response_format`、Anthropic `output_config.format`、Gemini `responseJsonSchema`。

```python
import json
from typing import Any

from common_llm import CommonLlm, Message, ModelRequest, ResponseFormat

CITY_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {"city": {"type": "string"}, "population": {"type": "integer"}},
    "required": ["city", "population"],
    "additionalProperties": False,
}


async def extract_city(llm: CommonLlm) -> Any:
    request = ModelRequest(
        provider="openai",
        messages=[Message.from_text("user", "上海有多少人口")],
        response_format=ResponseFormat(type="json_schema", name="city_info", schema=CITY_SCHEMA, strict=True),
    )
    response = await llm.generate(request)
    return json.loads(response.text)
```

### 图片输入
`ImagePart` 提供 `url`，或提供 `base64` 加 `mime_type`，二者只能选一种。

```python
import base64
from pathlib import Path

from common_llm import CommonLlm, ImagePart, Message, ModelRequest, TextPart


async def describe_image(llm: CommonLlm, image_path: Path) -> str:
    encoded = base64.b64encode(image_path.read_bytes()).decode("ascii")
    message = Message(
        role="user",
        content=[
            TextPart(text="描述这张图片"),
            ImagePart(base64=encoded, mime_type="image/png"),
        ],
    )
    response = await llm.generate(ModelRequest(provider="anthropic", messages=[message]))
    return response.text
```

### 厂商原生参数
请求级 `provider_options` 与配置级 `native_options` 合并后（请求级优先）经 SDK 的 extra body 机制透传：OpenAI / Anthropic 为 `extra_body`，Gemini 为 `HttpOptions.extra_body`（按字段深度合并）。键与已映射字段重名（如 `model`、`messages`、`tools`）时报 `CONFIG_INVALID`，不会静默覆盖。

```python
from common_llm import CommonLlm, Message, ModelRequest, ModelResponse


async def generate_with_options(llm: CommonLlm) -> ModelResponse:
    request = ModelRequest(
        provider="openai",
        messages=[Message.from_text("user", "你好")],
        provider_options={"store": False, "reasoning": {"effort": "low"}},
        include_raw_response=True,
    )
    response = await llm.generate(request)
    print(response.raw_response)
    return response
```

`include_raw_response=True` 时 `response.raw_response` 返回厂商原始响应，默认为 `None`，不会写进日志。

### 多个 Provider 实例
`providers` 的键是实例名，`type` 指定厂商。`type: openai` 的实例用 `api` 选择协议：`responses`（默认）或 `chat_completions`，后者用于只支持 Chat Completions 的兼容端点（vLLM、DeepSeek 等），同样经官方 OpenAI SDK 调用。

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
```

```python
from common_llm import CommonLlm, Message, ModelRequest


async def compare(llm: CommonLlm) -> None:
    for capability in llm.list_providers():
        print(capability.provider, capability.features)

    for provider in ("openai", "deepseek"):
        response = await llm.generate(ModelRequest(provider=provider, messages=[Message.from_text("user", "只回复 OK")]))
        print(provider, response.text)
```

### Coding Agent
`run()` 返回 `AgentResult`，`run_stream()` 逐个返回 `AgentEvent`，两者都不抛异常，失败以 `status="failed"` 或唯一的 `failed` 事件返回。`resume(session_id, request)` 在已有会话上继续；`interrupt(run_id)` 中断进行中的 run，结果为 `cancelled`。

```python
from common_llm import (
    AgentApprovalRequestedEvent,
    AgentCompletedEvent,
    AgentFailedEvent,
    AgentMessageEvent,
    AgentRequest,
    AgentResult,
    AgentToolStartedEvent,
    ApprovalDecision,
    ApprovalRequest,
    CommonLlm,
)


async def approve_read_only(approval: ApprovalRequest) -> ApprovalDecision:
    if approval.kind == "tool" and approval.summary == "Read":
        return "approve"

    return "decline"


async def review_repository(llm: CommonLlm, workspace: str) -> AgentResult:
    agent = llm.agent("claude_code")
    result = await agent.run(AgentRequest(agent="claude_code", prompt="读取 README.md 并概括", working_directory=workspace))
    if result.status != "completed" or result.session_id is None:
        print(result.error)
        return result

    follow_up = AgentRequest(agent="claude_code", prompt="再列出三个改进建议", working_directory=workspace)
    async for event in agent.run_stream(follow_up.model_copy(update={"session_id": result.session_id}), approval_handler=approve_read_only):
        if isinstance(event, AgentMessageEvent) and not event.is_delta:
            print(event.text)
        elif isinstance(event, AgentToolStartedEvent):
            print(f"[tool] {event.tool_name}")
        elif isinstance(event, AgentApprovalRequestedEvent):
            print(f"[approval] {event.summary} -> {event.decision}")
        elif isinstance(event, AgentCompletedEvent | AgentFailedEvent):
            print(f"[{event.type}]")

    return await agent.resume(result.session_id, AgentRequest(agent="claude_code", prompt="总结一下", working_directory=workspace))


async def run_with_interrupt(llm: CommonLlm, workspace: str) -> None:
    agent = llm.agent("codex")
    request = AgentRequest(agent="codex", prompt="逐个解释当前目录的文件", working_directory=workspace, timeout_seconds=300)
    async for event in agent.run_stream(request):
        if event.type == "started":
            await agent.interrupt(event.run_id)
        elif event.type == "cancelled":
            print("已中断")
```

审批处理器是 `async (ApprovalRequest) -> "approve" | "approve_for_session" | "decline" | "cancel"`，未提供、抛出异常或返回非法值时一律按 `decline` 处理，并发出 `approval_requested` 事件（`decision: decline`）。

权限与沙箱默认值：

| 项 | 默认值 | 说明 |
|---|---|---|
| Claude Code `permission_mode` | `default` | CLI 不支持 `default` 取值时不传参；其他 CLI 不支持的取值报 `SDK_UNSUPPORTED_FEATURE` |
| Claude Code 审批 | 经 `can_use_tool` 回调 | 未列入 `allowed_tools` 的工具调用交给审批处理器，默认拒绝 |
| Codex `sandbox_mode` | `workspace-write` | 只允许写工作目录 |
| Codex `approvalPolicy` | `on-request` | 审批请求交给审批处理器，默认回复 `decline`（Codex Python SDK 自带的处理器会自动同意，本库始终替换） |
| Codex 网络访问 | 关闭 | 保持 Codex 默认值，需要时经 `native_options` 开启 |
| `bypassPermissions` / `danger-full-access` | 不作为默认值 | 只在显式指定时使用，并输出 WARN 日志 |

Agent 认证：Claude Code `auth_mode: api_key` 时向子进程注入 `ANTHROPIC_API_KEY`；Codex `auth_mode: api_key` 时必须配置独立的 `home_directory` 作为 `CODEX_HOME` 并在其中登录，不改写用户 `~/.codex`；`cli_existing_session` 复用本机 CLI 登录。第三方产品不得复用用户的个人订阅登录，Claude Agent SDK 按 Anthropic Commercial Terms 使用 API key。

### 自定义配置来源
任何带 `load()` 方法、返回配置部分字段的对象都可以作为 `ConfigProvider` 注入，优先级高于配置文件。`reload()` 重新加载全部来源，校验失败或 `load()` 抛出异常时保留旧配置并抛出 `CONFIG_INVALID`，成功后重建客户端，旧客户端在进行中的请求结束后关闭。

```python
import os
from collections.abc import Mapping
from typing import Any

from common_llm import CommonLlm


class EnvironmentConfigProvider:
    def load(self) -> Mapping[str, Any]:
        return {
            "providers": {
                "anthropic": {
                    "enabled": True,
                    "api_key": os.environ.get("ANTHROPIC_API_KEY", ""),
                    "default_model": os.environ.get("ANTHROPIC_MODEL", ""),
                    "native_options": {"max_tokens": 1024},
                },
            },
        }


async def refresh_config() -> None:
    llm = CommonLlm.create(EnvironmentConfigProvider(), overrides={"common": {"log_level": "WARN"}})
    await llm.reload()
    print(llm.config)
    await llm.close()
```

基于标准库 `sqlite3` 配置表的完整示例见 `examples/database_config_provider.py`。

### 访问原生客户端
统一接口没有覆盖的能力，可以通过 `native_client()` 直接使用官方 SDK 客户端：OpenAI 为 `AsyncOpenAI`，Anthropic 为 `AsyncAnthropic`，Gemini 为 `genai.Client`。

```python
from openai import AsyncOpenAI

from common_llm import CommonLlm


async def list_models(llm: CommonLlm) -> None:
    client = llm.model("openai").native_client()
    if isinstance(client, AsyncOpenAI):
        page = await client.models.list()
        for model in page.data:
            print(model.id)
```

## 配置
配置文件查找顺序：`CommonLlm.create(config_path=...)` 显式传入，其次环境变量 `COMMON_LLM_CONFIG`，再次当前目录的 `common-llm.yaml`；按扩展名识别 YAML 或 JSON。三者都不存在且没有其他来源时报 `CONFIG_INVALID`。

优先级从高到低：`create(overrides=...)` 运行时覆盖、注入的 `ConfigProvider`、配置文件、字段默认值，按字段合并（高优先级中出现的字段覆盖同名字段，未出现的保留）。

环境变量规则：

| 写法 | 含义 |
|---|---|
| `${NAME}` | 取环境变量值，不存在时为空字符串 |
| `${NAME:-default}` | 变量不存在或为空时取 `default` |
| `$${NAME}` | 字面量 `${NAME}`，不替换 |

环境变量只通过引用生效，不会自动覆盖任何字段；只替换字符串值，不替换键名。必填字段为空时报 `CONFIG_INVALID` 或 `AUTH_MISSING`，错误信息写出缺失的变量名，不写值。

常用字段（`common` 段：`timeout_seconds` 120、`connect_timeout_seconds` 10、`max_retries` 2、`log_level` `INFO`）：

| 字段 | 默认值 | 说明 |
|---|---|---|
| `providers.<实例名>.type` | 实例名 | `openai` / `anthropic` / `gemini`，实例名不是三者之一时必填 |
| `providers.<实例名>.api` | `responses` | 仅 `type: openai`：`responses` / `chat_completions` |
| `providers.<实例名>.enabled` | `false` | |
| `providers.<实例名>.api_key` | 无 | 启用时必填 |
| `providers.<实例名>.base_url` | SDK 默认值 | |
| `providers.<实例名>.default_model` | 无 | 请求未指定 `model` 时使用 |
| `providers.<实例名>.timeout_seconds` / `max_retries` | 继承 `common` | |
| `providers.<实例名>.native_options` | `{}` | 每次请求附加的原生参数；Anthropic 的 `max_tokens` 作为 `max_output_tokens` 的默认值 |
| `agents.<claude_code\|codex>.enabled` | `false` | |
| `agents.*.auth_mode` | `cli_existing_session` | `api_key` / `cli_existing_session` / `vendor_supported` |
| `agents.*.executable_path` | `claude` / `codex` | 在 `PATH` 中查找 |
| `agents.*.home_directory` | 无 | Codex 的 `CODEX_HOME`，`auth_mode: api_key` 时必填 |
| `agents.*.working_directory` | 无 | 请求未指定时使用，必须存在且为目录 |
| `agents.*.timeout_seconds` | 600 | 整次 run 的时长上限 |
| `agents.*.permission_mode` / `allowed_tools` | `default` / `[]` | Claude Code |
| `agents.*.sandbox_mode` | `workspace-write` | Codex |
| `agents.*.env_allowlist` | `[]` | 子进程额外继承的环境变量名 |
| `agents.*.max_concurrent_runs` | 4 | 超出报 `RATE_LIMITED` |
| `agents.*.max_output_bytes` / `max_line_bytes` | 10485760 / 8388608 | 输出累计上限与单条消息上限 |
| `agents.*.max_tool_output_chars` | 4000 | `tool_completed.output` 截断长度 |
| `agents.*.collect_events` | `false` | `run()` 结果是否附带全部事件 |

完整示例见本目录的 `common-llm.example.yaml`，复制为 `common-llm.yaml` 后修改；`common-llm.yaml` 已在 `.gitignore` 中忽略，不要提交真实密钥。

## 错误处理
只有 `generate()` 会抛出 `CommonLlmError`，`error.info` 是 `ErrorInfo`（`category`、`message`、`provider`、`retriable`、`request_id`、`vendor_code`、`status_code`、`cause`，均已脱敏）。`stream()`、`run()`、`run_stream()` 不抛异常，失败（包括 Provider 不存在、配置非法、CLI 不存在、工作目录非法等预检失败）以唯一的 `error` / `failed` 终止事件或 `status="failed"` 的 `AgentResult` 返回。任务被取消时 `asyncio.CancelledError` 照常向上传播。

```python
from common_llm import CommonLlm, CommonLlmError, ErrorCategory, Message, ModelRequest


async def generate_or_none(llm: CommonLlm) -> str | None:
    try:
        response = await llm.generate(ModelRequest(provider="openai", messages=[Message.from_text("user", "你好")]))
    except CommonLlmError as error:
        if error.info.retriable:
            print(f"可重试：{error.category.value}")
        elif error.category == ErrorCategory.AUTH_FAILED:
            print("检查 api_key")
        return None

    return response.text
```

模型请求的重试交给各 SDK 内置机制（`max_retries`），本库不再额外重试。

| category | 触发条件 | 可重试 |
|---|---|---|
| `CONFIG_INVALID` | 配置缺失、取值非法、`provider_options` 键冲突、工作目录不存在 | 否 |
| `AUTH_MISSING` | 需要的 key 为空、CLI 未登录 | 否 |
| `AUTH_FAILED` | HTTP 401；Gemini 400 且详情含 `API_KEY_INVALID` | 否 |
| `PERMISSION_DENIED` | HTTP 403 | 否 |
| `MODEL_UNAVAILABLE` | HTTP 404 | 否 |
| `RATE_LIMITED` | HTTP 429（非额度类）、Agent 同时运行数达到上限 | 是 |
| `QUOTA_EXCEEDED` | HTTP 429 且 OpenAI 错误码为 `insufficient_quota` | 否 |
| `TIMEOUT` | 请求或 run 超时、HTTP 408 | 是 |
| `CANCELLED` | 调用方取消 | 否 |
| `NETWORK_ERROR` | 连接失败、连接中断 | 是 |
| `PROVIDER_ERROR` | HTTP 5xx（可重试）、其余 4xx、Agent 返回失败结果 | 视状态码 |
| `SDK_UNSUPPORTED_FEATURE` | 请求了 SDK 或 CLI 版本不支持的能力 | 否 |
| `CLI_NOT_FOUND` | 可执行文件不存在 | 否 |
| `CLI_VERSION_UNSUPPORTED` | CLI 版本低于最低支持版本 | 否 |
| `CLI_PROCESS_FAILED` | 进程非零退出且没有结果、输出超过上限 | 否 |
| `CLI_PROTOCOL_ERROR` | 输出不符合 stream-json / JSON-RPC、握手失败、单条消息超限 | 否 |
| `AGENT_APPROVAL_REQUIRED` | 操作需要审批但被拒绝导致 run 失败 | 否 |
| `SANDBOX_DENIED` | 沙箱拒绝导致 run 失败 | 否 |
| `INTERNAL_ERROR` | 本库自身缺陷 | 否 |

## 依赖版本
| 依赖 | 锁定版本 | 用途 |
|---|---|---|
| Python | 3.11+ | 运行时 |
| `openai` | 3.51.0 | OpenAI Responses / Chat Completions |
| `anthropic` | 2.8.0 | Anthropic Messages API |
| `google-genai` | 3.3.0 | Gemini generateContent |
| `claude-agent-sdk` | 0.2.182 | Claude Code Agent |
| `openai-codex` | 0.169.0 | Codex Agent（经 App Server） |
| `openai-codex-cli-bin` | 0.169.0 | `openai-codex` 的依赖，本库不使用其内置二进制 |
| `PyYAML` | 6.0.3 | YAML 配置文件 |
| `pydantic` | 2.14.3 | 由上述 SDK 引入，用于契约类型与配置校验 |
| `ruff`（开发） | 0.17.5 | 静态检查 |
| `mypy`（开发） | 2.4.1 | 类型检查 |
| `types-PyYAML`（开发） | 6.0.12.20261113 | PyYAML 类型存根 |

直接依赖在 `pyproject.toml` 中固定版本，完整依赖树见 `uv.lock`。本库不实现 HTTP 兜底，不直接依赖 `httpx2`。

## 已知限制
- 只提供 async 接口，没有同步包装。
- `anthropic` 2.8.0 的 `messages.create` 不提供 `temperature` 参数，请求带 `temperature` 时报 `SDK_UNSUPPORTED_FEATURE`；如需设置请放入 `provider_options`。
- Gemini SDK 不支持单独设置连接超时，`connect_timeout_seconds` 对 Gemini 不生效；Gemini 没有请求 ID，`request_id` 为 `None`。
- 流式响应的 `raw_response` 只在 OpenAI Responses 中提供，其余为 `None`。
- Agent 子进程由 SDK 启动和结束：`kill_grace_seconds` 不生效（Claude SDK 关闭 stdin 后等待 5 秒再发 SIGTERM，Codex SDK 等待 2 秒后强制结束）；SDK 不暴露退出码，`AgentResult.exit_code` 始终为 `None`；SDK 不暴露原始输出行，`max_output_bytes` 与 Codex 的 `max_line_bytes` 按消息重新序列化后的字节数计算。
- 两个 Agent SDK 都会把父进程环境变量与传入的 `env` 合并，本库把未列入 `env_allowlist` 且不属于运行必需（`PATH`、`HOME` 等）的变量置为空字符串，而不是从子进程环境中移除。
- `openai-codex` 的 `AsyncCodex` 不接受审批处理器，Codex 适配器改用 `CodexClient` 并在线程中调用；为保证审批事件与通知的先后顺序，适配器覆盖了 `CodexClient` 的内部方法 `_coerce_notification`，升级 `openai-codex` 时需要复核。
- Claude Code 不发出 `file_changed` 事件（从 Edit / Write 工具调用推断属于猜测）；Codex 不提供成本，`cost_usd` 为 `None`。

## 示例
| 文件 | 用途 | 运行命令 |
|---|---|---|
| `examples/text_generation.py` | 从 `common-llm.yaml` 加载配置，完成一次非流式生成 | `uv run python examples/text_generation.py openai` |
| `examples/stream_generation.py` | 流式打印文本增量与完成原因 | `uv run python examples/stream_generation.py anthropic` |
| `examples/coding_agent.py` | 执行一次 Agent run，打印事件流并使用自定义审批处理器 | `uv run python examples/coding_agent.py claude_code ./workspace` |
| `examples/config_switch.py` | 同时配置官方 OpenAI 与 OpenAI 兼容端点，按实例名切换 | `uv run python examples/config_switch.py` |
| `examples/database_config_provider.py` | 用 `sqlite3` 配置表实现 `ConfigProvider` 并演示 `reload()` | `uv run python examples/database_config_provider.py` |

## 许可证
本项目基于 [MIT License](../LICENSE) 发布。
