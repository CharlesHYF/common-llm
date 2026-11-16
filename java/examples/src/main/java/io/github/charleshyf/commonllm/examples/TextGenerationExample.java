package io.github.charleshyf.commonllm.examples;

import io.github.charleshyf.commonllm.CommonLlm;
import io.github.charleshyf.commonllm.core.CommonLlmException;
import io.github.charleshyf.commonllm.core.Message;
import io.github.charleshyf.commonllm.core.ModelRequest;
import io.github.charleshyf.commonllm.core.ModelResponse;

/**
 * 普通文本生成示例：读取 common-llm.yaml 并调用 openai 实例
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class TextGenerationExample {

	private TextGenerationExample() {
	}

	public static void main(String[] arguments) {
		String provider = arguments.length > 0 ? arguments[0] : "openai";

		try (CommonLlm commonLlm = CommonLlm.create()) {
			ModelRequest request = ModelRequest.builder()
				.provider(provider)
				.addMessage(Message.system("你是天气助手"))
				.addMessage(Message.user("上海今天天气如何"))
				.build();
			ModelResponse response = commonLlm.generate(request);
			System.out.println(response.getText());
			System.out.println("finishReason=" + response.getFinishReason().getValue() + " usage=" + response.getUsage());
		} catch (CommonLlmException exception) {
			System.err.println("调用失败：" + exception.getErrorInfo());
		}
	}
}
