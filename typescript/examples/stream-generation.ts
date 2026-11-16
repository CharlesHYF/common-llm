/**
 * 示例：流式生成文本并按事件类型处理增量、用量与终止事件
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

import { CommonLlm } from "@charleshyf/common-llm";

const provider = process.argv[2] ?? "anthropic";
const llm = await CommonLlm.create();
const controller = new AbortController();

try {
	for await (const event of llm.stream({
		provider,
		maxOutputTokens: 512,
		messages: [
			{
				role: "user",
				content: [
					{
						type: "text",
						text: "写一首关于秋天的四行短诗",
					},
				],
			},
		],
	}, {
		signal: controller.signal,
	})) {
		if (event.type === "text_delta") {
			process.stdout.write(event.text);
		} else if (event.type === "usage") {
			console.log(`\n用量：${JSON.stringify(event.usage)}`);
		} else if (event.type === "error") {
			console.error(`\n失败：${event.error.category} ${event.error.message}`);
		}
	}
} finally {
	await llm.close();
}
