# -*- coding: utf-8 -*-
"""
配置切换示例：同时配置官方 OpenAI 与一个 OpenAI 兼容端点，请求里用实例名选择
创建日期：2026-11-16
修改日期：2026-11-16
"""
import asyncio
import os

from common_llm import CommonLlm, CommonLlmError, Message, ModelRequest

PROVIDER_OVERRIDES = {
    "providers": {
        "openai": {
            "enabled": True,
            "api_key": os.environ.get("OPENAI_API_KEY", ""),
            "default_model": os.environ.get("OPENAI_MODEL", ""),
        },
        "local_vllm": {
            "type": "openai",
            "api": "chat_completions",
            "enabled": True,
            "api_key": os.environ.get("LOCAL_VLLM_API_KEY", "none"),
            "base_url": os.environ.get("LOCAL_VLLM_BASE_URL", "http://127.0.0.1:8000/v1"),
            "default_model": os.environ.get("LOCAL_VLLM_MODEL", ""),
        },
    },
}


async def main() -> None:
    try:
        llm = CommonLlm.create(overrides=PROVIDER_OVERRIDES)
    except CommonLlmError as error:
        print(f"配置无效：{error}")
        return

    try:
        for capability in llm.list_providers():
            print(f"{capability.provider}: {', '.join(capability.features)}")

        for provider in ("openai", "local_vllm"):
            try:
                response = await llm.generate(ModelRequest(provider=provider, messages=[Message.from_text("user", "只回复 OK")]))
                print(f"{provider}: {response.text}")
            except CommonLlmError as error:
                print(f"{provider} 调用失败：{error.category.value}")
    finally:
        await llm.close()


if __name__ == "__main__":
    asyncio.run(main())
