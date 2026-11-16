# -*- coding: utf-8 -*-
"""
CommonLlm 门面：按配置装配模型客户端与 Agent 客户端，并按请求路由
创建日期：2026-11-16
修改日期：2026-11-16
"""
import asyncio
import contextlib
from collections.abc import AsyncIterator, Mapping
from typing import Any

from common_llm.agents.claude_code.client import ClaudeCodeAgentClient
from common_llm.agents.codex.client import CodexAgentClient
from common_llm.config.loader import ConfigProvider, ConfigResolver, build_agent_settings, build_provider_settings
from common_llm.config.models import CommonLlmConfig
from common_llm.core.agent_client import AgentClient, BaseAgentClient
from common_llm.core.errors import CommonLlmError, ErrorCategory, build_error, build_error_info
from common_llm.core.ids import IdFactory, resolve_trace_id
from common_llm.core.model_client import ModelClient
from common_llm.core.model_support import ModelEventSequencer
from common_llm.core.types import AgentCapability, ModelErrorEvent, ModelEvent, ModelRequest, ModelResponse, ProviderCapability
from common_llm.observability.logging import apply_log_level
from common_llm.providers.anthropic.client import AnthropicModelClient
from common_llm.providers.gemini.client import GeminiModelClient
from common_llm.providers.openai.client import create_openai_client


class CommonLlm:
    """
    业务层统一入口，持有当前配置下已启用的全部客户端
    """

    def __init__(
        self,
        resolver: ConfigResolver,
        config: CommonLlmConfig,
        *,
        run_id_factory: IdFactory | None = None,
        trace_id_factory: IdFactory | None = None,
    ) -> None:
        self._resolver = resolver
        self._run_id_factory = run_id_factory
        self._trace_id_factory = trace_id_factory
        self._config = config
        self._models: dict[str, ModelClient] = {}
        self._agents: dict[str, AgentClient] = {}
        self._retire_tasks: set[asyncio.Task[None]] = set()
        self._models, self._agents = self.build_clients(config)
        apply_log_level(config.common.log_level)

    @classmethod
    def create(
        cls,
        config_provider: ConfigProvider | None = None,
        *,
        config_path: str | None = None,
        overrides: Mapping[str, Any] | None = None,
        run_id_factory: IdFactory | None = None,
        trace_id_factory: IdFactory | None = None,
    ) -> "CommonLlm":
        """
        加载配置并创建客户端；未传 config_path 时依次查找 COMMON_LLM_CONFIG 与当前目录的 common-llm.yaml
        """
        resolver = ConfigResolver(config_path=config_path, config_provider=config_provider, overrides=overrides)
        return cls(resolver, resolver.resolve(), run_id_factory=run_id_factory, trace_id_factory=trace_id_factory)

    @property
    def config(self) -> CommonLlmConfig:
        return self._config

    def build_clients(self, config: CommonLlmConfig) -> tuple[dict[str, ModelClient], dict[str, AgentClient]]:
        """
        为已启用的 Provider 与 Agent 创建客户端
        """
        models: dict[str, ModelClient] = {}
        for name, provider in config.providers.items():
            if not provider.enabled:
                continue

            settings = build_provider_settings(config, name)
            if settings.provider_type == "openai":
                models[name] = create_openai_client(settings, self._trace_id_factory)
            elif settings.provider_type == "anthropic":
                models[name] = AnthropicModelClient(settings, self._trace_id_factory)
            else:
                models[name] = GeminiModelClient(settings, self._trace_id_factory)

        agents: dict[str, AgentClient] = {}
        for agent_name, agent in config.agents.items():
            if not agent.enabled:
                continue

            agent_settings = build_agent_settings(config, agent_name)
            if agent_name == "claude_code":
                agents[agent_name] = ClaudeCodeAgentClient(agent_settings, run_id_factory=self._run_id_factory, trace_id_factory=self._trace_id_factory)
            else:
                agents[agent_name] = CodexAgentClient(agent_settings, run_id_factory=self._run_id_factory, trace_id_factory=self._trace_id_factory)

        return models, agents

    def model(self, provider: str) -> ModelClient:
        """
        获取 ModelClient，未启用或未配置时报 CONFIG_INVALID
        """
        client = self._models.get(provider)
        if client is None:
            raise build_error(ErrorCategory.CONFIG_INVALID, f"provider {provider} 未配置或未启用", provider=provider)

        return client

    def agent(self, name: str) -> AgentClient:
        """
        获取 AgentClient，未启用或未配置时报 CONFIG_INVALID
        """
        client = self._agents.get(name)
        if client is None:
            raise build_error(ErrorCategory.CONFIG_INVALID, f"agent {name} 未配置或未启用", provider=name)

        return client

    async def generate(self, request: ModelRequest) -> ModelResponse:
        """
        按 request.provider 路由到对应 ModelClient 完成非流式生成
        """
        return await self.model(request.provider).generate(request)

    async def stream(self, request: ModelRequest) -> AsyncIterator[ModelEvent]:
        """
        按 request.provider 路由到对应 ModelClient 完成流式生成，provider 不存在时返回 error 事件
        """
        try:
            client = self.model(request.provider)
        except CommonLlmError as error:
            trace_id = resolve_trace_id(request.trace_id, self._trace_id_factory)
            failure: ModelErrorEvent = ModelEventSequencer(request.provider, trace_id).error(error.info)
            yield failure
            return

        async for event in client.stream(request):
            yield event

    def list_providers(self) -> list[ProviderCapability]:
        """
        返回已启用 Provider 的能力
        """
        capabilities: list[ProviderCapability] = []
        for client in self._models.values():
            capabilities.append(client.capability())

        return capabilities

    async def list_agents(self) -> list[AgentCapability]:
        """
        返回已启用 Agent 的能力，会触发运行时探测
        """
        capabilities: list[AgentCapability] = []
        for client in self._agents.values():
            capabilities.append(await client.capabilities())

        return capabilities

    async def reload(self) -> None:
        """
        重新加载全部配置来源，失败时保留旧配置并抛出 CONFIG_INVALID；成功后重建客户端，旧客户端在请求结束后关闭
        """
        try:
            new_config = self._resolver.resolve()
        except CommonLlmError as error:
            info = build_error_info(ErrorCategory.CONFIG_INVALID, f"配置刷新失败，继续使用旧配置：{error.info.message}")
            raise CommonLlmError(info) from error

        new_models, new_agents = self.build_clients(new_config)
        old_models = self._models
        old_agents = self._agents
        self._config = new_config
        self._models = new_models
        self._agents = new_agents
        apply_log_level(new_config.common.log_level)
        retire_task = asyncio.create_task(self.retire_clients(old_models, old_agents))
        self._retire_tasks.add(retire_task)
        retire_task.add_done_callback(self._retire_tasks.discard)

    async def retire_clients(self, models: dict[str, ModelClient], agents: dict[str, AgentClient]) -> None:
        """
        等待旧客户端上进行中的请求与 run 结束后关闭
        """
        for agent_client in agents.values():
            if isinstance(agent_client, BaseAgentClient):
                await agent_client.wait_until_idle()

            await agent_client.close()

        for model_client in models.values():
            await model_client.close()

    async def close(self) -> None:
        """
        释放全部 SDK 客户端、子进程与连接
        """
        for task in list(self._retire_tasks):
            with contextlib.suppress(Exception):
                await task

        for agent_client in self._agents.values():
            await agent_client.close()

        for model_client in self._models.values():
            await model_client.close()
