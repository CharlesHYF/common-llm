package io.github.charleshyf.commonllm.examples;

import io.github.charleshyf.commonllm.CommonLlm;
import io.github.charleshyf.commonllm.CommonLlmOptions;
import io.github.charleshyf.commonllm.core.CommonLlmException;
import io.github.charleshyf.commonllm.core.Message;
import io.github.charleshyf.commonllm.core.ModelRequest;
import io.github.charleshyf.commonllm.core.ProviderCapability;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 配置切换示例：同时配置官方 OpenAI 与 OpenAI 兼容端点，按实例名切换
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class ProviderSwitchExample {

	private ProviderSwitchExample() {
	}

	public static void main(String[] arguments) {
		Map<String, Object> officialOpenAi = new LinkedHashMap<>();
		officialOpenAi.put("enabled", true);
		officialOpenAi.put("api_key", System.getenv("OPENAI_API_KEY"));
		officialOpenAi.put("default_model", System.getenv("OPENAI_MODEL"));
		Map<String, Object> localVllm = new LinkedHashMap<>();
		localVllm.put("type", "openai");
		localVllm.put("api", "chat_completions");
		localVllm.put("enabled", true);
		localVllm.put("api_key", "none");
		localVllm.put("base_url", "http://127.0.0.1:8000/v1");
		localVllm.put("default_model", "local-model");
		Map<String, Object> providers = new LinkedHashMap<>();
		providers.put("openai", officialOpenAi);
		providers.put("local_vllm", localVllm);

		try (CommonLlm commonLlm = CommonLlm.create(new CommonLlmOptions().overrides(Map.of("providers", providers)))) {
			for (ProviderCapability capability : commonLlm.listProviders()) {
				ModelRequest request = ModelRequest.builder().provider(capability.getProvider()).addMessage(Message.user("只回复 ok")).build();

				try {
					System.out.println(capability.getProvider() + ": " + commonLlm.generate(request).getText());
				} catch (CommonLlmException exception) {
					System.err.println(capability.getProvider() + " 调用失败：" + exception.getErrorInfo().getCategory());
				}
			}
		}
	}
}
