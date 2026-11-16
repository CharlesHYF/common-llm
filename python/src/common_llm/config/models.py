# -*- coding: utf-8 -*-
"""
配置文件结构与字段默认值
创建日期：2026-11-16
修改日期：2026-11-16
"""
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field

from common_llm.core.redaction import MASK_TEXT, redact_mapping
from common_llm.core.settings import (
    DEFAULT_AGENT_TIMEOUT_SECONDS,
    DEFAULT_CONNECT_TIMEOUT_SECONDS,
    DEFAULT_KILL_GRACE_SECONDS,
    DEFAULT_MAX_BUFFERED_EVENTS,
    DEFAULT_MAX_CONCURRENT_RUNS,
    DEFAULT_MAX_LINE_BYTES,
    DEFAULT_MAX_OUTPUT_BYTES,
    DEFAULT_MAX_RETRIES,
    DEFAULT_MAX_STDERR_BYTES,
    DEFAULT_MAX_TOOL_OUTPUT_CHARS,
    DEFAULT_TIMEOUT_SECONDS,
)

PROVIDER_TYPES = ("openai", "anthropic", "gemini")
AGENT_NAMES = ("claude_code", "codex")


class ConfigSection(BaseModel):
    """
    配置结构基类，拒绝未知字段，repr 与 str 对密钥字段输出 ***
    """

    model_config = ConfigDict(extra="forbid")

    def to_redacted_dict(self) -> dict[str, Any]:
        """
        转为字典并把密钥字段替换为 ***
        """
        return redact_mapping(self.model_dump())

    def __repr__(self) -> str:
        return f"{type(self).__name__}({self.to_redacted_dict()!r})"

    def __str__(self) -> str:
        return self.__repr__()


class CommonConfig(ConfigSection):
    """
    common 段
    """

    timeout_seconds: float = Field(default=DEFAULT_TIMEOUT_SECONDS, gt=0)
    connect_timeout_seconds: float = Field(default=DEFAULT_CONNECT_TIMEOUT_SECONDS, gt=0)
    max_retries: int = Field(default=DEFAULT_MAX_RETRIES, ge=0)
    log_level: Literal["DEBUG", "INFO", "WARN", "ERROR"] = "INFO"


class ProviderConfig(ConfigSection):
    """
    providers.<实例名> 段
    """

    type: Literal["openai", "anthropic", "gemini"] | None = None
    api: Literal["responses", "chat_completions"] = "responses"
    enabled: bool = False
    api_key: str | None = None
    base_url: str | None = None
    default_model: str | None = None
    timeout_seconds: float | None = Field(default=None, gt=0)
    max_retries: int | None = Field(default=None, ge=0)
    native_options: dict[str, Any] = Field(default_factory=dict)


class AgentConfig(ConfigSection):
    """
    agents.<claude_code|codex> 段
    """

    enabled: bool = False
    auth_mode: Literal["api_key", "cli_existing_session", "vendor_supported"] = "cli_existing_session"
    api_key: str | None = None
    executable_path: str | None = None
    home_directory: str | None = None
    working_directory: str | None = None
    timeout_seconds: float = Field(default=DEFAULT_AGENT_TIMEOUT_SECONDS, gt=0)
    permission_mode: str = "default"
    allowed_tools: list[str] = Field(default_factory=list)
    sandbox_mode: Literal["read-only", "workspace-write", "danger-full-access"] = "workspace-write"
    env_allowlist: list[str] = Field(default_factory=list)
    max_concurrent_runs: int = Field(default=DEFAULT_MAX_CONCURRENT_RUNS, ge=1)
    max_output_bytes: int = Field(default=DEFAULT_MAX_OUTPUT_BYTES, ge=1)
    max_line_bytes: int = Field(default=DEFAULT_MAX_LINE_BYTES, ge=1)
    max_stderr_bytes: int = Field(default=DEFAULT_MAX_STDERR_BYTES, ge=0)
    max_buffered_events: int = Field(default=DEFAULT_MAX_BUFFERED_EVENTS, ge=1)
    max_tool_output_chars: int = Field(default=DEFAULT_MAX_TOOL_OUTPUT_CHARS, ge=0)
    kill_grace_seconds: float = Field(default=DEFAULT_KILL_GRACE_SECONDS, ge=0)
    collect_events: bool = False
    native_options: dict[str, Any] = Field(default_factory=dict)


class CommonLlmConfig(ConfigSection):
    """
    完成合并与校验后的完整配置
    """

    common: CommonConfig = Field(default_factory=CommonConfig)
    providers: dict[str, ProviderConfig] = Field(default_factory=dict)
    agents: dict[str, AgentConfig] = Field(default_factory=dict)

    def resolve_provider_type(self, name: str) -> str:
        """
        type 缺省时取实例名
        """
        provider = self.providers[name]
        if provider.type is not None:
            return provider.type

        return name


__all__ = [
    "AGENT_NAMES",
    "MASK_TEXT",
    "PROVIDER_TYPES",
    "AgentConfig",
    "CommonConfig",
    "CommonLlmConfig",
    "ProviderConfig",
]
