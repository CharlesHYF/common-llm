# -*- coding: utf-8 -*-
"""
配置加载：读取 YAML / JSON 文件、解析环境变量引用、按字段合并多个来源并校验
创建日期：2026-11-16
修改日期：2026-11-16
"""
import json
import os
import re
from collections.abc import Mapping
from typing import Any, Protocol

import yaml
from pydantic import ValidationError

from common_llm.config.models import AGENT_NAMES, PROVIDER_TYPES, AgentConfig, CommonLlmConfig, ProviderConfig
from common_llm.core.errors import CommonLlmError, ErrorCategory, build_error
from common_llm.core.redaction import register_secret
from common_llm.core.settings import AgentSettings, ProviderSettings

CONFIG_PATH_ENV = "COMMON_LLM_CONFIG"
DEFAULT_CONFIG_FILE = "common-llm.yaml"
# $${NAME} 为字面量，${NAME} 与 ${NAME:-default} 为引用
ENV_REFERENCE_PATTERN = re.compile(r"\$\$\{([^}]*)\}|\$\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?\}")
SECTION_NAMES = ("common", "providers", "agents")
# 被合并到实例级别的配置段，键是实例名，值是字段字典
INSTANCE_SECTIONS = ("providers", "agents")


class ConfigProvider(Protocol):
    """
    外部配置来源接口，load 返回配置的部分字段，未提供的字段省略
    """

    def load(self) -> Mapping[str, Any]:
        """
        读取配置字段
        """


def substitute_env_text(text: str, missing_names: list[str]) -> str:
    """
    替换字符串中的环境变量引用，缺失且无默认值的变量名追加到 missing_names
    """

    def replace(match: re.Match[str]) -> str:
        literal_name = match.group(1)
        if literal_name is not None:
            return "${" + literal_name + "}"

        name = match.group(2)
        default_value = match.group(3)
        value = os.environ.get(name)
        if value:
            return value

        if default_value is not None:
            return default_value

        missing_names.append(name)
        return ""

    return ENV_REFERENCE_PATTERN.sub(replace, text)


def substitute_env_values(value: Any, path: str, missing: dict[str, list[str]]) -> Any:
    """
    递归替换字符串值中的环境变量引用，键名不替换，missing 记录字段路径到缺失变量名
    """
    if isinstance(value, str):
        missing_names: list[str] = []
        substituted = substitute_env_text(value, missing_names)
        if missing_names:
            missing[path] = missing_names

        return substituted

    if isinstance(value, Mapping):
        substituted_mapping: dict[str, Any] = {}
        for key, child in value.items():
            substituted_mapping[key] = substitute_env_values(child, f"{path}.{key}" if path else str(key), missing)

        return substituted_mapping

    if isinstance(value, list):
        substituted_items: list[Any] = []
        for index, child in enumerate(value):
            substituted_items.append(substitute_env_values(child, f"{path}[{index}]", missing))

        return substituted_items

    return value


def read_config_file(path: str) -> dict[str, Any]:
    """
    按扩展名读取 YAML 或 JSON 文件，文件不存在或格式错误时报 CONFIG_INVALID
    """
    if not os.path.isfile(path):
        raise build_error(ErrorCategory.CONFIG_INVALID, f"配置文件不存在：{path}")

    try:
        with open(path, encoding="utf-8") as config_file:
            if path.lower().endswith(".json"):
                content = json.load(config_file)
            else:
                content = yaml.safe_load(config_file)
    except (OSError, yaml.YAMLError, json.JSONDecodeError) as error:
        raise build_error(ErrorCategory.CONFIG_INVALID, f"配置文件无法解析：{path}", cause=error) from error

    if content is None:
        return {}

    if not isinstance(content, dict):
        raise build_error(ErrorCategory.CONFIG_INVALID, f"配置文件顶层必须是对象：{path}")

    return content


class FileConfigProvider:
    """
    文件配置来源，加载时解析 ${ENV} 引用并记录缺失的变量名
    """

    def __init__(self, path: str) -> None:
        self.path = path
        self.missing_variables: dict[str, list[str]] = {}

    def load(self) -> Mapping[str, Any]:
        missing: dict[str, list[str]] = {}
        substituted: dict[str, Any] = substitute_env_values(read_config_file(self.path), "", missing)
        self.missing_variables = missing
        return substituted


def resolve_config_path(explicit_path: str | None) -> str | None:
    """
    按显式路径、COMMON_LLM_CONFIG、当前目录 common-llm.yaml 的顺序确定文件，都没有时返回 None
    """
    if explicit_path:
        return explicit_path

    env_path = os.environ.get(CONFIG_PATH_ENV)
    if env_path:
        return env_path

    default_path = os.path.join(os.getcwd(), DEFAULT_CONFIG_FILE)
    if os.path.isfile(default_path):
        return default_path

    return None


def merge_config_layer(target: dict[str, Any], layer: Mapping[str, Any]) -> None:
    """
    按字段合并一个来源：common 合并到字段，providers 与 agents 合并到实例的字段，叶子值整体覆盖
    """
    for section, section_value in layer.items():
        if section not in SECTION_NAMES or not isinstance(section_value, Mapping):
            target[section] = section_value
            continue

        target_section = target.setdefault(section, {})
        if section not in INSTANCE_SECTIONS:
            for key, value in section_value.items():
                target_section[key] = value
            continue

        for instance_name, instance_value in section_value.items():
            if not isinstance(instance_value, Mapping):
                target_section[instance_name] = instance_value
                continue

            target_instance = target_section.setdefault(instance_name, {})
            for key, value in instance_value.items():
                target_instance[key] = value


def normalize_empty_strings(merged: dict[str, Any]) -> None:
    """
    把 providers 与 agents 中替换后为空字符串的字段视为未设置
    """
    for section in INSTANCE_SECTIONS:
        section_value = merged.get(section)
        if not isinstance(section_value, dict):
            continue

        for instance_value in section_value.values():
            if not isinstance(instance_value, dict):
                continue

            empty_keys: list[str] = []
            for key, value in instance_value.items():
                if value == "":
                    empty_keys.append(key)

            for key in empty_keys:
                del instance_value[key]


def describe_missing(path: str, missing: Mapping[str, list[str]]) -> str:
    """
    生成缺失变量名提示，不包含变量值
    """
    names = missing.get(path)
    if not names:
        return ""

    return f"（缺少环境变量 {', '.join(names)}）"


def build_validation_message(error: ValidationError) -> str:
    """
    把 pydantic 校验错误转为不含输入值的字段路径说明
    """
    descriptions: list[str] = []
    for detail in error.errors(include_input=False, include_url=False):
        location_parts: list[str] = []
        for location in detail["loc"]:
            location_parts.append(str(location))

        descriptions.append(f"{'.'.join(location_parts)}: {detail['msg']}")

    return "配置校验失败：" + "; ".join(descriptions)


def validate_provider(name: str, provider: ProviderConfig, missing: Mapping[str, list[str]]) -> None:
    """
    校验 Provider 实例的类型与启用时的必填字段
    """
    provider_type = provider.type or name
    if provider_type not in PROVIDER_TYPES:
        raise build_error(ErrorCategory.CONFIG_INVALID, f"providers.{name}.type 必填，取值为 openai / anthropic / gemini")

    if provider.api != "responses" and provider_type != "openai":
        raise build_error(ErrorCategory.CONFIG_INVALID, f"providers.{name}.api 只适用于 type=openai")

    if provider.enabled and not provider.api_key:
        hint = describe_missing(f"providers.{name}.api_key", missing)
        raise build_error(ErrorCategory.AUTH_MISSING, f"providers.{name}.api_key 为空{hint}", provider=name)


def validate_agent(name: str, agent: AgentConfig, missing: Mapping[str, list[str]]) -> None:
    """
    校验启用的 Agent 在 api_key 模式下的必填字段
    """
    if name not in AGENT_NAMES:
        raise build_error(ErrorCategory.CONFIG_INVALID, f"agents.{name} 不是支持的 Agent，取值为 claude_code / codex")

    if not agent.enabled or agent.auth_mode != "api_key":
        return

    if not agent.api_key:
        hint = describe_missing(f"agents.{name}.api_key", missing)
        raise build_error(ErrorCategory.AUTH_MISSING, f"agents.{name}.api_key 为空{hint}", provider=name)

    if name == "codex" and not agent.home_directory:
        raise build_error(ErrorCategory.CONFIG_INVALID, "agents.codex.auth_mode=api_key 时必须配置 home_directory", provider=name)


class ConfigResolver:
    """
    按 运行时覆盖 > ConfigProvider > 文件 > 默认值 的优先级加载并校验配置
    """

    def __init__(
        self,
        *,
        config_path: str | None = None,
        config_provider: ConfigProvider | None = None,
        overrides: Mapping[str, Any] | None = None,
    ) -> None:
        self.config_path = config_path
        self.config_provider = config_provider
        self.overrides = overrides

    def resolve(self) -> CommonLlmConfig:
        """
        执行全部来源的加载、合并与校验，失败时抛出 CONFIG_INVALID 或 AUTH_MISSING
        """
        merged: dict[str, Any] = {}
        missing: dict[str, list[str]] = {}
        path = resolve_config_path(self.config_path)
        if path is None and self.config_provider is None and self.overrides is None:
            raise build_error(ErrorCategory.CONFIG_INVALID, f"找不到配置：未指定配置文件，{CONFIG_PATH_ENV} 未设置，当前目录也没有 {DEFAULT_CONFIG_FILE}")

        if path is not None:
            file_provider = FileConfigProvider(path)
            merge_config_layer(merged, file_provider.load())
            missing = file_provider.missing_variables

        if self.config_provider is not None:
            merge_config_layer(merged, self.load_from_provider(self.config_provider))

        if self.overrides is not None:
            merge_config_layer(merged, self.overrides)

        normalize_empty_strings(merged)
        try:
            config = CommonLlmConfig.model_validate(merged)
        except ValidationError as error:
            raise build_error(ErrorCategory.CONFIG_INVALID, build_validation_message(error)) from None

        for name, provider in config.providers.items():
            validate_provider(name, provider, missing)
            register_secret(provider.api_key)

        for agent_name, agent in config.agents.items():
            validate_agent(agent_name, agent, missing)
            register_secret(agent.api_key)

        return config

    def load_from_provider(self, provider: ConfigProvider) -> Mapping[str, Any]:
        """
        调用外部 ConfigProvider，异常统一转为 CONFIG_INVALID
        """
        try:
            loaded = provider.load()
        except CommonLlmError:
            raise
        except Exception as error:
            raise build_error(ErrorCategory.CONFIG_INVALID, "ConfigProvider 加载配置失败", cause=error) from error

        if not isinstance(loaded, Mapping):
            raise build_error(ErrorCategory.CONFIG_INVALID, "ConfigProvider.load 必须返回对象")

        return loaded


def build_provider_settings(config: CommonLlmConfig, name: str) -> ProviderSettings:
    """
    把 Provider 配置与 common 段合并为适配层运行参数
    """
    provider = config.providers[name]
    common = config.common
    timeout_seconds = provider.timeout_seconds if provider.timeout_seconds is not None else common.timeout_seconds
    max_retries = provider.max_retries if provider.max_retries is not None else common.max_retries
    return ProviderSettings(
        name=name,
        provider_type=config.resolve_provider_type(name),
        api=provider.api,
        api_key=provider.api_key,
        base_url=provider.base_url,
        default_model=provider.default_model,
        timeout_seconds=timeout_seconds,
        connect_timeout_seconds=common.connect_timeout_seconds,
        max_retries=max_retries,
        native_options=dict(provider.native_options),
    )


def build_agent_settings(config: CommonLlmConfig, name: str) -> AgentSettings:
    """
    把 Agent 配置转为适配层运行参数
    """
    agent = config.agents[name]
    return AgentSettings(
        name=name,
        auth_mode=agent.auth_mode,
        api_key=agent.api_key,
        executable_path=agent.executable_path,
        home_directory=agent.home_directory,
        working_directory=agent.working_directory,
        timeout_seconds=agent.timeout_seconds,
        permission_mode=agent.permission_mode,
        allowed_tools=list(agent.allowed_tools),
        sandbox_mode=agent.sandbox_mode,
        env_allowlist=list(agent.env_allowlist),
        max_concurrent_runs=agent.max_concurrent_runs,
        max_output_bytes=agent.max_output_bytes,
        max_line_bytes=agent.max_line_bytes,
        max_stderr_bytes=agent.max_stderr_bytes,
        max_buffered_events=agent.max_buffered_events,
        max_tool_output_chars=agent.max_tool_output_chars,
        kill_grace_seconds=agent.kill_grace_seconds,
        collect_events=agent.collect_events,
        native_options=dict(agent.native_options),
    )
