# -*- coding: utf-8 -*-
"""
流式生成示例：逐个打印文本增量，并在结束时输出完成原因或错误分类
创建日期：2026-11-16
修改日期：2026-11-16
"""
import asyncio
import sys

from common_llm import CommonLlm, Message, ModelCompletedEvent, ModelErrorEvent, ModelRequest, ModelTextDeltaEvent


async def main(provider: str) -> None:
    llm = CommonLlm.create()
    request = ModelRequest(provider=provider, messages=[Message.from_text("user", "写一首关于秋天的四行短诗")])
    try:
        async for event in llm.stream(request):
            if isinstance(event, ModelTextDeltaEvent):
                print(event.text, end="", flush=True)
            elif isinstance(event, ModelCompletedEvent):
                print(f"\n完成：{event.response.finish_reason}")
            elif isinstance(event, ModelErrorEvent):
                print(f"\n失败：{event.error.category.value} {event.error.message}")
    finally:
        await llm.close()


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1] if len(sys.argv) > 1 else "anthropic"))
