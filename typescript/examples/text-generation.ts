/**
 * 示例：从 common-llm.yaml 加载配置并调用模型生成文本
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

import { CommonLlm, createConsoleLogger, isCommonLlmError } from "@charleshyf/common-llm";

const provider = process.argv[2] ?? "openai";
const llm = await CommonLlm.create({
	logger: createConsoleLogger(),
});

try {
	const response = await llm.generate({
		provider,
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
						text: "用一句话介绍上海",
					},
				],
			},
		],
	});
	console.log(response.text);
	console.log(JSON.stringify(response.usage));
} catch (error) {
	if (isCommonLlmError(error)) {
		console.error(`${error.category}: ${error.message}`);
	} else {
		throw error;
	}
} finally {
	await llm.close();
}
