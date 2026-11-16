# -*- coding: utf-8 -*-
"""
基于标准库 logging 的调用日志记录，默认不记录 prompt、响应正文与密钥
创建日期：2026-11-16
修改日期：2026-11-16
"""
import logging
from typing import Any

from common_llm.core.redaction import redact_text

LOGGER_NAME = "common_llm"
LOG_LEVELS = {
    "DEBUG": logging.DEBUG,
    "INFO": logging.INFO,
    "WARN": logging.WARNING,
    "ERROR": logging.ERROR,
}


def get_logger() -> logging.Logger:
    """
    获取本库统一使用的 logger
    """
    return logging.getLogger(LOGGER_NAME)


def apply_log_level(level_name: str) -> None:
    """
    按配置的 log_level 设置本库 logger 的级别
    """
    get_logger().setLevel(LOG_LEVELS.get(level_name, logging.INFO))


def format_fields(fields: dict[str, Any]) -> str:
    """
    把日志字段组装为 key=value 文本，值统一脱敏
    """
    pairs: list[str] = []
    for key, value in fields.items():
        if value is None:
            continue

        pairs.append(f"{key}={redact_text(str(value))}")

    return " ".join(pairs)


def log_model_call(
    *,
    provider: str,
    model: str | None,
    duration_ms: float,
    trace_id: str,
    category: str | None,
    usage: dict[str, Any] | None,
) -> None:
    """
    记录一次模型调用的耗时、错误分类与用量
    """
    fields: dict[str, Any] = {
        "provider": provider,
        "model": model,
        "duration_ms": round(duration_ms),
        "trace_id": trace_id,
        "category": category,
        "usage": usage,
    }
    level = logging.INFO
    if category is not None:
        level = logging.WARNING

    get_logger().log(level, "模型调用结束 %s", format_fields(fields))


def log_agent_run(
    *,
    agent: str,
    run_id: str,
    trace_id: str,
    duration_ms: float,
    status: str,
    category: str | None,
    usage: dict[str, Any] | None,
) -> None:
    """
    记录一次 Agent run 的耗时、状态、错误分类与用量
    """
    fields: dict[str, Any] = {
        "agent": agent,
        "run_id": run_id,
        "trace_id": trace_id,
        "duration_ms": round(duration_ms),
        "status": status,
        "category": category,
        "usage": usage,
    }
    level = logging.INFO
    if category is not None:
        level = logging.WARNING

    get_logger().log(level, "Agent run 结束 %s", format_fields(fields))


def log_dangerous_mode(agent: str, mode: str) -> None:
    """
    使用 bypassPermissions 或 danger-full-access 时输出 WARN 日志
    """
    get_logger().warning("Agent %s 使用了高权限模式 %s，仅在显式指定时允许", agent, mode)
