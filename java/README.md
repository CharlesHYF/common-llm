<h1 align="center">common-llm for Java</h1>
<p align="center">用一套 Java API 调用 OpenAI、Anthropic、Gemini 模型与 Claude Code、Codex 两种 Coding Agent</p>

<div align="center">

![Java](https://img.shields.io/badge/Java-17%2B-orange) ![version](https://img.shields.io/badge/version-1.0.0-blue) ![License](https://img.shields.io/badge/License-MIT-green) ![Maven](https://img.shields.io/badge/Maven-io.github.charleshyf%3Acommon--llm-red) ![build](https://img.shields.io/badge/build-Maven%203.9%2B-blue)

</div>

## 介绍
common-llm for Java 把三家模型 API 与两种 Coding Agent 收敛到同一组请求、响应、事件与错误类型上，业务代码换厂商时只需要改配置里的 Provider 实例名。模型调用全部经过各厂商官方 Java SDK，Coding Agent 通过官方 CLI（`claude -p` 子进程、`codex app-server` JSON-RPC）接入。它是一个库，不是 HTTP 网关，也不做自动模型路由、成本优化或 Agent 编排。

## 特性
- 模型 API：OpenAI（Responses API 与 Chat Completions）、Anthropic Messages、Gemini generateContent；统一的 `generate` / `stream`，支持工具调用、JSON Schema 结构化输出、图片输入与厂商原生参数透传。
- 图片生成：OpenAI（Images API 与 Responses API `image_generation` 工具两条路径）与 Gemini（`generateContent` 原生出图）；统一的 `generateImage` / `streamImage`，支持文生图、图生图、蒙版、部分图片流式输出与多张生成。
- Coding Agent：Claude Code 与 Codex；统一的 `run` / `runStream` / `resume` / `interrupt`，运行前探测可执行文件、版本与 CLI 参数集合。
- 配置：YAML 或 JSON 文件、`${ENV}` 环境变量引用、可注入的 `ConfigProvider`（例如数据库），按字段合并，`reload()` 失败时保留旧配置。
- 错误分类：19 个统一分类，携带 `retriable`、HTTP 状态码、厂商错误码与请求 ID，消息已脱敏。
- 可观测性：SLF4J 结构化日志（provider、model、耗时、traceId、usage），默认不记录 prompt、正文与密钥；traceId / runId 生成器可注入。
- 安全默认值：Codex 沙箱 `workspace-write` 且关闭网络，未提供审批处理器时一律拒绝；子进程只继承白名单环境变量；超时或取消时结束整个进程树。

## 环境要求
- Java 17 及以上，Maven 3.9 及以上。
- Coding Agent 需要本机安装对应 CLI 并能在 PATH 中找到（或在配置中写绝对路径）：

| Agent | CLI | 最低版本 |
| --- | --- | --- |
| Claude Code | `claude` | 2.1.325 |
| Codex | `codex` | 0.169.0 |

未安装 CLI 时模型 API 照常可用，Agent 调用以 `CLI_NOT_FOUND` 失败。

## 安装
方式一：从 Git 仓库构建并安装到本地 Maven 仓库，再在项目中声明依赖。
```bash
git clone https://github.com/CharlesHYF/common-llm.git
cd common-llm/java
mvn -q install
```

```xml
<dependency>
	<groupId>io.github.charleshyf</groupId>
	<artifactId>common-llm</artifactId>
	<version>1.0.0</version>
</dependency>
```

方式二：复制源码。
1. 把 `src/main/java/io/github/charleshyf/commonllm` 整个目录复制到你的工程源码目录。
2. 把 [依赖版本](#依赖版本) 表中的六个库依赖加入你的 `pom.xml`。
3. 复制 `common-llm.example.yaml` 为 `common-llm.yaml` 并按需修改。`common-llm.yaml` 可能含密钥，不要提交到仓库。

## 快速开始
在工作目录创建 `common-llm.yaml`：
```yaml
providers:
  openai:
    enabled: true
    api_key: ${OPENAI_API_KEY}
    default_model: ${OPENAI_MODEL}
```

设置 `OPENAI_API_KEY` 与 `OPENAI_MODEL` 两个环境变量后运行：
```java
import io.github.charleshyf.commonllm.CommonLlm;
import io.github.charleshyf.commonllm.core.Message;
import io.github.charleshyf.commonllm.core.ModelRequest;
import io.github.charleshyf.commonllm.core.ModelResponse;

public class QuickStart {

	public static void main(String[] args) {
		try (CommonLlm commonLlm = CommonLlm.create()) {
			ModelResponse response = commonLlm.generate(ModelRequest.builder()
				.provider("openai")
				.addMessage(Message.user("用一句话介绍上海"))
				.build());
			System.out.println(response.getText());
		}
	}
}
```

`CommonLlm.create()` 会读取当前目录的 `common-llm.yaml`，`provider` 填的是配置中的 Provider 实例名。

## 使用说明
### 文本生成
`generate` 同步阻塞并返回 `ModelResponse`。`model` 缺省时取配置的 `default_model`；`system` 消息会分别映射为 OpenAI `instructions`、Anthropic `system`、Gemini `systemInstruction`。
```java
import io.github.charleshyf.commonllm.CommonLlm;
import io.github.charleshyf.commonllm.core.Message;
import io.github.charleshyf.commonllm.core.ModelRequest;
import io.github.charleshyf.commonllm.core.ModelResponse;

public class TextGeneration {

	public static void main(String[] args) {
		try (CommonLlm commonLlm = CommonLlm.create()) {
			ModelRequest request = ModelRequest.builder()
				.provider("openai")
				.addMessage(Message.system("你是天气助手"))
				.addMessage(Message.user("上海今天天气如何"))
				.temperature(0.3)
				.maxOutputTokens(512L)
				.timeoutSeconds(30.0)
				.traceId("trace-001")
				.build();
			ModelResponse response = commonLlm.generate(request);
			System.out.println(response.getText());
			System.out.println(response.getFinishReason().getValue());
			System.out.println(response.getUsage());
			System.out.println(response.getRequestId());
		}
	}
}
```

`ModelResponse` 提供 `getText()`、`getContentParts()`、`getToolCalls()`、`getFinishReason()`（`stop` / `length` / `tool_calls` / `content_filter` / `other`）、`getVendorFinishReason()` 与 `getUsage()`。`getUsage()` 中厂商没有提供的字段为 `null`，不会填 0。请求设置 `includeRawResponse(true)` 时 `getRawResponse()` 返回厂商原始响应。

### 流式输出
`stream` 返回 `ModelStream`，它实现了 `Iterator<ModelEvent>` 与 `AutoCloseable`。每个流第一个事件是 `started`，以且仅以一个 `completed` 或 `error` 结束；提前关闭流视为取消，底层连接随之关闭。
```java
import io.github.charleshyf.commonllm.CommonLlm;
import io.github.charleshyf.commonllm.core.Message;
import io.github.charleshyf.commonllm.core.ModelEvent;
import io.github.charleshyf.commonllm.core.ModelEventType;
import io.github.charleshyf.commonllm.core.ModelRequest;
import io.github.charleshyf.commonllm.core.ModelStream;

public class Streaming {

	public static void main(String[] args) {
		ModelRequest request = ModelRequest.builder()
			.provider("openai")
			.addMessage(Message.user("用三句话介绍上海"))
			.build();

		try (CommonLlm commonLlm = CommonLlm.create(); ModelStream stream = commonLlm.stream(request)) {
			while (stream.hasNext()) {
				ModelEvent event = stream.next();

				if (event.getType() == ModelEventType.TEXT_DELTA) {
					System.out.print(event.getText());
				} else if (event.getType() == ModelEventType.COMPLETED) {
					System.out.println();
					System.out.println(event.getResponse().getUsage());
				} else if (event.getType() == ModelEventType.ERROR) {
					System.err.println(event.getError());
				}
			}
		}
	}
}
```

事件类型：`started`、`text_delta`、`tool_call_delta`、`usage`（收到过厂商用量时发出一次，紧挨在 `completed` 之前）、`completed`（`getResponse()` 为累积后的完整响应）、`error`。

### 工具调用
用 `ToolDefinition` 声明工具，参数是 JSON Schema。模型返回的 `ToolCall.getArguments()` 是 JSON 字符串；执行工具后把调用与结果分别作为 assistant 与 tool 消息追加回去，再次调用即可。
```java
import io.github.charleshyf.commonllm.CommonLlm;
import io.github.charleshyf.commonllm.core.ContentPart;
import io.github.charleshyf.commonllm.core.Message;
import io.github.charleshyf.commonllm.core.MessageRole;
import io.github.charleshyf.commonllm.core.ModelRequest;
import io.github.charleshyf.commonllm.core.ModelResponse;
import io.github.charleshyf.commonllm.core.ToolCall;
import io.github.charleshyf.commonllm.core.ToolChoice;
import io.github.charleshyf.commonllm.core.ToolDefinition;

import java.util.List;
import java.util.Map;

public class ToolCalling {

	public static void main(String[] args) {
		Map<String, Object> parameters = Map.of(
			"type", "object",
			"properties", Map.of("city", Map.of("type", "string")),
			"required", List.of("city"));
		ToolDefinition weatherTool = new ToolDefinition("get_weather", "查询城市天气", parameters);

		try (CommonLlm commonLlm = CommonLlm.create()) {
			ModelRequest.Builder builder = ModelRequest.builder()
				.provider("openai")
				.addMessage(Message.user("上海今天天气如何"))
				.addTool(weatherTool)
				.toolChoice(ToolChoice.auto());
			ModelResponse response = commonLlm.generate(builder.build());

			for (ToolCall toolCall : response.getToolCalls()) {
				String toolOutput = "晴，18 到 25 度";
				builder.addMessage(new Message(MessageRole.ASSISTANT, List.of(ContentPart.toolCall(toolCall.getId(), toolCall.getName(), toolCall.getArguments()))));
				builder.addMessage(new Message(MessageRole.TOOL, List.of(ContentPart.toolResult(toolCall.getId(), toolOutput, false))));
			}

			System.out.println(commonLlm.generate(builder.build()).getText());
		}
	}
}
```

`ToolChoice` 可选 `auto()`、`none()`、`required()` 与 `tool("get_weather")`。

### 结构化输出
`ResponseFormat.jsonSchema` 让模型按 JSON Schema 输出，映射为 OpenAI `text.format` / `response_format`、Anthropic `output_config.format`、Gemini `responseJsonSchema`。
```java
import io.github.charleshyf.commonllm.CommonLlm;
import io.github.charleshyf.commonllm.core.Message;
import io.github.charleshyf.commonllm.core.ModelRequest;
import io.github.charleshyf.commonllm.core.ResponseFormat;

import java.util.List;
import java.util.Map;

public class StructuredOutput {

	public static void main(String[] args) {
		Map<String, Object> schema = Map.of(
			"type", "object",
			"properties", Map.of("city", Map.of("type", "string"), "temperature", Map.of("type", "number")),
			"required", List.of("city", "temperature"),
			"additionalProperties", false);

		try (CommonLlm commonLlm = CommonLlm.create()) {
			String json = commonLlm.generate(ModelRequest.builder()
				.provider("openai")
				.addMessage(Message.user("上海今天的气温是多少"))
				.responseFormat(ResponseFormat.jsonSchema("weather", schema, true))
				.build()).getText();
			System.out.println(json);
		}
	}
}
```

### 图片输入
图片作为 user 消息中的 `ContentPart`，可以是 URL，也可以是 base64 加 `mimeType`。
```java
import io.github.charleshyf.commonllm.CommonLlm;
import io.github.charleshyf.commonllm.core.ContentPart;
import io.github.charleshyf.commonllm.core.Message;
import io.github.charleshyf.commonllm.core.MessageRole;
import io.github.charleshyf.commonllm.core.ModelRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

public class ImageInput {

	public static void main(String[] args) throws IOException {
		String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(Path.of("chart.png")));
		Message message = new Message(MessageRole.USER, List.of(
			ContentPart.text("比较这两张图"),
			ContentPart.imageUrl("https://example.com/photo.jpg"),
			ContentPart.imageBase64(base64, "image/png")));

		try (CommonLlm commonLlm = CommonLlm.create()) {
			System.out.println(commonLlm.generate(ModelRequest.builder().provider("openai").addMessage(message).build()).getText());
		}
	}
}
```

### 厂商原生参数
没有统一的厂商参数通过 `providerOptions` 透传，配置中的 `native_options` 作为每次请求的默认值，请求级优先。透传走官方 SDK 的 extra body 机制（OpenAI / Anthropic 的 additional body properties，Gemini 的 `HttpOptions.extraBody`）。键名与本库已映射的字段重名时报 `CONFIG_INVALID`，不会静默覆盖。
```java
import io.github.charleshyf.commonllm.CommonLlm;
import io.github.charleshyf.commonllm.core.Message;
import io.github.charleshyf.commonllm.core.ModelRequest;

import java.util.Map;

public class ProviderOptions {

	public static void main(String[] args) {
		try (CommonLlm commonLlm = CommonLlm.create()) {
			ModelRequest request = ModelRequest.builder()
				.provider("openai")
				.addMessage(Message.user("解释一下量子纠缠"))
				.putProviderOption("reasoning", Map.of("effort", "low"))
				.putProviderOption("store", false)
				.build();
			System.out.println(commonLlm.generate(request).getText());
		}
	}
}
```

### 多个 Provider 实例
`providers` 的键是实例名，`type` 指定厂商；实例名本身就是 `openai` / `anthropic` / `gemini` 时可省略 `type`。`type: openai` 的实例可用 `api: chat_completions` 接入只支持 Chat Completions 的兼容端点。
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

```java
import io.github.charleshyf.commonllm.CommonLlm;
import io.github.charleshyf.commonllm.core.Message;
import io.github.charleshyf.commonllm.core.ModelRequest;
import io.github.charleshyf.commonllm.core.ProviderCapability;

public class MultipleProviders {

	public static void main(String[] args) {
		try (CommonLlm commonLlm = CommonLlm.create()) {
			for (ProviderCapability capability : commonLlm.listProviders()) {
				ModelRequest request = ModelRequest.builder()
					.provider(capability.getProvider())
					.addMessage(Message.user("只回复 ok"))
					.build();
				System.out.println(capability.getProvider() + ": " + commonLlm.generate(request).getText());
			}
		}
	}
}
```

### 图片生成
在 Provider 实例上配置图片模型 `default_image_model`；`type: openai` 的实例可用 `image_api` 选择 `images`（默认，Images API）或 `responses`（Responses API 的 `image_generation` 工具，对话模型取 `default_model`）。Anthropic 没有图片生成能力，调用时报 `SDK_UNSUPPORTED_FEATURE`。
```yaml
providers:
  openai:
    enabled: true
    api_key: ${OPENAI_API_KEY}
    default_model: ${OPENAI_MODEL}
    default_image_model: ${OPENAI_IMAGE_MODEL}
    image_api: images
  gemini:
    enabled: true
    api_key: ${GEMINI_API_KEY}
    default_image_model: ${GEMINI_IMAGE_MODEL}
```

文生图、图生图与蒙版：`ImageResponse.getImages()` 返回 `GeneratedImage` 列表，`getData()` 是图片字节。输入图片可用 `ImageInput.fromFile`、`fromBytes`、`fromBase64` 构造；`fromUrl` 只有 Responses 路径支持，本库不代为下载。
```java
import io.github.charleshyf.commonllm.CommonLlm;
import io.github.charleshyf.commonllm.core.GeneratedImage;
import io.github.charleshyf.commonllm.core.ImageInput;
import io.github.charleshyf.commonllm.core.ImageRequest;
import io.github.charleshyf.commonllm.core.ImageResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ImageGeneration {

	public static void main(String[] args) throws IOException {
		try (CommonLlm commonLlm = CommonLlm.create()) {
			ImageResponse created = commonLlm.generateImage(ImageRequest.builder()
				.provider("openai")
				.prompt("一只橘色的猫坐在窗台上，水彩风格")
				.size("1024x1024")
				.quality("low")
				.outputFormat("png")
				.imageCount(2)
				.build());

			for (GeneratedImage image : created.getImages()) {
				System.out.println(image.getMimeType() + " " + image.getData().length + " bytes, revisedPrompt=" + image.getRevisedPrompt());
			}

			ImageResponse edited = commonLlm.generateImage(ImageRequest.builder()
				.provider("openai")
				.prompt("把背景换成星空")
				.addImage(ImageInput.fromFile(Path.of("cat.png")))
				.mask(ImageInput.fromFile(Path.of("mask.png")))
				.build());
			Files.write(Path.of("edited.png"), edited.getImages().get(0).getData());

			ImageResponse gemini = commonLlm.generateImage(ImageRequest.builder()
				.provider("gemini")
				.prompt("一只橘色的猫坐在窗台上")
				.aspectRatio("16:9")
				.imageSize("2K")
				.build());
			System.out.println(gemini.getText() + " " + gemini.getUsage());
		}
	}
}
```

流式：`streamImage` 返回 `ImageStream`（`Iterator<ImageEvent>` 加 `AutoCloseable`），不抛异常。事件类型为 `started`、`partial_image`（只在厂商实际返回部分图片时发出，`getPartialData()` 为部分图片字节）、`image_completed`（`getImageIndex()` 区分第几张）、`usage`（只在厂商返回过用量时发出）、`completed` 与 `error`。流式结果不提供 `rawResponse`。
```java
import io.github.charleshyf.commonllm.CommonLlm;
import io.github.charleshyf.commonllm.core.ImageEvent;
import io.github.charleshyf.commonllm.core.ImageEventType;
import io.github.charleshyf.commonllm.core.ImageRequest;
import io.github.charleshyf.commonllm.core.ImageStream;

public class ImageStreaming {

	public static void main(String[] args) {
		ImageRequest request = ImageRequest.builder()
			.provider("openai")
			.prompt("一只橘色的猫坐在窗台上")
			.partialImages(2)
			.build();

		try (CommonLlm commonLlm = CommonLlm.create(); ImageStream stream = commonLlm.streamImage(request)) {
			while (stream.hasNext()) {
				ImageEvent event = stream.next();

				if (event.getType() == ImageEventType.PARTIAL_IMAGE) {
					System.out.println("部分图片 " + event.getPartialIndex() + "：" + event.getPartialData().length + " bytes");
				} else if (event.getType() == ImageEventType.IMAGE_COMPLETED) {
					System.out.println("第 " + event.getImageIndex() + " 张完成");
				} else if (event.getType() == ImageEventType.ERROR) {
					System.err.println(event.getError());
				}
			}
		}
	}
}
```

多张生成：`imageCount(n)` 对应契约字段 `n`。OpenAI Images API 原生支持 1-10 张，一次请求完成；Responses 路径与 Gemini 不支持原生多张，本库循环调用 n 次，并发数由 `image_concurrency` 控制（默认 1，即串行）。循环中任一次失败则整个调用失败并返回该次错误，未开始的请求不再发出，进行中的请求被中断；结果按调用顺序排列，`usage` 逐项求和，任一次为 `null` 则该项为 `null`。流式时按顺序逐次调用并按图片顺序发出事件，`imageIndex` 跨次连续编号。设置 `includeRawResponse(true)` 时，单次调用的 `getRawResponse()` 为厂商原始响应，库内循环时为各次原始响应组成的列表。

各路径支持的参数（不支持的字段一律报 `SDK_UNSUPPORTED_FEATURE`，不会静默忽略）：

| 参数 | OpenAI Images | OpenAI Responses | Gemini |
| --- | --- | --- | --- |
| `images`（图生图） | 是，最多 16 张，不支持 URL | 是，支持 URL | 是，不支持 URL |
| `mask` | 是 | 是 | 否 |
| `imageCount` | 原生 1-10 | 库内循环 | 库内循环 |
| `size` / `quality` / `outputFormat` / `outputCompression` / `background` | 是 | 是 | 否 |
| `aspectRatio` / `imageSize` | 否 | 否 | 是 |
| `partialImages`（仅 `streamImage`） | 是 | 是 | 否 |
| `providerOptions` | additional body properties | additional body properties | `HttpOptions.extraBody` |

`providerOptions` 与已映射字段重名时报 `CONFIG_INVALID`，例如 Images 路径的 `prompt`、`n`、`size`，Responses 路径的 `model`、`input`、`tools`、`tool_choice`，Gemini 的 `contents`、`generationConfig`。Responses 路径多轮修改图片可通过 `putProviderOption("previous_response_id", ...)` 透传。图片客户端复用同一 Provider 的 SDK 客户端，`commonLlm.image("openai").nativeClient()` 与 `commonLlm.model("openai").nativeClient()` 是同一个对象。

### Coding Agent
在配置中启用 Agent 并指定工作目录：
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

`runAgent` 阻塞到运行结束并返回 `AgentResult`；`runAgentStream` 返回 `AgentStream`（`Iterator<AgentEvent>` 加 `AutoCloseable`，关闭即取消）。通过 `agent(name)` 拿到 `AgentClient` 后可以调用 `resume`、`interrupt` 与 `capabilities`。
```java
import io.github.charleshyf.commonllm.CommonLlm;
import io.github.charleshyf.commonllm.core.AgentClient;
import io.github.charleshyf.commonllm.core.AgentEvent;
import io.github.charleshyf.commonllm.core.AgentEventType;
import io.github.charleshyf.commonllm.core.AgentRequest;
import io.github.charleshyf.commonllm.core.AgentResult;
import io.github.charleshyf.commonllm.core.AgentStatus;
import io.github.charleshyf.commonllm.core.AgentStream;
import io.github.charleshyf.commonllm.core.ApprovalDecision;
import io.github.charleshyf.commonllm.core.ApprovalKind;

public class CodingAgent {

	public static void main(String[] args) {
		try (CommonLlm commonLlm = CommonLlm.create()) {
			AgentResult result = commonLlm.runAgent(AgentRequest.builder()
				.agent("claude_code")
				.prompt("阅读 README 并用一句话总结这个项目")
				.build());

			if (result.getStatus() == AgentStatus.COMPLETED) {
				System.out.println(result.getFinalText() + " " + result.getUsage());
			} else {
				System.err.println(result.getError());
			}

			AgentClient codex = commonLlm.agent("codex");
			AgentRequest request = AgentRequest.builder()
				.agent("codex")
				.prompt("运行单元测试并修复失败的用例")
				.approvalHandler(approval -> approval.getKind() == ApprovalKind.COMMAND ? ApprovalDecision.APPROVE : ApprovalDecision.DECLINE)
				.build();
			String sessionId = null;

			try (AgentStream stream = codex.runStream(request)) {
				while (stream.hasNext()) {
					AgentEvent event = stream.next();

					if (event.getType() == AgentEventType.TOOL_STARTED) {
						System.out.println(event.getField("toolName") + " " + event.getField("input"));
					} else if (event.getType() == AgentEventType.COMPLETED) {
						sessionId = event.getResult().getSessionId();
					}
				}
			}

			AgentResult followUp = codex.resume(sessionId, AgentRequest.builder().agent("codex").prompt("总结刚才的修改").build());
			System.out.println(followUp.getFinalText());
		}
	}
}
```

中断进行中的运行：在另一个线程调用 `agentClient.interrupt(stream.getRunId())`，Codex 发送 `turn/interrupt`，Claude Code 结束子进程，事件流以 `cancelled` 结束。

事件类型：`started`、`message`（`isDelta` 区分增量与完整消息）、`tool_started`、`tool_completed`（输出按 Unicode 码点截断到 `max_tool_output_chars`）、`file_changed`（仅 Codex）、`approval_requested`、`usage`、`completed`、`failed`、`cancelled`、`raw_vendor_event`。

权限与沙箱默认值：

| 项 | 默认 | 说明 |
| --- | --- | --- |
| Claude Code `permission_mode` | `default` | 本机 CLI 的可选值里没有 `default` 时不传该参数；不支持的其他取值报 `SDK_UNSUPPORTED_FEATURE` |
| Claude Code 审批 | 不支持运行中审批 | 只能预设 `permissionMode` 与 `allowedTools`；传入 `approvalHandler` 报 `SDK_UNSUPPORTED_FEATURE` |
| Codex `sandbox_mode` | `workspace-write` | 可选 `read-only` / `workspace-write` / `danger-full-access` |
| Codex 网络访问 | 关闭 | `nativeOptions.network_access=true` 时开启 |
| Codex 审批 | 未提供 `approvalHandler` 时一律拒绝 | 同时发出 `approval_requested` 事件，`decision` 为 `decline` |
| `bypassPermissions` / `danger-full-access` | 不作为默认值 | 显式指定时输出 WARN 日志 |

Agent 原生选项（`nativeOptions` 或配置 `native_options`）：Claude Code 支持 `max_turns`、`add_dirs`、`setting_sources`、`include_partial_messages`，本机 CLI 不支持对应参数时报 `SDK_UNSUPPORTED_FEATURE`；Codex 支持 `approval_policy`（默认 `on-request`）、`effort`、`network_access`、`config`。其他键报 `SDK_UNSUPPORTED_FEATURE`。

### 自定义配置来源
实现 `ConfigProvider` 即可接入数据库等配置来源。`load()` 返回与配置文件结构相同的部分配置树，只需包含要覆盖的字段。`reload()` 重新执行全部来源的加载与校验，失败时保留旧配置并抛出 `CONFIG_INVALID`；成功后旧客户端在进行中的调用结束后关闭。
```java
import io.github.charleshyf.commonllm.CommonLlm;
import io.github.charleshyf.commonllm.CommonLlmOptions;
import io.github.charleshyf.commonllm.config.ConfigProvider;

import java.nio.file.Path;
import java.util.Map;

public class CustomConfigSource {

	public static void main(String[] args) {
		ConfigProvider databaseProvider = () -> Map.of(
			"providers", Map.of(
				"openai", Map.of(
					"api_key", System.getenv().getOrDefault("OPENAI_API_KEY", ""),
					"default_model", System.getenv().getOrDefault("OPENAI_MODEL", ""))));

		try (CommonLlm commonLlm = CommonLlm.create(new CommonLlmOptions()
			.configPath(Path.of("common-llm.yaml"))
			.configProvider(databaseProvider)
			.overrides(Map.of("common", Map.of("max_retries", 3))))) {
			System.out.println(commonLlm.getConfig());
			commonLlm.reload();
		}
	}
}
```

`examples/DatabaseConfigProviderExample.java` 演示了把配置表的行映射为 `ConfigProvider`（JDBC 读取加内存实现）。

### 访问原生客户端
每个 `ModelClient` 的 `nativeClient()` 返回底层官方 SDK 客户端，用于调用本库尚未统一的能力：OpenAI 为 `com.openai.client.OpenAIClient`，Anthropic 为 `com.anthropic.client.AnthropicClient`，Gemini 为 `com.google.genai.Client`。
```java
import com.openai.client.OpenAIClient;
import io.github.charleshyf.commonllm.CommonLlm;

public class NativeClient {

	public static void main(String[] args) {
		try (CommonLlm commonLlm = CommonLlm.create()) {
			OpenAIClient openAiClient = (OpenAIClient) commonLlm.model("openai").nativeClient();
			System.out.println(openAiClient.models().list().data().size());
		}
	}
}
```

## 配置
配置文件查找顺序：`CommonLlmOptions.configPath(...)` 显式指定，其次环境变量 `COMMON_LLM_CONFIG`，最后是当前工作目录下的 `common-llm.yaml`。扩展名为 `.json` 时按 JSON 解析，其余按 YAML 解析。三者都不存在且没有其他配置来源时报 `CONFIG_INVALID`。

优先级从高到低：运行时覆盖（`CommonLlmOptions.overrides`）、注入的 `ConfigProvider`、配置文件、字段默认值。合并按字段进行，高优先级来源中出现的字段覆盖低优先级同名字段，未出现的字段保留。

环境变量只通过引用生效，不会自动覆盖任何字段，且只替换字符串值、不替换键名：

| 写法 | 结果 |
| --- | --- |
| `${NAME}` | 环境变量 `NAME` 的值；不存在时为空字符串，必填字段为空时报错并写出变量名 |
| `${NAME:-default}` | `NAME` 不存在时取 `default` |
| `$${NAME}` | 字面量 `${NAME}`，不做替换 |

常用字段（完整示例见本目录 [`common-llm.example.yaml`](common-llm.example.yaml)）：

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `common.timeout_seconds` | 120 | 单次模型请求超时 |
| `common.connect_timeout_seconds` | 10 | 连接超时 |
| `common.max_retries` | 2 | 交给 SDK 的重试次数 |
| `providers.<实例名>.type` | 实例名 | `openai` / `anthropic` / `gemini`，实例名不是这三者之一时必填 |
| `providers.<实例名>.api` | `responses` | 仅 `type=openai`：`responses` / `chat_completions` |
| `providers.<实例名>.enabled` | `false` | |
| `providers.<实例名>.api_key` | 无 | 启用时必填 |
| `providers.<实例名>.base_url` | SDK 默认值 | |
| `providers.<实例名>.default_model` | 无 | 请求未指定 `model` 时使用 |
| `providers.<实例名>.native_options` | `{}` | 每次模型请求附加的原生参数；Anthropic 的 `max_tokens` 默认值写在这里；不用于图片生成 |
| `providers.<实例名>.default_image_model` | 无 | 图片模型；请求未指定 `model` 时使用 |
| `providers.<实例名>.image_api` | `images` | 仅 `type=openai`：`images` / `responses` |
| `providers.<实例名>.image_concurrency` | 1 | Responses 与 Gemini 路径 n 大于 1 时的循环并发数 |
| `providers.<实例名>.image_timeout_seconds` | 继承 `timeout_seconds` | 图片生成单次请求超时 |
| `agents.<claude_code\|codex>.enabled` | `false` | |
| `agents.*.auth_mode` | `cli_existing_session` | `api_key` / `cli_existing_session` / `vendor_supported` |
| `agents.*.api_key` | 无 | `auth_mode=api_key` 时必填 |
| `agents.*.executable_path` | `claude` / `codex` | 在 PATH 中查找，也可写绝对路径 |
| `agents.codex.home_directory` | 无 | `auth_mode=api_key` 时必填，作为独立的 `CODEX_HOME` |
| `agents.*.working_directory` | 无 | 请求未指定 `workingDirectory` 时使用 |
| `agents.*.timeout_seconds` | 600 | 整次运行总时长 |
| `agents.*.permission_mode` | `default` | Claude Code |
| `agents.*.allowed_tools` | `[]` | Claude Code 工具白名单 |
| `agents.*.sandbox_mode` | `workspace-write` | Codex |
| `agents.*.env_allowlist` | `[]` | 允许子进程继承的环境变量 |
| `agents.*.max_concurrent_runs` | 4 | 超出报 `RATE_LIMITED` |
| `agents.*.max_tool_output_chars` | 4000 | `tool_completed.output` 截断长度 |
| `agents.*.kill_grace_seconds` | 5 | 结束进程到强制结束的等待时间 |
| `agents.*.collect_events` | `false` | `run` 的结果是否附带全部事件 |

认证：Claude Code 在 `auth_mode=api_key` 时向子进程注入 `ANTHROPIC_API_KEY`；Codex 在 `auth_mode=api_key` 时使用独立的 `home_directory` 作为 `CODEX_HOME` 并经 App Server 登录，不改写用户 `~/.codex`。面向第三方用户的产品不得复用个人订阅的 CLI 登录，应使用 API key 并遵守厂商条款。

## 错误处理
- `generate` 失败时抛出 `CommonLlmException`（unchecked），`getErrorInfo()` 返回 `ErrorInfo`。
- `stream` 不抛异常，以唯一的 `error` 终止事件结束，Provider 不存在、配置非法等预检失败也一样。
- `generateImage` 失败时抛出 `CommonLlmException`；`streamImage` 不抛异常，以唯一的 `error` 终止事件结束。图片生成复用模型调用的错误分类：OpenAI 内容审核拒绝（如 `moderation_blocked`）为不可重试的 `PROVIDER_ERROR`；Gemini 响应中没有图片时为 `PROVIDER_ERROR`，`vendorCode` 为 `finishReason`（如 `IMAGE_SAFETY`）；输入图片无法识别 MIME 类型为 `CONFIG_INVALID`。`image(name)` 对 Anthropic 抛出 `SDK_UNSUPPORTED_FEATURE`。
- `runAgent` / `AgentClient.run` 不抛异常，返回 `status=failed` 且 `getError()` 非空的 `AgentResult`；`runAgentStream` / `AgentClient.runStream` 以唯一的 `failed` 终止事件结束。CLI 不存在、工作目录非法也走这条路径。
- `model(name)` / `agent(name)` 对未配置或未启用的实例抛出 `CONFIG_INVALID`。

```java
import io.github.charleshyf.commonllm.CommonLlm;
import io.github.charleshyf.commonllm.core.CommonLlmException;
import io.github.charleshyf.commonllm.core.ErrorInfo;
import io.github.charleshyf.commonllm.core.Message;
import io.github.charleshyf.commonllm.core.ModelRequest;

public class ErrorHandling {

	public static void main(String[] args) {
		try (CommonLlm commonLlm = CommonLlm.create()) {
			commonLlm.generate(ModelRequest.builder().provider("openai").addMessage(Message.user("你好")).build());
		} catch (CommonLlmException exception) {
			ErrorInfo error = exception.getErrorInfo();
			System.err.println(error.getCategory() + " retriable=" + error.isRetriable() + " status=" + error.getStatusCode() + " vendorCode=" + error.getVendorCode() + " requestId=" + error.getRequestId());
		}
	}
}
```

| 分类 | 触发条件 | 可重试 |
| --- | --- | --- |
| `CONFIG_INVALID` | 配置缺失、取值非法、`providerOptions` 键冲突 | 否 |
| `AUTH_MISSING` | 需要的 key 为空、CLI 未登录 | 否 |
| `AUTH_FAILED` | HTTP 401；Gemini 400 且详情含 `API_KEY_INVALID` | 否 |
| `PERMISSION_DENIED` | HTTP 403 | 否 |
| `MODEL_UNAVAILABLE` | HTTP 404 | 否 |
| `RATE_LIMITED` | HTTP 429（非额度类）、超过 `max_concurrent_runs` | 是 |
| `QUOTA_EXCEEDED` | HTTP 429 且 OpenAI 错误码为 `insufficient_quota` | 否 |
| `TIMEOUT` | HTTP 408、请求或运行超时 | 是 |
| `CANCELLED` | 调用方取消 | 否 |
| `NETWORK_ERROR` | 连接失败、连接中断 | 是 |
| `PROVIDER_ERROR` | 5xx（可重试）；其余 4xx 与 Agent 返回的失败结果（不可重试） | 视状态码 |
| `SDK_UNSUPPORTED_FEATURE` | 请求了 Provider、Agent 或本机 CLI 版本不支持的能力 | 否 |
| `CLI_NOT_FOUND` | 可执行文件不存在 | 否 |
| `CLI_VERSION_UNSUPPORTED` | CLI 版本低于最低支持版本 | 否 |
| `CLI_PROCESS_FAILED` | 非零退出且没有可解析的结果、输出超过 `max_output_bytes` | 否 |
| `CLI_PROTOCOL_ERROR` | JSONL / JSON-RPC 不符合协议、单行超过 `max_line_bytes`、握手失败 | 否 |
| `AGENT_APPROVAL_REQUIRED` | 操作需要审批但被拒绝或无法审批，导致运行失败 | 否 |
| `SANDBOX_DENIED` | 沙箱拒绝的操作导致运行失败 | 否 |
| `INTERNAL_ERROR` | 本库自身缺陷 | 否 |

重试交给各 SDK 的内置机制（`max_retries`），本库不再额外重试；流式请求收到首个事件后不重试。

## 依赖版本
| 依赖 | 版本 |
| --- | --- |
| Java（编译目标 `maven.compiler.release`） | 17 |
| `com.openai:openai-java` | 4.105.0 |
| `com.anthropic:anthropic-java` | 3.8.0 |
| `com.google.genai:google-genai` | 2.3.0 |
| `com.fasterxml.jackson.core:jackson-databind` | 2.23.0 |
| `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml` | 2.23.0 |
| `org.slf4j:slf4j-api` | 2.0.20 |
| `org.apache.maven.plugins:maven-compiler-plugin` | 3.16.0 |
| `org.apache.maven.plugins:maven-surefire-plugin` | 3.6.1 |

本库只依赖 SLF4J 接口，日志绑定（如 Logback）由使用方提供；没有绑定时 SLF4J 输出一次提示并静默丢弃日志。

## 已知限制
- Anthropic 已弃用 `temperature`，`anthropic-java` 3.8.0 的 `MessageCreateParams` 也没有该字段：请求设置 `temperature` 时报 `SDK_UNSUPPORTED_FEATURE`，确需传入可通过 `providerOptions` 透传。
- Gemini Java SDK 不支持单独设置连接超时，`connect_timeout_seconds` 对 Gemini 不生效；重试次数按 `attempts = max_retries + 1` 映射（`attempts` 含首次请求）。
- Gemini 的 URL 图片必须带 MIME 类型，`ContentPart.imageUrl` 不带 `mimeType`，本库按扩展名（png / jpg / jpeg / gif / webp）推断，推断不了时报 `CONFIG_INVALID`，此时请改用 `ContentPart.imageBase64`。
- Chat Completions 的输出上限映射为 `max_completion_tokens`；兼容端点只认 `max_tokens` 时请经 `providerOptions` 传入。
- Gemini 图片生成不支持蒙版、部分图片（`partialImages`）、指定输出格式与压缩率、`size` / `quality` / `background`，也不支持 URL 输入图片；`google-genai` 2.3.0 的 `ImageConfig` 虽有 `outputMimeType` 等字段，本库按设计不映射。Gemini 不支持原生多张，由库内循环完成。
- OpenAI Images API 编辑多张图片时，SDK 以同一个 multipart 字段上传图片列表，各图使用第一张图片的 MIME 类型。
- 图片生成不使用配置中的 `native_options`，厂商原生参数请经请求的 `providerOptions` 传入。
- Claude Code 走 `claude -p` 子进程，不支持运行中审批回调，中断即结束子进程；本机 `claude -p --help` 没有 `--max-turns` 时，`max_turns` 选项报 `SDK_UNSUPPORTED_FEATURE`。Claude Code 不提供文件变更事件，不发出 `file_changed`。
- Codex 没有成本数据，`AgentUsage.costUsd` 为 `null`。
- Java 无法为子进程创建独立进程组，超时或取消时通过 `ProcessHandle.descendants()` 逐个结束子进程及其后代。

## 示例
| 示例 | 用途 |
| --- | --- |
| `TextGenerationExample` | 普通文本生成，参数为 Provider 实例名（默认 `openai`） |
| `StreamingGenerationExample` | 流式生成，参数为 Provider 实例名（默认 `anthropic`） |
| `CodingAgentExample` | Coding Agent 流式执行与审批处理，参数为 Agent 名（默认 `codex`）与工作目录 |
| `ProviderSwitchExample` | 同时配置官方 OpenAI 与本地 vLLM 兼容端点并按实例名切换 |
| `DatabaseConfigProviderExample` | 把配置表映射为 `ConfigProvider` 并演示 `reload()` |
| `ImageGenerationExample` | 文生图并把图片保存到文件，参数为 Provider 实例名（默认 `openai`）与输出目录（默认 `generated-images`） |

先安装库，再编译示例并导出运行时类路径：
```bash
mvn -q install
mvn -q -f examples/pom.xml compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
```

在 `java/` 目录下运行任一示例（其余示例替换类名即可），示例从当前目录的 `common-llm.yaml` 读取配置：
```bash
java -cp "examples/target/classes:$(cat examples/target/classpath.txt)" io.github.charleshyf.commonllm.examples.TextGenerationExample openai
```

## 许可证
本项目基于 [MIT License](../LICENSE) 开源。
