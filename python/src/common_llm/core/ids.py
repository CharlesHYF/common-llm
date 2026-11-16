# -*- coding: utf-8 -*-
"""
traceId 与 runId 的生成器类型及默认实现，测试可注入固定值
创建日期：2026-11-16
修改日期：2026-11-16
"""
import uuid
from collections.abc import Callable

IdFactory = Callable[[], str]


def generate_uuid() -> str:
    """
    生成随机 UUID 字符串
    """
    return str(uuid.uuid4())


def resolve_trace_id(trace_id: str | None, factory: IdFactory | None) -> str:
    """
    请求带 traceId 时原样使用，否则调用生成器
    """
    if trace_id:
        return trace_id

    if factory is not None:
        return factory()

    return generate_uuid()
