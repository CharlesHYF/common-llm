# -*- coding: utf-8 -*-
"""
Agent 适配层共用的工作目录校验、环境变量组装、运行时探测、并发限制与事件缓冲工具
创建日期：2026-11-16
修改日期：2026-11-16
"""
import asyncio
import os
import re
import shutil
from collections import deque
from collections.abc import Iterable
from dataclasses import dataclass
from typing import Any

from common_llm.core.errors import ErrorCategory, ErrorInfo, build_error
from common_llm.core.types import (
    AgentApprovalRequestedEvent,
    AgentCancelledEvent,
    AgentCompletedEvent,
    AgentEvent,
    AgentFailedEvent,
    AgentFileChangedEvent,
    AgentMessageEvent,
    AgentName,
    AgentRawVendorEvent,
    AgentResult,
    AgentStartedEvent,
    AgentStatus,
    AgentToolCompletedEvent,
    AgentToolStartedEvent,
    AgentUsage,
    AgentUsageEvent,
    ApprovalDecision,
    ApprovalKind,
    FileChange,
)

# 子进程运行必需的环境变量，始终继承
BASE_ENV_NAMES = (
    "PATH",
    "HOME",
    "USER",
    "LOGNAME",
    "SHELL",
    "TMPDIR",
    "TEMP",
    "TMP",
    "LANG",
    "LC_ALL",
    "LC_CTYPE",
    "TERM",
    "SYSTEMROOT",
    "SystemRoot",
    "COMSPEC",
    "PATHEXT",
    "USERPROFILE",
    "APPDATA",
    "LOCALAPPDATA",
)
VERSION_PATTERN = re.compile(r"(\d+)\.(\d+)\.(\d+)")


def resolve_working_directory(requested: str | None, configured: str | None, agent: str) -> str:
    """
    取请求或配置中的工作目录，校验存在且为目录后返回真实绝对路径
    """
    directory = requested or configured
    if not directory:
        raise build_error(ErrorCategory.CONFIG_INVALID, f"{agent} 未指定工作目录，请在请求或配置中提供 working_directory", provider=agent)

    resolved = os.path.realpath(os.path.expanduser(directory))
    if not os.path.exists(resolved):
        raise build_error(ErrorCategory.CONFIG_INVALID, f"工作目录不存在：{resolved}", provider=agent)

    if not os.path.isdir(resolved):
        raise build_error(ErrorCategory.CONFIG_INVALID, f"工作目录不是目录：{resolved}", provider=agent)

    return resolved


def build_isolated_environment(env_allowlist: Iterable[str], extra_values: dict[str, str]) -> dict[str, str]:
    """
    组装传给 SDK 的环境变量覆盖表，未列入白名单的继承变量置为空字符串
    """
    allowed_names = set(BASE_ENV_NAMES)
    for name in env_allowlist:
        allowed_names.add(name)

    overrides: dict[str, str] = {}
    for name in os.environ:
        if name in allowed_names:
            continue

        overrides[name] = ""

    for name, value in extra_values.items():
        overrides[name] = value

    return overrides


def resolve_executable(configured: str | None, fallback_candidates: Iterable[str], agent: str) -> str:
    """
    解析可执行文件路径，配置值优先，找不到时报 CLI_NOT_FOUND
    """
    candidates: list[str] = []
    if configured:
        candidates.append(configured)
    else:
        for candidate in fallback_candidates:
            candidates.append(candidate)

    for candidate in candidates:
        if os.path.sep in candidate or (os.path.altsep is not None and os.path.altsep in candidate):
            expanded = os.path.realpath(os.path.expanduser(candidate))
            if os.path.isfile(expanded) and os.access(expanded, os.X_OK):
                return expanded
            continue

        located = shutil.which(candidate)
        if located is not None:
            return os.path.realpath(located)

    raise build_error(ErrorCategory.CLI_NOT_FOUND, f"找不到 {agent} 可执行文件：{', '.join(candidates)}", provider=agent)


def parse_version(text: str) -> tuple[int, int, int] | None:
    """
    从版本输出中解析第一个 x.y.z 版本号
    """
    match = VERSION_PATTERN.search(text)
    if match is None:
        return None

    return int(match.group(1)), int(match.group(2)), int(match.group(3))


def format_version(version: tuple[int, int, int]) -> str:
    """
    把版本元组格式化为 x.y.z
    """
    return f"{version[0]}.{version[1]}.{version[2]}"


async def run_probe_command(executable: str, arguments: list[str], agent: str) -> str:
    """
    执行探测命令并返回 stdout 与 stderr 合并后的文本，超时或无法执行时报错
    """
    try:
        process = await asyncio.create_subprocess_exec(
            executable,
            *arguments,
            stdin=asyncio.subprocess.DEVNULL,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
    except FileNotFoundError as error:
        raise build_error(ErrorCategory.CLI_NOT_FOUND, f"找不到 {agent} 可执行文件：{executable}", provider=agent, cause=error) from error
    except PermissionError as error:
        raise build_error(ErrorCategory.CLI_NOT_FOUND, f"{agent} 可执行文件无法执行：{executable}", provider=agent, cause=error) from error

    try:
        stdout, stderr = await asyncio.wait_for(process.communicate(), 30)
    except TimeoutError as error:
        process.kill()
        await process.wait()
        raise build_error(ErrorCategory.TIMEOUT, f"{agent} 探测命令超时：{' '.join(arguments)}", provider=agent, cause=error) from error

    return stdout.decode("utf-8", errors="replace") + stderr.decode("utf-8", errors="replace")


async def probe_version(executable: str, minimum: tuple[int, int, int], agent: str) -> str:
    """
    执行 --version 并校验最低版本，低于最低版本时报 CLI_VERSION_UNSUPPORTED
    """
    output = await run_probe_command(executable, ["--version"], agent)
    version = parse_version(output)
    if version is None:
        raise build_error(ErrorCategory.CLI_VERSION_UNSUPPORTED, f"无法从 {agent} --version 输出中解析版本号", provider=agent)

    if version < minimum:
        raise build_error(
            ErrorCategory.CLI_VERSION_UNSUPPORTED,
            f"{agent} 版本 {format_version(version)} 低于最低支持版本 {format_version(minimum)}",
            provider=agent,
        )

    return format_version(version)


def truncate_by_code_points(text: str | None, limit: int) -> str | None:
    """
    按 Unicode 码点截断工具输出
    """
    if text is None:
        return None

    if len(text) <= limit:
        return text

    return text[:limit]


class AgentRunLimiter:
    """
    限制单个 AgentClient 的同时运行数，超出时报 RATE_LIMITED
    """

    def __init__(self, agent: str, max_concurrent_runs: int) -> None:
        self.agent = agent
        self.max_concurrent_runs = max_concurrent_runs
        self._active_run_ids: set[str] = set()

    def acquire(self, run_id: str) -> None:
        if len(self._active_run_ids) >= self.max_concurrent_runs:
            raise build_error(
                ErrorCategory.RATE_LIMITED,
                f"{self.agent} 同时运行数已达上限 {self.max_concurrent_runs}",
                provider=self.agent,
                retriable=True,
            )

        self._active_run_ids.add(run_id)

    def release(self, run_id: str) -> None:
        self._active_run_ids.discard(run_id)

    @property
    def active_count(self) -> int:
        return len(self._active_run_ids)


class AgentEventFactory:
    """
    组装带公共字段的 AgentEvent，sequence 从 0 递增
    """

    def __init__(self, agent: AgentName, trace_id: str, run_id: str) -> None:
        self.agent: AgentName = agent
        self.trace_id = trace_id
        self.run_id = run_id
        self.session_id: str | None = None
        self._next_sequence = 0

    def _common_fields(self) -> dict[str, Any]:
        sequence = self._next_sequence
        self._next_sequence += 1
        return {
            "trace_id": self.trace_id,
            "run_id": self.run_id,
            "session_id": self.session_id,
            "sequence": sequence,
            "agent": self.agent,
        }

    def started(self, model: str | None, working_directory: str) -> AgentStartedEvent:
        return AgentStartedEvent(model=model, working_directory=working_directory, **self._common_fields())

    def message(self, text: str, is_delta: bool) -> AgentMessageEvent:
        return AgentMessageEvent(role="assistant", text=text, is_delta=is_delta, **self._common_fields())

    def tool_started(self, tool_call_id: str, tool_name: str, tool_input: dict[str, Any] | None) -> AgentToolStartedEvent:
        return AgentToolStartedEvent(tool_call_id=tool_call_id, tool_name=tool_name, input=tool_input, **self._common_fields())

    def tool_completed(self, tool_call_id: str, tool_name: str | None, output: str | None, is_error: bool) -> AgentToolCompletedEvent:
        return AgentToolCompletedEvent(tool_call_id=tool_call_id, tool_name=tool_name, output=output, is_error=is_error, **self._common_fields())

    def file_changed(self, changes: list[FileChange]) -> AgentFileChangedEvent:
        return AgentFileChangedEvent(changes=changes, **self._common_fields())

    def approval_requested(self, approval_id: str, kind: ApprovalKind, summary: str, decision: ApprovalDecision) -> AgentApprovalRequestedEvent:
        return AgentApprovalRequestedEvent(approval_id=approval_id, kind=kind, summary=summary, decision=decision, **self._common_fields())

    def usage(self, usage: AgentUsage) -> AgentUsageEvent:
        return AgentUsageEvent(usage=usage, **self._common_fields())

    def completed(self, result: AgentResult) -> AgentCompletedEvent:
        return AgentCompletedEvent(result=result, **self._common_fields())

    def failed(self, error: ErrorInfo) -> AgentFailedEvent:
        return AgentFailedEvent(error=error, **self._common_fields())

    def cancelled(self) -> AgentCancelledEvent:
        return AgentCancelledEvent(**self._common_fields())

    def raw_vendor_event(self, vendor_type: str, payload: dict[str, Any]) -> AgentRawVendorEvent:
        return AgentRawVendorEvent(vendor_type=vendor_type, payload=payload, **self._common_fields())

    def build_result(self, status: AgentStatus, final_text: str, usage: AgentUsage | None, error: ErrorInfo | None) -> AgentResult:
        return AgentResult(
            run_id=self.run_id,
            session_id=self.session_id,
            status=status,
            final_text=final_text,
            usage=usage,
            error=error,
        )


@dataclass
class ChannelClosed:
    """
    事件通道结束标记
    """


class AgentEventChannel:
    """
    生产者与消费者之间的事件缓冲区，超过上限时丢弃最早的 raw_vendor_event
    """

    def __init__(self, max_buffered_events: int) -> None:
        self.max_buffered_events = max_buffered_events
        self._events: deque[AgentEvent | ChannelClosed] = deque()
        self._signal = asyncio.Event()
        self.dropped_count = 0

    def put(self, event: AgentEvent) -> None:
        if len(self._events) >= self.max_buffered_events:
            self._drop_oldest_raw_event()

        self._events.append(event)
        self._signal.set()

    def _drop_oldest_raw_event(self) -> None:
        for index, buffered in enumerate(self._events):
            if isinstance(buffered, AgentRawVendorEvent):
                del self._events[index]
                self.dropped_count += 1
                return

    def close(self) -> None:
        self._events.append(ChannelClosed())
        self._signal.set()

    async def get(self) -> AgentEvent | None:
        while not self._events:
            self._signal.clear()
            await self._signal.wait()

        item = self._events.popleft()
        if isinstance(item, ChannelClosed):
            return None

        return item
