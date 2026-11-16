# -*- coding: utf-8 -*-
"""
AgentClient 抽象接口与 Claude Code、Codex 共用的 run 生命周期管理
创建日期：2026-11-16
修改日期：2026-11-16
"""
import asyncio
import contextlib
import time
from abc import ABC, abstractmethod
from collections.abc import AsyncIterator, Awaitable, Callable
from typing import Any

from common_llm.core.agent_support import (
    AgentEventChannel,
    AgentEventFactory,
    AgentRunLimiter,
    resolve_working_directory,
)
from common_llm.core.errors import CommonLlmError, ErrorCategory, ErrorInfo, build_error, build_error_info
from common_llm.core.ids import IdFactory, generate_uuid, resolve_trace_id
from common_llm.core.settings import AgentSettings
from common_llm.core.types import (
    AGENT_TERMINAL_EVENT_TYPES,
    AgentCancelledEvent,
    AgentCapability,
    AgentCompletedEvent,
    AgentEvent,
    AgentFailedEvent,
    AgentMessageEvent,
    AgentName,
    AgentRequest,
    AgentResult,
    AgentUsage,
    AgentUsageEvent,
    ApprovalDecision,
    ApprovalHandler,
    ApprovalRequest,
)
from common_llm.observability.logging import get_logger, log_agent_run

VALID_APPROVAL_DECISIONS = ("approve", "approve_for_session", "decline", "cancel")
DEFAULT_APPROVAL_DECISION: ApprovalDecision = "decline"


class AgentRunState:
    """
    单次 run 的运行状态，负责按顺序写入事件并保证只有一个终止事件
    """

    def __init__(
        self,
        *,
        request: AgentRequest,
        factory: AgentEventFactory,
        channel: AgentEventChannel,
        approval_handler: ApprovalHandler | None,
        timeout_seconds: float,
    ) -> None:
        self.request = request
        self.factory = factory
        self.channel = channel
        self.approval_handler = approval_handler
        self.timeout_seconds = timeout_seconds
        self.working_directory = ""
        self.is_terminated = False
        self.terminal_status: str | None = None
        self.terminal_category: str | None = None
        self.last_usage: AgentUsage | None = None
        self.is_interrupt_requested = False
        self.interrupt_callback: Callable[[], Awaitable[None]] | None = None

    @property
    def run_id(self) -> str:
        return self.factory.run_id

    @property
    def trace_id(self) -> str:
        return self.factory.trace_id

    def emit(self, event: AgentEvent) -> None:
        """
        写入事件，终止事件之后的事件直接丢弃
        """
        if self.is_terminated:
            return

        if isinstance(event, AgentUsageEvent):
            self.last_usage = event.usage

        if event.type in AGENT_TERMINAL_EVENT_TYPES:
            self.is_terminated = True
            self.terminal_status = event.type

        if isinstance(event, AgentFailedEvent):
            self.terminal_category = event.error.category.value

        self.channel.put(event)

    def fail(self, info: ErrorInfo) -> None:
        """
        写入 failed 终止事件
        """
        self.emit(self.factory.failed(info))


async def decide_approval(run: AgentRunState, approval: ApprovalRequest) -> ApprovalDecision:
    """
    调用审批处理器获取决定，未提供处理器、处理器异常或返回非法值时一律拒绝
    """
    if run.approval_handler is None:
        return DEFAULT_APPROVAL_DECISION

    try:
        decision = await run.approval_handler(approval)
    except asyncio.CancelledError:
        raise
    except Exception:
        get_logger().warning("审批处理器执行失败，按拒绝处理 run_id=%s approval_id=%s", run.run_id, approval.approval_id)
        return DEFAULT_APPROVAL_DECISION

    if decision not in VALID_APPROVAL_DECISIONS:
        get_logger().warning("审批处理器返回了非法决定，按拒绝处理 run_id=%s", run.run_id)
        return DEFAULT_APPROVAL_DECISION

    return decision


class AgentClient(ABC):
    """
    单个 Coding Agent 的调用接口
    """

    @property
    @abstractmethod
    def name(self) -> AgentName:
        """
        Agent 名称
        """

    @abstractmethod
    async def run(self, request: AgentRequest, *, approval_handler: ApprovalHandler | None = None) -> AgentResult:
        """
        执行一次 run 并返回结果，失败以 status=failed 返回
        """

    @abstractmethod
    def run_stream(self, request: AgentRequest, *, approval_handler: ApprovalHandler | None = None) -> AsyncIterator[AgentEvent]:
        """
        执行一次 run 并逐个产生事件，以 completed、failed 或 cancelled 结束
        """

    @abstractmethod
    async def resume(self, session_id: str, request: AgentRequest, *, approval_handler: ApprovalHandler | None = None) -> AgentResult:
        """
        在已有会话上继续执行
        """

    @abstractmethod
    async def interrupt(self, run_id: str) -> bool:
        """
        中断进行中的 run，找不到该 run 时返回 False
        """

    @abstractmethod
    async def capabilities(self) -> AgentCapability:
        """
        执行运行时探测并返回能力
        """

    @abstractmethod
    async def close(self) -> None:
        """
        取消进行中的 run 并释放资源
        """


class BaseAgentClient(AgentClient):
    """
    Agent 适配层基类，统一处理校验、并发限制、超时、取消、事件缓冲与日志
    """

    agent_name: AgentName = "claude_code"

    def __init__(
        self,
        settings: AgentSettings,
        *,
        run_id_factory: IdFactory | None = None,
        trace_id_factory: IdFactory | None = None,
    ) -> None:
        self.settings = settings
        self._run_id_factory = run_id_factory or generate_uuid
        self._trace_id_factory = trace_id_factory
        self._limiter = AgentRunLimiter(self.agent_name, settings.max_concurrent_runs)
        self._active_runs: dict[str, AgentRunState] = {}
        self._producer_tasks: set[asyncio.Task[None]] = set()
        self._is_closed = False

    @property
    def name(self) -> AgentName:
        return self.agent_name

    @abstractmethod
    async def execute_run(self, run: AgentRunState) -> None:
        """
        启动 SDK 并把厂商消息映射为事件写入 run，必须写入终止事件或抛出异常
        """

    @abstractmethod
    def map_exception(self, error: Exception) -> CommonLlmError:
        """
        把 SDK 异常映射为 CommonLlmError
        """

    @abstractmethod
    async def release_resources(self) -> None:
        """
        close 时释放子类持有的资源与探测缓存
        """

    def validate_request(self, request: AgentRequest) -> None:
        """
        校验请求中的 agent 名称与 prompt
        """
        if request.agent != self.agent_name:
            raise build_error(ErrorCategory.CONFIG_INVALID, f"请求的 agent {request.agent} 与客户端 {self.agent_name} 不一致", provider=self.agent_name)

        if not request.prompt:
            raise build_error(ErrorCategory.CONFIG_INVALID, "prompt 不能为空", provider=self.agent_name)

        if request.timeout_seconds is not None and request.timeout_seconds <= 0:
            raise build_error(ErrorCategory.CONFIG_INVALID, "timeoutSeconds 必须大于 0", provider=self.agent_name)

    def resolve_env_allowlist(self, request: AgentRequest) -> list[str]:
        """
        请求级 envAllowlist 优先，否则取配置
        """
        if request.env_allowlist is not None:
            return list(request.env_allowlist)

        return list(self.settings.env_allowlist)

    def merge_native_options(self, request: AgentRequest) -> dict[str, Any]:
        """
        合并配置级与请求级 nativeOptions，请求级优先
        """
        merged: dict[str, Any] = {}
        for key, value in self.settings.native_options.items():
            merged[key] = value

        if request.native_options is not None:
            for key, value in request.native_options.items():
                merged[key] = value

        return merged

    async def run(self, request: AgentRequest, *, approval_handler: ApprovalHandler | None = None) -> AgentResult:
        collected: list[AgentEvent] = []
        result: AgentResult | None = None
        last_usage: AgentUsage | None = None
        final_text = ""
        async for event in self.run_stream(request, approval_handler=approval_handler):
            if self.settings.collect_events:
                collected.append(event)

            if isinstance(event, AgentUsageEvent):
                last_usage = event.usage
            elif isinstance(event, AgentMessageEvent) and not event.is_delta:
                final_text = event.text
            elif isinstance(event, AgentCompletedEvent):
                result = event.result
            elif isinstance(event, AgentFailedEvent):
                result = AgentResult(
                    run_id=event.run_id,
                    session_id=event.session_id,
                    status="failed",
                    final_text=final_text,
                    usage=last_usage,
                    error=event.error,
                )
            elif isinstance(event, AgentCancelledEvent):
                result = AgentResult(
                    run_id=event.run_id,
                    session_id=event.session_id,
                    status="cancelled",
                    final_text=final_text,
                    usage=last_usage,
                )

        if result is None:
            raise build_error(ErrorCategory.INTERNAL_ERROR, "run 结束时没有终止事件", provider=self.agent_name)

        if self.settings.collect_events:
            result = result.model_copy(update={"events": collected})

        return result

    async def resume(self, session_id: str, request: AgentRequest, *, approval_handler: ApprovalHandler | None = None) -> AgentResult:
        resumed_request = request.model_copy(update={"session_id": session_id})
        return await self.run(resumed_request, approval_handler=approval_handler)

    async def run_stream(self, request: AgentRequest, *, approval_handler: ApprovalHandler | None = None) -> AsyncIterator[AgentEvent]:
        run_id = self._run_id_factory()
        trace_id = resolve_trace_id(request.trace_id, self._trace_id_factory)
        factory = AgentEventFactory(self.agent_name, trace_id, run_id)
        channel = AgentEventChannel(self.settings.max_buffered_events)
        timeout_seconds = request.timeout_seconds or self.settings.timeout_seconds
        run = AgentRunState(
            request=request,
            factory=factory,
            channel=channel,
            approval_handler=approval_handler,
            timeout_seconds=timeout_seconds,
        )
        producer = asyncio.create_task(self._produce(run))
        self._producer_tasks.add(producer)
        producer.add_done_callback(self._producer_tasks.discard)
        try:
            while True:
                event = await channel.get()
                if event is None:
                    break

                yield event
        finally:
            if not producer.done():
                producer.cancel()

            with contextlib.suppress(asyncio.CancelledError):
                await producer

    async def _produce(self, run: AgentRunState) -> None:
        started_at = time.monotonic()
        is_acquired = False
        try:
            try:
                if self._is_closed:
                    raise build_error(ErrorCategory.CONFIG_INVALID, f"{self.agent_name} 客户端已关闭", provider=self.agent_name)

                self.validate_request(run.request)
                run.working_directory = resolve_working_directory(run.request.working_directory, self.settings.working_directory, self.agent_name)
                self._limiter.acquire(run.run_id)
                is_acquired = True
            except CommonLlmError as error:
                run.fail(error.info)
                return

            self._active_runs[run.run_id] = run
            await self._execute_with_timeout(run)
        finally:
            self._active_runs.pop(run.run_id, None)
            if is_acquired:
                self._limiter.release(run.run_id)

            duration_ms = (time.monotonic() - started_at) * 1000
            usage_fields: dict[str, Any] | None = None
            if run.last_usage is not None:
                usage_fields = run.last_usage.to_contract_dict()

            log_agent_run(
                agent=self.agent_name,
                run_id=run.run_id,
                trace_id=run.trace_id,
                duration_ms=duration_ms,
                status=run.terminal_status or "cancelled",
                category=run.terminal_category,
                usage=usage_fields,
            )
            run.channel.close()

    async def _execute_with_timeout(self, run: AgentRunState) -> None:
        try:
            async with asyncio.timeout(run.timeout_seconds):
                await self.execute_run(run)
        except TimeoutError as error:
            run.fail(build_error_info(ErrorCategory.TIMEOUT, f"{self.agent_name} run 超过 {run.timeout_seconds} 秒未结束", provider=self.agent_name, cause=error))
        except CommonLlmError as error:
            run.fail(error.info)
        except Exception as error:
            run.fail(self.map_exception(error).info)

        if not run.is_terminated:
            run.fail(build_error_info(ErrorCategory.CLI_PROTOCOL_ERROR, f"{self.agent_name} 在结束前没有返回终止结果", provider=self.agent_name))

    async def interrupt(self, run_id: str) -> bool:
        run = self._active_runs.get(run_id)
        if run is None:
            return False

        run.is_interrupt_requested = True
        if run.interrupt_callback is not None:
            await run.interrupt_callback()

        return True

    async def close(self) -> None:
        self._is_closed = True
        pending = list(self._producer_tasks)
        for task in pending:
            task.cancel()

        for task in pending:
            with contextlib.suppress(asyncio.CancelledError):
                await task

        await self.release_resources()

    async def wait_until_idle(self) -> None:
        """
        等待全部进行中的 run 结束，用于配置刷新后关闭旧客户端
        """
        pending = list(self._producer_tasks)
        for task in pending:
            with contextlib.suppress(asyncio.CancelledError):
                await task
