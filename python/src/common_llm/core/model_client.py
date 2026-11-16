# -*- coding: utf-8 -*-
"""
ModelClient 抽象接口与各 Provider 共用的调用流程
创建日期：2026-11-16
修改日期：2026-11-16
"""
import asyncio
import time
from abc import ABC, abstractmethod
from collections.abc import AsyncGenerator, AsyncIterator
from contextlib import aclosing
from typing import Any

from common_llm.core.errors import CommonLlmError, ErrorCategory, build_error, build_error_info
from common_llm.core.ids import IdFactory, resolve_trace_id
from common_llm.core.model_support import (
    ModelCallContext,
    ModelEventSequencer,
    merge_provider_options,
    resolve_model_name,
    validate_model_request,
)
from common_llm.core.settings import ProviderSettings
from common_llm.core.types import ModelEvent, ModelRequest, ModelResponse, ProviderCapability
from common_llm.observability.logging import log_model_call


class ModelClient(ABC):
    """
    单个 Provider 实例的模型调用接口
    """

    @property
    @abstractmethod
    def name(self) -> str:
        """
        Provider 实例名
        """

    @abstractmethod
    async def generate(self, request: ModelRequest) -> ModelResponse:
        """
        非流式生成，失败时抛出 CommonLlmError
        """

    @abstractmethod
    def stream(self, request: ModelRequest) -> AsyncIterator[ModelEvent]:
        """
        流式生成，错误以 error 终止事件返回
        """

    @abstractmethod
    def capability(self) -> ProviderCapability:
        """
        获取实际支持的能力集合
        """

    def supports(self, feature: str) -> bool:
        """
        查询是否支持指定能力，未知能力返回 False
        """
        return feature in self.capability().features

    @abstractmethod
    def native_client(self) -> Any:
        """
        获取底层官方 SDK 客户端
        """

    @abstractmethod
    async def close(self) -> None:
        """
        等待进行中的请求结束后释放 SDK 客户端
        """


class BaseModelClient(ModelClient):
    """
    Provider 适配层基类，统一处理校验、参数合并、错误映射、日志与流终止事件
    """

    mapped_option_keys: tuple[str, ...] = ()
    features: tuple[str, ...] = ()

    def __init__(self, settings: ProviderSettings, trace_id_factory: IdFactory | None = None) -> None:
        self.settings = settings
        self._trace_id_factory = trace_id_factory
        self._active_requests = 0
        self._idle_event = asyncio.Event()
        self._idle_event.set()
        self._is_closed = False

    @property
    def name(self) -> str:
        return self.settings.name

    def capability(self) -> ProviderCapability:
        return ProviderCapability(provider=self.settings.name, features=list(self.features))

    def build_context(self, request: ModelRequest) -> ModelCallContext:
        """
        校验请求并组装调用上下文
        """
        if self._is_closed:
            raise build_error(ErrorCategory.CONFIG_INVALID, f"provider {self.name} 已关闭", provider=self.name)

        validate_model_request(request, self.name)
        model = resolve_model_name(request, self.settings.default_model, self.name)
        extra_body = self.merge_options(request)
        timeout_seconds = request.timeout_seconds or self.settings.timeout_seconds
        trace_id = resolve_trace_id(request.trace_id, self._trace_id_factory)
        return ModelCallContext(request=request, model=model, trace_id=trace_id, timeout_seconds=timeout_seconds, extra_body=extra_body)

    def merge_options(self, request: ModelRequest) -> dict[str, Any]:
        """
        合并原生参数并做重名校验，子类可覆盖以处理厂商特殊键
        """
        return merge_provider_options(self.settings.native_options, request.provider_options, self.mapped_option_keys, self.name)

    @abstractmethod
    async def invoke_generate(self, context: ModelCallContext) -> ModelResponse:
        """
        调用 SDK 完成非流式请求并映射响应
        """

    @abstractmethod
    def invoke_stream(self, context: ModelCallContext, sequencer: ModelEventSequencer) -> AsyncGenerator[ModelEvent, None]:
        """
        调用 SDK 完成流式请求并逐个产生事件，必须以 completed 结束
        """

    @abstractmethod
    def map_exception(self, error: Exception) -> CommonLlmError:
        """
        把 SDK 异常映射为 CommonLlmError
        """

    def _enter_request(self) -> None:
        self._active_requests += 1
        self._idle_event.clear()

    def _leave_request(self) -> None:
        self._active_requests -= 1
        if self._active_requests == 0:
            self._idle_event.set()

    async def generate(self, request: ModelRequest) -> ModelResponse:
        started_at = time.monotonic()
        context = self.build_context(request)
        self._enter_request()
        category: str | None = None
        usage: dict[str, Any] | None = None
        try:
            response = await self.invoke_generate(context)
            usage = response.usage.to_contract_dict()
            return response
        except CommonLlmError as error:
            category = error.category.value
            raise
        except Exception as error:
            mapped = self.map_exception(error)
            category = mapped.category.value
            raise mapped from error
        finally:
            self._leave_request()
            duration_ms = (time.monotonic() - started_at) * 1000
            log_model_call(provider=self.name, model=context.model, duration_ms=duration_ms, trace_id=context.trace_id, category=category, usage=usage)

    async def stream(self, request: ModelRequest) -> AsyncIterator[ModelEvent]:
        started_at = time.monotonic()
        trace_id = resolve_trace_id(request.trace_id, self._trace_id_factory)
        sequencer = ModelEventSequencer(self.name, trace_id)
        try:
            context = self.build_context(request.model_copy(update={"trace_id": trace_id}))
        except CommonLlmError as error:
            yield sequencer.error(error.info)
            return

        self._enter_request()
        category: str | None = None
        try:
            async with aclosing(self.invoke_stream(context, sequencer)) as events:
                async for event in events:
                    yield event
                    if sequencer.is_terminated:
                        return

            if not sequencer.is_terminated:
                info = build_error_info(ErrorCategory.PROVIDER_ERROR, "流在收到完成事件之前结束", provider=self.name, retriable=True)
                category = info.category.value
                yield sequencer.error(info)
        except CommonLlmError as error:
            category = error.category.value
            if not sequencer.is_terminated:
                yield sequencer.error(error.info)
        except Exception as error:
            mapped = self.map_exception(error)
            category = mapped.category.value
            if not sequencer.is_terminated:
                yield sequencer.error(mapped.info)
        finally:
            self._leave_request()
            duration_ms = (time.monotonic() - started_at) * 1000
            log_model_call(provider=self.name, model=context.model, duration_ms=duration_ms, trace_id=trace_id, category=category, usage=None)

    async def close(self) -> None:
        self._is_closed = True
        await self._idle_event.wait()
        await self.close_native_client()

    @abstractmethod
    async def close_native_client(self) -> None:
        """
        关闭底层 SDK 客户端
        """
