package io.github.charleshyf.commonllm.examples;

import io.github.charleshyf.commonllm.CommonLlm;
import io.github.charleshyf.commonllm.core.AgentEvent;
import io.github.charleshyf.commonllm.core.AgentEventType;
import io.github.charleshyf.commonllm.core.AgentRequest;
import io.github.charleshyf.commonllm.core.AgentStream;
import io.github.charleshyf.commonllm.core.ApprovalDecision;
import io.github.charleshyf.commonllm.core.ApprovalKind;

/**
 * Coding Agent 执行示例：流式运行 Codex 或 Claude Code 并处理审批
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class CodingAgentExample {

	private CodingAgentExample() {
	}

	public static void main(String[] arguments) {
		String agent = arguments.length > 0 ? arguments[0] : "codex";
		String workingDirectory = arguments.length > 1 ? arguments[1] : System.getProperty("user.dir");
		AgentRequest.Builder requestBuilder = AgentRequest.builder()
			.agent(agent)
			.prompt("阅读 README 并用一句话总结这个项目")
			.workingDirectory(workingDirectory);

		// 只有 Codex 支持运行中审批；示例只放行命令类审批，其余一律拒绝
		if ("codex".equals(agent)) {
			requestBuilder.approvalHandler(approvalRequest -> approvalRequest.getKind() == ApprovalKind.COMMAND ? ApprovalDecision.APPROVE : ApprovalDecision.DECLINE);
		}

		try (CommonLlm commonLlm = CommonLlm.create(); AgentStream stream = commonLlm.runAgentStream(requestBuilder.build())) {
			while (stream.hasNext()) {
				AgentEvent event = stream.next();

				if (event.getType() == AgentEventType.MESSAGE && Boolean.FALSE.equals(event.getField("isDelta"))) {
					System.out.println("[assistant] " + event.getText());
				} else if (event.getType() == AgentEventType.TOOL_STARTED) {
					System.out.println("[tool] " + event.getField("toolName") + " " + event.getField("input"));
				} else if (event.getType() == AgentEventType.COMPLETED) {
					System.out.println("完成，sessionId=" + event.getResult().getSessionId() + " usage=" + event.getResult().getUsage());
				} else if (event.getType() == AgentEventType.FAILED) {
					System.err.println("运行失败：" + event.getError());
				}
			}
		}
	}
}
