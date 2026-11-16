/**
 * 示例：同时配置官方 OpenAI 与 OpenAI 兼容端点，按实例名切换 Provider
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

import { CommonLlm } from "@charleshyf/common-llm";

const llm = await CommonLlm.create({
	overrides: {
		providers: {
			openai: {
				enabled: true,
				api_key: process.env.OPENAI_API_KEY ?? "",
				default_model: process.env.OPENAI_MODEL ?? "",
			},
			deepseek: {
				type: "openai",
				api: "chat_completions",
				enabled: true,
				api_key: process.env.DEEPSEEK_API_KEY ?? "",
				base_url: "https://api.deepseek.com/v1",
				default_model: process.env.DEEPSEEK_MODEL ?? "",
			},
		},
	},
});

try {
	for (const capability of llm.listProviders()) {
		const response = await llm.generate({
			provider: capability.provider,
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
		console.log(`${capability.provider}: ${response.text}`);
	}
} finally {
	await llm.close();
}
