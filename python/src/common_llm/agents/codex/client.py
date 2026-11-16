# -*- coding: utf-8 -*-
"""
基于 openai-codex SDK 的 Codex Agent 适配器，经由 codex app-server 执行 run
创建日期：2026-11-16
修改日期：2026-11-16
"""
import asyncio
import contextlib
import json
import os
import threading
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from openai_codex import CodexConfig, CodexError, JsonRpcError, TransportClosedError
from openai_codex.client import CodexClient
from openai_codex.generated.v2_all import TurnStartResponse
from openai_codex.models import JsonObject, Notification

from common_llm.agents.codex.mapper import (
    APPROVAL_METHODS,
    FILE_CHANGE_APPROVAL_METHOD,
    CodexTurnMapper,
    WireApproval,
    WireClosed,
    WireNotification,
    WireServerRequest,
)
from common_llm.core.agent_client import AgentRunState, BaseAgentClient, decide_approval
from common_llm.core.agent_support import build_isolated_environment, probe_version, resolve_executable
from common_llm.core.errors import CommonLlmError, ErrorCategory, build_error
from common_llm.core.ids import IdFactory
from common_llm.core.settings import AgentSettings
from common_llm.core.types import (
    FEATURE_APPROVALS,
    FEATURE_INTERRUPT,
    FEATURE_SESSION_RESUME,
    FEATURE_STREAMING,
    AgentCapability,
    AgentName,
    ApprovalDecision,
    ApprovalRequest,
)
from common_llm.observability.logging import log_dangerous_mode
from common_llm.version import __version__

MINIMUM_CODEX_VERSION = (0, 169, 0)
TURN_NATIVE_OPTION_KEY = "turn"
RESERVED_THREAD_OPTION_KEYS = ("cwd", "sandbox", "model", "threadId", "excludeTurns")
RESERVED_TURN_OPTION_KEYS = ("threadId", "input")
# 审批决定与 App Server CommandExecutionApprovalDecision / FileChangeApprovalDecision 取值的对应关系
WIRE_DECISIONS: dict[str, str] = {
    "approve": "accept",
    "approve_for_session": "acceptForSession",
    "decline": "decline",
    "cancel": "cancel",
}
EVENT_TYPES = [
    "started",
    "message",
    "tool_started",
    "tool_completed",
    "file_changed",
    "approval_requested",
    "usage",
    "completed",
    "failed",
    "cancelled",
    "raw_vendor_event",
]
FEATURES = [
    FEATURE_STREAMING,
    FEATURE_SESSION_RESUME,
    FEATURE_APPROVALS,
    FEATURE_INTERRUPT,
]

WireItem = WireNotification | WireApproval | WireServerRequest | WireClosed
ServerRequestHandler = Callable[[str, JsonObject | None], JsonObject]


class ObservedCodexClient(CodexClient):
    """
    在读取线程中按到达顺序把通知交给观察者，用于保持审批请求与通知之间的先后顺序
    """

    def __init__(self, config: CodexConfig, approval_handler: ServerRequestHandler, observer: Callable[[WireItem], None]) -> None:
        super().__init__(config=config, approval_handler=approval_handler)
        self._observer = observer

    def _coerce_notification(self, method: str, params: object) -> Notification:
        params_dict: dict[str, Any] = {}
        if isinstance(params, dict):
            params_dict = params

        self._observer(WireNotification(method=method, params=params_dict))
        return super()._coerce_notification(method, params)


def decline_all_requests(method: str, params: JsonObject | None) -> JsonObject:
    """
    探测握手使用的审批处理器，审批请求一律拒绝
    """
    if method in APPROVAL_METHODS:
        return {"decision": "decline"}

    return {}


def estimate_wire_bytes(params: dict[str, Any]) -> int:
    """
    估算单条通知的字节数，SDK 不暴露原始行，按参数重新序列化计算
    """
    return len(json.dumps(params, ensure_ascii=False, default=str).encode("utf-8"))


@dataclass
class CodexProbeResult:
    """
    运行时探测结果
    """

    executable_path: str
    version: str


class CodexAgentClient(BaseAgentClient):
    """
    Codex 适配器，每个 run 启动一个 app-server 进程，审批默认拒绝
    """

    agent_name: AgentName = "codex"

    def __init__(
        self,
        settings: AgentSettings,
        *,
        run_id_factory: IdFactory | None = None,
        trace_id_factory: IdFactory | None = None,
    ) -> None:
        super().__init__(settings, run_id_factory=run_id_factory, trace_id_factory=trace_id_factory)
        self._probe_result: CodexProbeResult | None = None
        self._probe_lock = asyncio.Lock()
        self._is_handshake_verified = False

    async def ensure_probe(self) -> CodexProbeResult:
        """
        解析可执行文件并校验版本，结果缓存到 close 为止
        """
        async with self._probe_lock:
            if self._probe_result is not None:
                return self._probe_result

            executable = resolve_executable(self.settings.executable_path, ["codex"], self.agent_name)
            version = await probe_version(executable, MINIMUM_CODEX_VERSION, self.agent_name)
            self._probe_result = CodexProbeResult(executable_path=executable, version=version)
            return self._probe_result

    def build_environment(self, env_allowlist: list[str]) -> dict[str, str]:
        """
        组装子进程环境变量，配置了 home_directory 时设置 CODEX_HOME
        """
        extra_values: dict[str, str] = {}
        if self.settings.home_directory:
            home_directory = os.path.realpath(os.path.expanduser(self.settings.home_directory))
            os.makedirs(home_directory, exist_ok=True)
            extra_values["CODEX_HOME"] = home_directory

        if self.settings.auth_mode == "api_key" and not self.settings.home_directory:
            raise build_error(
                ErrorCategory.CONFIG_INVALID,
                "auth_mode=api_key 时必须配置 home_directory，避免改写用户的 Codex 登录状态",
                provider=self.agent_name,
            )

        return build_isolated_environment(env_allowlist, extra_values)

    def build_config(self, probe: CodexProbeResult, working_directory: str | None, env_allowlist: list[str]) -> CodexConfig:
        """
        组装 CodexConfig，显式指定可执行文件路径与隔离后的环境变量
        """
        return CodexConfig(
            codex_bin=probe.executable_path,
            cwd=working_directory,
            env=self.build_environment(env_allowlist),
            client_name="common_llm",
            client_title="common-llm",
            client_version=__version__,
        )

    async def verify_handshake(self, probe: CodexProbeResult) -> None:
        """
        启动 app-server 完成 initialize 握手后关闭，握手失败报 CLI_PROTOCOL_ERROR
        """
        if self._is_handshake_verified:
            return

        client = CodexClient(config=self.build_config(probe, None, list(self.settings.env_allowlist)), approval_handler=decline_all_requests)
        try:
            await asyncio.to_thread(client.start)
            await asyncio.to_thread(client.initialize)
        except (CodexError, OSError) as error:
            raise build_error(ErrorCategory.CLI_PROTOCOL_ERROR, "Codex app-server initialize 握手失败", provider=self.agent_name, cause=error) from error
        finally:
            await asyncio.to_thread(client.close)

        self._is_handshake_verified = True

    async def capabilities(self) -> AgentCapability:
        probe = await self.ensure_probe()
        await self.verify_handshake(probe)
        return AgentCapability(
            agent=self.agent_name,
            implementation="sdk",
            executable_path=probe.executable_path,
            version=probe.version,
            features=list(FEATURES),
            event_types=list(EVENT_TYPES),
            supported_options=None,
        )

    def build_thread_params(self, run: AgentRunState, native_options: dict[str, Any]) -> dict[str, Any]:
        """
        组装 thread/start 或 thread/resume 参数，沙箱默认 workspace-write，审批默认 on-request
        """
        sandbox_mode = run.request.sandbox_mode or self.settings.sandbox_mode
        if sandbox_mode == "danger-full-access":
            log_dangerous_mode(self.agent_name, sandbox_mode)

        params: dict[str, Any] = {
            "cwd": run.working_directory,
            "sandbox": sandbox_mode,
            "approvalPolicy": "on-request",
        }
        if run.request.model:
            params["model"] = run.request.model

        if run.request.session_id:
            params["excludeTurns"] = True

        for key, value in native_options.items():
            if key == TURN_NATIVE_OPTION_KEY:
                continue

            if key in RESERVED_THREAD_OPTION_KEYS:
                raise build_error(ErrorCategory.CONFIG_INVALID, f"nativeOptions.{key} 由统一请求字段负责，不能透传", provider=self.agent_name)

            params[key] = value

        return params

    def build_turn_params(self, run: AgentRunState, thread_id: str, native_options: dict[str, Any]) -> dict[str, Any]:
        """
        组装 turn/start 参数，nativeOptions.turn 中的键追加到参数中
        """
        params: dict[str, Any] = {
            "threadId": thread_id,
            "input": [
                {
                    "type": "text",
                    "text": run.request.prompt,
                },
            ],
        }
        if run.request.model:
            params["model"] = run.request.model

        turn_options = native_options.get(TURN_NATIVE_OPTION_KEY)
        if turn_options is None:
            return params

        if not isinstance(turn_options, dict):
            raise build_error(ErrorCategory.CONFIG_INVALID, "nativeOptions.turn 必须是对象", provider=self.agent_name)

        for key, value in turn_options.items():
            if key in RESERVED_TURN_OPTION_KEYS:
                raise build_error(ErrorCategory.CONFIG_INVALID, f"nativeOptions.turn.{key} 由统一请求字段负责，不能透传", provider=self.agent_name)

            params[key] = value

        return params

    async def authenticate(self, client: CodexClient) -> None:
        """
        api_key 模式在独立 CODEX_HOME 中登录，cli_existing_session 模式用 account/read 校验登录状态
        """
        if self.settings.auth_mode == "api_key":
            if not self.settings.api_key:
                raise build_error(ErrorCategory.AUTH_MISSING, "auth_mode=api_key 但未配置 api_key", provider=self.agent_name)

            login_params: JsonObject = {
                "type": "apiKey",
                "apiKey": self.settings.api_key,
            }
            await asyncio.to_thread(client.account_login_start, login_params)
            return

        if self.settings.auth_mode != "cli_existing_session":
            return

        account_params: JsonObject = {
            "refreshToken": False,
        }
        account = await asyncio.to_thread(client.account_read, account_params)
        if account.account is None and account.requires_openai_auth:
            raise build_error(ErrorCategory.AUTH_MISSING, "Codex 未登录，请先执行 codex login 或改用 api_key 模式", provider=self.agent_name)

    async def execute_run(self, run: AgentRunState) -> None:
        probe = await self.ensure_probe()
        native_options = self.merge_native_options(run.request)
        thread_params = self.build_thread_params(run, native_options)
        loop = asyncio.get_running_loop()
        wire_queue: asyncio.Queue[WireItem] = asyncio.Queue()
        is_finished = threading.Event()

        def observe(item: WireItem) -> None:
            if is_finished.is_set():
                return

            with contextlib.suppress(RuntimeError):
                loop.call_soon_threadsafe(wire_queue.put_nowait, item)

        def handle_server_request(method: str, params: JsonObject | None) -> JsonObject:
            request_params: dict[str, Any] = dict(params or {})
            if method not in APPROVAL_METHODS:
                observe(WireServerRequest(method=method, params=request_params))
                return {}

            decision = self.request_approval_decision(run, loop, method, request_params)
            observe(WireApproval(method=method, params=request_params, decision=decision))
            return {"decision": WIRE_DECISIONS[decision]}

        config = self.build_config(probe, run.working_directory, self.resolve_env_allowlist(run.request))
        client = ObservedCodexClient(config, handle_server_request, observe)
        mapper = CodexTurnMapper(run.factory, self.settings.max_tool_output_chars)
        try:
            await asyncio.to_thread(client.start)
            self.start_drain_thread(client, observe)
            try:
                await asyncio.to_thread(client.initialize)
            except CodexError as error:
                raise build_error(ErrorCategory.CLI_PROTOCOL_ERROR, "Codex app-server initialize 握手失败", provider=self.agent_name, cause=error) from error

            await self.authenticate(client)
            if run.request.session_id:
                resumed = await asyncio.to_thread(client.thread_resume, run.request.session_id, thread_params)
                mapper.record_thread(resumed.thread.id, resumed.model, str(resumed.cwd.root))
            else:
                started = await asyncio.to_thread(client.thread_start, thread_params)
                mapper.record_thread(started.thread.id, started.model, str(started.cwd.root))

            turn_params = self.build_turn_params(run, mapper.factory.session_id or "", native_options)
            turn = await asyncio.to_thread(client.request, "turn/start", turn_params, response_model=TurnStartResponse)
            thread_id = mapper.factory.session_id or ""
            turn_id = turn.turn.id

            async def interrupt_turn() -> None:
                await asyncio.to_thread(client.turn_interrupt, thread_id, turn_id)

            run.interrupt_callback = interrupt_turn
            if run.is_interrupt_requested:
                await interrupt_turn()

            await self.consume_wire(run, mapper, wire_queue)
        finally:
            is_finished.set()
            run.interrupt_callback = None
            await asyncio.to_thread(client.close)

    def request_approval_decision(self, run: AgentRunState, loop: asyncio.AbstractEventLoop, method: str, params: dict[str, Any]) -> ApprovalDecision:
        """
        在读取线程中同步等待事件循环上的审批处理器结果，超时或失败时拒绝
        """
        approval_id = str(params.get("approvalId") or params.get("itemId") or "")
        kind: Any = "command"
        summary = str(params.get("command") or "")
        if method == FILE_CHANGE_APPROVAL_METHOD:
            kind = "file_change"
            summary = str(params.get("reason") or "file change")

        approval = ApprovalRequest(approval_id=approval_id, kind=kind, summary=summary, agent=self.agent_name, run_id=run.run_id, raw=params)
        future = asyncio.run_coroutine_threadsafe(decide_approval(run, approval), loop)
        try:
            return future.result(timeout=run.timeout_seconds)
        except Exception:
            future.cancel()
            return "decline"

    def start_drain_thread(self, client: CodexClient, observe: Callable[[WireItem], None]) -> None:
        """
        持续读取全局通知队列，读取线程退出时写入 WireClosed 结束标记
        """

        def drain() -> None:
            while True:
                try:
                    client.next_notification()
                except BaseException as error:
                    observe(WireClosed(error=error))
                    return

        threading.Thread(target=drain, name="common-llm-codex-drain", daemon=True).start()

    async def consume_wire(self, run: AgentRunState, mapper: CodexTurnMapper, wire_queue: "asyncio.Queue[WireItem]") -> None:
        """
        按到达顺序映射通知与审批，直到当前 turn 结束或传输层关闭
        """
        output_bytes = 0
        while not run.is_terminated:
            item = await wire_queue.get()
            if isinstance(item, WireClosed):
                raise self.map_transport_error(item.error)

            if isinstance(item, WireApproval):
                for event in mapper.map_approval(item):
                    run.emit(event)
                continue

            if isinstance(item, WireServerRequest):
                run.emit(run.factory.raw_vendor_event(item.method, item.params))
                continue

            message_bytes = estimate_wire_bytes(item.params)
            if message_bytes > self.settings.max_line_bytes:
                raise build_error(ErrorCategory.CLI_PROTOCOL_ERROR, f"Codex 单条消息超过上限 {self.settings.max_line_bytes} 字节", provider=self.agent_name)

            output_bytes += message_bytes
            if output_bytes > self.settings.max_output_bytes:
                raise build_error(ErrorCategory.CLI_PROCESS_FAILED, f"Codex 输出超过上限 {self.settings.max_output_bytes} 字节", provider=self.agent_name)

            for event in mapper.map_notification(item):
                run.emit(event)

    def map_transport_error(self, error: BaseException) -> CommonLlmError:
        """
        读取线程退出：进程关闭输出报 CLI_PROCESS_FAILED，非法 JSON-RPC 报 CLI_PROTOCOL_ERROR
        """
        if isinstance(error, TransportClosedError):
            return build_error(ErrorCategory.CLI_PROCESS_FAILED, "Codex app-server 进程在 turn 结束前退出", provider=self.agent_name, cause=error)

        return build_error(ErrorCategory.CLI_PROTOCOL_ERROR, "Codex app-server 输出不符合 JSON-RPC 协议", provider=self.agent_name, cause=error)

    def map_exception(self, error: Exception) -> CommonLlmError:
        if isinstance(error, TransportClosedError):
            return self.map_transport_error(error)

        if isinstance(error, JsonRpcError):
            return build_error(
                ErrorCategory.PROVIDER_ERROR,
                f"Codex app-server 返回错误：{error.message}",
                provider=self.agent_name,
                vendor_code=str(error.code),
                cause=error,
            )

        if isinstance(error, CodexError):
            return build_error(ErrorCategory.CLI_PROTOCOL_ERROR, "Codex app-server 通信失败", provider=self.agent_name, cause=error)

        if isinstance(error, FileNotFoundError):
            return build_error(ErrorCategory.CLI_NOT_FOUND, "找不到 Codex 可执行文件", provider=self.agent_name, cause=error)

        return build_error(ErrorCategory.INTERNAL_ERROR, "Codex 适配层内部错误", provider=self.agent_name, cause=error)

    async def release_resources(self) -> None:
        self._probe_result = None
        self._is_handshake_verified = False
