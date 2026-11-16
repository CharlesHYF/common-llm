# -*- coding: utf-8 -*-
"""
Provider 与 Agent 适配层使用的运行参数，由配置模块转换得到
创建日期：2026-11-16
修改日期：2026-11-16
"""
from dataclasses import dataclass, field
from typing import Any, Final

from common_llm.core.redaction import MASK_TEXT

# 配置字段默认值集中在此处，config 模块与适配层共用
DEFAULT_TIMEOUT_SECONDS = 120.0
DEFAULT_CONNECT_TIMEOUT_SECONDS = 10.0
DEFAULT_MAX_RETRIES = 2
DEFAULT_IMAGE_API: Final = "images"
DEFAULT_IMAGE_CONCURRENCY = 1
DEFAULT_AGENT_TIMEOUT_SECONDS = 600.0
DEFAULT_MAX_CONCURRENT_RUNS = 4
DEFAULT_MAX_OUTPUT_BYTES = 10485760
DEFAULT_MAX_LINE_BYTES = 8388608
DEFAULT_MAX_STDERR_BYTES = 65536
DEFAULT_MAX_BUFFERED_EVENTS = 10000
DEFAULT_MAX_TOOL_OUTPUT_CHARS = 4000
DEFAULT_KILL_GRACE_SECONDS = 5.0


@dataclass
class ProviderSettings:
    """
    单个 Provider 实例的运行参数
    """

    name: str
    provider_type: str
    api: str = "responses"
    api_key: str | None = None
    base_url: str | None = None
    default_model: str | None = None
    timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS
    connect_timeout_seconds: float = DEFAULT_CONNECT_TIMEOUT_SECONDS
    max_retries: int = DEFAULT_MAX_RETRIES
    native_options: dict[str, Any] = field(default_factory=dict)
    default_image_model: str | None = None
    image_api: str = DEFAULT_IMAGE_API
    image_concurrency: int = DEFAULT_IMAGE_CONCURRENCY
    image_timeout_seconds: float | None = None

    def __repr__(self) -> str:
        masked_key = MASK_TEXT if self.api_key else None
        return (
            f"ProviderSettings(name={self.name!r}, provider_type={self.provider_type!r}, api={self.api!r}, "
            f"api_key={masked_key!r}, base_url={self.base_url!r}, default_model={self.default_model!r})"
        )


@dataclass
class AgentSettings:
    """
    单个 Agent 的运行参数
    """

    name: str
    auth_mode: str = "cli_existing_session"
    api_key: str | None = None
    executable_path: str | None = None
    home_directory: str | None = None
    working_directory: str | None = None
    timeout_seconds: float = DEFAULT_AGENT_TIMEOUT_SECONDS
    permission_mode: str = "default"
    allowed_tools: list[str] = field(default_factory=list)
    sandbox_mode: str = "workspace-write"
    env_allowlist: list[str] = field(default_factory=list)
    max_concurrent_runs: int = DEFAULT_MAX_CONCURRENT_RUNS
    max_output_bytes: int = DEFAULT_MAX_OUTPUT_BYTES
    max_line_bytes: int = DEFAULT_MAX_LINE_BYTES
    max_stderr_bytes: int = DEFAULT_MAX_STDERR_BYTES
    max_buffered_events: int = DEFAULT_MAX_BUFFERED_EVENTS
    max_tool_output_chars: int = DEFAULT_MAX_TOOL_OUTPUT_CHARS
    kill_grace_seconds: float = DEFAULT_KILL_GRACE_SECONDS
    collect_events: bool = False
    native_options: dict[str, Any] = field(default_factory=dict)

    def __repr__(self) -> str:
        masked_key = MASK_TEXT if self.api_key else None
        return (
            f"AgentSettings(name={self.name!r}, auth_mode={self.auth_mode!r}, api_key={masked_key!r}, "
            f"executable_path={self.executable_path!r}, working_directory={self.working_directory!r})"
        )
