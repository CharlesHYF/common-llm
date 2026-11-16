# -*- coding: utf-8 -*-
"""
文本生成示例：从 common-llm.yaml 加载配置并调用指定 Provider 完成一次非流式生成
创建日期：2026-11-16
修改日期：2026-11-16
"""
import asyncio
import sys

from common_llm import CommonLlm, CommonLlmError, Message, ModelRequest


async def main(provider: str) -> None:
    llm = CommonLlm.create()
    try:
        response = await llm.generate(
            ModelRequest(
                provider=provider,
                messages=[
                    Message.from_text("system", "你是简洁的助手"),
                    Message.from_text("user", "用一句话介绍上海"),
                ],
            )
        )
        print(response.text)
        print(response.usage.to_contract_dict())
    except CommonLlmError as error:
        print(f"调用失败：{error}")
    finally:
        await llm.close()


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1] if len(sys.argv) > 1 else "openai"))
