/**
 * 示例：调用 Coding Agent 在工作目录内执行任务并打印事件
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

import { CommonLlm } from "@charleshyf/common-llm";

import type { AgentName } from "@charleshyf/common-llm";

const agentName = (process.argv[2] ?? "claude_code") as AgentName;
const workingDirectory = process.argv[3] ?? process.cwd();
const llm = await CommonLlm.create();
const agent = llm.agent(agentName);

try {
	const capability = await agent.capabilities();
	console.log(`使用 ${capability.agent} ${capability.version ?? ""}，能力：${capability.features.join(", ")}`);

	for await (const event of agent.runStream({
		agent: agentName,
		prompt: "列出当前目录的文件并说明 README 的内容",
		workingDirectory,
		timeoutSeconds: 300,
	})) {
		if (event.type === "message" && !event.isDelta) {
			console.log(`[assistant] ${event.text}`);
		} else if (event.type === "tool_started") {
			console.log(`[tool] ${event.toolName}`);
		} else if (event.type === "approval_requested") {
			console.log(`[approval] ${event.summary} -> ${event.decision}`);
		} else if (event.type === "completed") {
			console.log(`完成，sessionId=${event.result.sessionId ?? ""}`);
		} else if (event.type === "failed") {
			console.error(`失败：${event.error.category} ${event.error.message}`);
		}
	}
} finally {
	await llm.close();
}
