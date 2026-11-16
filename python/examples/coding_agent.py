# -*- coding: utf-8 -*-
"""
Coding Agent 示例：在工作目录中执行一次 run，打印事件流，审批请求交给自定义处理器
创建日期：2026-11-16
修改日期：2026-11-16
"""
import asyncio
import sys

from common_llm import (
    AgentApprovalRequestedEvent,
    AgentCompletedEvent,
    AgentFailedEvent,
    AgentMessageEvent,
    AgentRequest,
    AgentToolStartedEvent,
    ApprovalDecision,
    ApprovalRequest,
    CommonLlm,
)


async def approve_read_only(approval: ApprovalRequest) -> ApprovalDecision:
    """
    只放行只读的 Read 工具，其余审批一律拒绝
    """
    if approval.kind == "tool" and approval.summary == "Read":
        return "approve"

    return "decline"


async def main(agent: str, working_directory: str) -> None:
    llm = CommonLlm.create()
    request = AgentRequest(agent=agent, prompt="读取 README.md 并用一句话概括", working_directory=working_directory)
    try:
        async for event in llm.agent(agent).run_stream(request, approval_handler=approve_read_only):
            if isinstance(event, AgentMessageEvent) and not event.is_delta:
                print(f"[assistant] {event.text}")
            elif isinstance(event, AgentToolStartedEvent):
                print(f"[tool] {event.tool_name}")
            elif isinstance(event, AgentApprovalRequestedEvent):
                print(f"[approval] {event.summary} -> {event.decision}")
            elif isinstance(event, AgentCompletedEvent):
                print(f"[completed] session={event.result.session_id}")
            elif isinstance(event, AgentFailedEvent):
                print(f"[failed] {event.error.category.value} {event.error.message}")
    finally:
        await llm.close()


if __name__ == "__main__":
    agent_name = sys.argv[1] if len(sys.argv) > 1 else "claude_code"
    directory = sys.argv[2] if len(sys.argv) > 2 else "."
    asyncio.run(main(agent_name, directory))
