# -*- coding: utf-8 -*-
"""
数据库配置示例：用标准库 sqlite3 的配置表实现 ConfigProvider，并演示 reload
创建日期：2026-11-16
修改日期：2026-11-16
"""
import asyncio
import json
import os
import sqlite3
from collections.abc import Mapping
from typing import Any

from common_llm import CommonLlm, CommonLlmError


class SqliteConfigProvider:
    """
    从 llm_provider_config 表读取 Provider 实例配置，api_key 列存放环境变量名作为凭证引用
    """

    def __init__(self, connection: sqlite3.Connection) -> None:
        self.connection = connection

    def load(self) -> Mapping[str, Any]:
        providers: dict[str, Any] = {}
        rows = self.connection.execute("SELECT name, provider_type, enabled, api_key_ref, default_model, native_options FROM llm_provider_config")
        for name, provider_type, enabled, api_key_ref, default_model, native_options in rows:
            # 凭证引用在 load 中解析为明文，核心库不接触凭证存储
            providers[name] = {
                "type": provider_type,
                "enabled": bool(enabled),
                "api_key": os.environ.get(api_key_ref, ""),
                "default_model": default_model,
                "native_options": json.loads(native_options or "{}"),
            }

        return {"providers": providers}


def create_demo_database() -> sqlite3.Connection:
    """
    创建内存数据库并写入一条 Anthropic 配置
    """
    connection = sqlite3.connect(":memory:")
    connection.execute(
        "CREATE TABLE llm_provider_config (name TEXT PRIMARY KEY, provider_type TEXT, enabled INTEGER, api_key_ref TEXT, default_model TEXT, native_options TEXT)"
    )
    connection.execute(
        "INSERT INTO llm_provider_config VALUES (?, ?, ?, ?, ?, ?)",
        ("anthropic", "anthropic", 1, "ANTHROPIC_API_KEY", os.environ.get("ANTHROPIC_MODEL", "model-from-db"), json.dumps({"max_tokens": 1024})),
    )
    connection.commit()
    return connection


async def main() -> None:
    connection = create_demo_database()
    try:
        llm = CommonLlm.create(SqliteConfigProvider(connection))
    except CommonLlmError as error:
        print(f"配置无效：{error}")
        return

    print(llm.config)
    connection.execute("UPDATE llm_provider_config SET default_model = ? WHERE name = ?", ("model-after-reload", "anthropic"))
    connection.commit()
    await llm.reload()
    print(llm.config.providers["anthropic"].default_model)
    await llm.close()


if __name__ == "__main__":
    asyncio.run(main())
