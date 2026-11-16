package io.github.charleshyf.commonllm.examples;

import io.github.charleshyf.commonllm.CommonLlm;
import io.github.charleshyf.commonllm.core.Message;
import io.github.charleshyf.commonllm.core.ModelEvent;
import io.github.charleshyf.commonllm.core.ModelEventType;
import io.github.charleshyf.commonllm.core.ModelRequest;
import io.github.charleshyf.commonllm.core.ModelStream;

/**
 * 流式生成示例：逐个读取 ModelEvent 并打印文本增量
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class StreamingGenerationExample {

	private StreamingGenerationExample() {
	}

	public static void main(String[] arguments) {
		String provider = arguments.length > 0 ? arguments[0] : "anthropic";

		try (CommonLlm commonLlm = CommonLlm.create()) {
			ModelRequest request = ModelRequest.builder().provider(provider).addMessage(Message.user("用三句话介绍上海")).build();

			try (ModelStream stream = commonLlm.stream(request)) {
				while (stream.hasNext()) {
					ModelEvent event = stream.next();

					if (event.getType() == ModelEventType.TEXT_DELTA) {
						System.out.print(event.getText());
					} else if (event.getType() == ModelEventType.COMPLETED) {
						System.out.println();
						System.out.println("usage=" + event.getResponse().getUsage());
					} else if (event.getType() == ModelEventType.ERROR) {
						System.err.println("流式调用失败：" + event.getError());
					}
				}
			}
		}
	}
}
