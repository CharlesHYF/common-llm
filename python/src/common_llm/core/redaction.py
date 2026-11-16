# -*- coding: utf-8 -*-
"""
密钥与令牌的脱敏工具，用于日志、异常消息与配置输出
创建日期：2026-11-16
修改日期：2026-11-16
"""
import re
import threading
from collections.abc import Mapping
from typing import Any

MASK_TEXT = "***"
# 少于该长度的值不登记为密钥，避免把 "none" 之类的占位值全局替换
MIN_SECRET_LENGTH = 6
SECRET_FIELD_NAMES = ("api_key", "apiKey", "token", "access_token", "password", "secret")
SECRET_PATTERNS = (
    re.compile(r"sk-[A-Za-z0-9_\-*]{6,}"),
    re.compile(r"AIza[0-9A-Za-z_\-]{20,}"),
    re.compile(r"(?i)bearer\s+[A-Za-z0-9._\-]+"),
    re.compile(r"(?i)(x-api-key|x-goog-api-key)\s*[:=]\s*\S+"),
)

_registered_secrets: set[str] = set()
_registry_lock = threading.Lock()


def register_secret(secret: str | None) -> None:
    """
    登记一个需要脱敏的明文密钥，过短的值跳过
    """
    if secret is None or len(secret) < MIN_SECRET_LENGTH:
        return

    with _registry_lock:
        _registered_secrets.add(secret)


def redact_text(text: str | None) -> str | None:
    """
    把文本中已登记的密钥与常见密钥格式替换为 ***
    """
    if text is None:
        return None

    with _registry_lock:
        secrets = sorted(_registered_secrets, key=len, reverse=True)

    redacted = text
    for secret in secrets:
        redacted = redacted.replace(secret, MASK_TEXT)

    for pattern in SECRET_PATTERNS:
        redacted = pattern.sub(MASK_TEXT, redacted)

    return redacted


def redact_mapping(values: Mapping[str, Any]) -> dict[str, Any]:
    """
    复制字典并把密钥字段替换为 ***，嵌套字典递归处理
    """
    redacted: dict[str, Any] = {}
    for key, value in values.items():
        if key in SECRET_FIELD_NAMES and value:
            redacted[key] = MASK_TEXT
            continue

        if isinstance(value, Mapping):
            redacted[key] = redact_mapping(value)
            continue

        redacted[key] = value

    return redacted
