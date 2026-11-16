# -*- coding: utf-8 -*-
"""
基于 claude-agent-sdk 的 Claude Code Agent 适配器
创建日期：2026-11-16
修改日期：2026-11-16
"""
import asyncio
import dataclasses
import json
import re
from collections import deque
from dataclasses import dataclass
from typing import Any, cast

from claude_agent_sdk import (
    ClaudeAgentOptions,
    ClaudeSDKClient,
    ClaudeSDKError,
    CLIConnectionError,
    CLIJSONDecodeError,
    CLINotFoundError,
    PermissionResultAllow,
    PermissionResultDeny,
    ProcessError,
    ToolPermissionContext,
)
from claude_agent_sdk.types import PermissionResult

from common_llm.agents.claude_code.mapper import ClaudeMessageMapper, PendingApproval
from common_llm.core.agent_client import AgentRunState, BaseAgentClient, decide_approval
from common_llm.core.agent_support import (
    build_isolated_environment,
    probe_version,
    resolve_executable,
    run_probe_command,
)
from common_llm.core.errors import CommonLlmError, ErrorCategory, build_error
from common_llm.core.ids import IdFactory
from common_llm.core.settings import AgentSettings
from common_llm.core.types import (
    FEATURE_APPROVALS,
    FEATURE_COST,
    FEATURE_INTERRUPT,
    FEATURE_SESSION_RESUME,
    FEATURE_STREAMING,
    AgentCapability,
    AgentName,
    ApprovalRequest,
)
from common_llm.observability.logging import log_dangerous_mode

MINIMUM_CLAUDE_VERSION = (2, 1, 325)
DEFAULT_PERMISSION_MODE = "default"
PERMISSION_MODE_FLAG = "--permission-mode"
OPTION_PATTERN = re.compile(r"(?<![\w-])(--[a-zA-Z][\w-]*)")
CHOICES_PATTERN = re.compile(r"\(choices:\s*([^)]*)\)")
QUOTED_PATTERN = re.compile(r"\"([^\"]+)\"")
NEXT_OPTION_PATTERN = re.compile(r"\n\s+-")
# nativeOptions 中由统一请求字段负责的键，不允许透传覆盖
RESERVED_NATIVE_OPTION_KEYS = (
    "cwd",
    "resume",
    "permission_mode",
    "allowed_tools",
    "cli_path",
    "env",
    "can_use_tool",
    "model",
    "max_buffer_size",
    "stderr",
    "permission_prompt_tool_name",
)
# 需要 CLI 支持对应参数才能透传的 nativeOptions 键
OPTION_FLAG_REQUIREMENTS = {
    "max_turns": "--max-turns",
    "max_budget_usd": "--max-budget-usd",
    "fallback_model": "--fallback-model",
    "add_dirs": "--add-dir",
    "disallowed_tools": "--disallowed-tools",
}
EVENT_TYPES = [
    "started",
    "message",
    "tool_started",
    "tool_completed",
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
    FEATURE_COST,
]


@dataclass
class ClaudeProbeResult:
    """
    运行时探测结果
    """

    executable_path: str
    version: str
    supported_options: list[str]
    permission_modes: list[str] | None


def parse_help_options(help_text: str) -> list[str]:
    """
    从 claude -p --help 输出中提取全部长参数名
    """
    options: list[str] = []
    for match in OPTION_PATTERN.finditer(help_text):
        option = match.group(1)
        if option not in options:
            options.append(option)

    return options


def parse_permission_modes(help_text: str) -> list[str] | None:
    """
    从 --permission-mode 的说明中提取可选值，找不到 choices 时返回 None
    """
    start = help_text.find(PERMISSION_MODE_FLAG)
    if start < 0:
        return None

    section = help_text[start:]
    next_option = NEXT_OPTION_PATTERN.search(section, len(PERMISSION_MODE_FLAG))
    if next_option is not None:
        section = section[: next_option.start()]

    choices_match = CHOICES_PATTERN.search(section)
    if choices_match is None:
        return None

    modes: list[str] = []
    for quoted in QUOTED_PATTERN.finditer(choices_match.group(1)):
        modes.append(quoted.group(1))

    return modes


class StderrTail:
    """
    只保留 stderr 末尾指定字节数，用于诊断
    """

    def __init__(self, max_bytes: int) -> None:
        self.max_bytes = max_bytes
        self._lines: deque[str] = deque()
        self._size = 0

    def append(self, line: str) -> None:
        self._lines.append(line)
        self._size += len(line.encode("utf-8")) + 1
        while self._size > self.max_bytes and self._lines:
            removed = self._lines.popleft()
            self._size -= len(removed.encode("utf-8")) + 1

    def text(self) -> str:
        return "\n".join(self._lines)


class ClaudeCodeAgentClient(BaseAgentClient):
    """
    Claude Code 适配器，使用 ClaudeSDKClient 执行 run，支持流式事件、恢复、审批与中断
    """

    agent_name: AgentName = "claude_code"

    def __init__(
        self,
        settings: AgentSettings,
        *,
        run_id_factory: IdFactory | None = None,
        trace_id_factory: IdFactory | None = None,
    ) -> None:
        super().__init__(settings, run_id_factory=run_id_factory, trace_id_factory=trace_id_factory)
        self._probe_result: ClaudeProbeResult | None = None
        self._probe_lock = asyncio.Lock()

    async def ensure_probe(self) -> ClaudeProbeResult:
        """
        解析可执行文件、校验版本并解析 -p --help 参数集合，结果缓存到 close 为止
        """
        async with self._probe_lock:
            if self._probe_result is not None:
                return self._probe_result

            executable = resolve_executable(self.settings.executable_path, ["claude"], self.agent_name)
            version = await probe_version(executable, MINIMUM_CLAUDE_VERSION, self.agent_name)
            help_text = await run_probe_command(executable, ["-p", "--help"], self.agent_name)
            self._probe_result = ClaudeProbeResult(
                executable_path=executable,
                version=version,
                supported_options=parse_help_options(help_text),
                permission_modes=parse_permission_modes(help_text),
            )
            return self._probe_result

    async def capabilities(self) -> AgentCapability:
        probe = await self.ensure_probe()
        return AgentCapability(
            agent=self.agent_name,
            implementation="sdk",
            executable_path=probe.executable_path,
            version=probe.version,
            features=list(FEATURES),
            event_types=list(EVENT_TYPES),
            supported_options=list(probe.supported_options),
        )

    def resolve_permission_mode(self, run: AgentRunState, probe: ClaudeProbeResult) -> str | None:
        """
        按运行时探测结果决定是否传 --permission-mode，CLI 不支持 default 时不传
        """
        mode = run.request.permission_mode or self.settings.permission_mode or DEFAULT_PERMISSION_MODE
        is_flag_supported = PERMISSION_MODE_FLAG in probe.supported_options
        is_value_supported = probe.permission_modes is None or mode in probe.permission_modes
        if is_flag_supported and is_value_supported:
            if mode == "bypassPermissions":
                log_dangerous_mode(self.agent_name, mode)

            return mode

        if mode == DEFAULT_PERMISSION_MODE:
            return None

        raise build_error(
            ErrorCategory.SDK_UNSUPPORTED_FEATURE,
            f"当前 Claude Code 版本不支持 permission_mode={mode}",
            provider=self.agent_name,
        )

    def build_native_option_values(self, run: AgentRunState, probe: ClaudeProbeResult) -> dict[str, Any]:
        """
        校验 nativeOptions 并返回可直接写入 ClaudeAgentOptions 的字段
        """
        option_field_names: list[str] = []
        for option_field in dataclasses.fields(ClaudeAgentOptions):
            option_field_names.append(option_field.name)

        values: dict[str, Any] = {}
        for key, value in self.merge_native_options(run.request).items():
            if key in RESERVED_NATIVE_OPTION_KEYS:
                raise build_error(ErrorCategory.CONFIG_INVALID, f"nativeOptions.{key} 由统一请求字段负责，不能透传", provider=self.agent_name)

            if key not in option_field_names:
                raise build_error(ErrorCategory.CONFIG_INVALID, f"nativeOptions.{key} 不是 ClaudeAgentOptions 支持的字段", provider=self.agent_name)

            required_flag = OPTION_FLAG_REQUIREMENTS.get(key)
            if required_flag is not None and required_flag not in probe.supported_options:
                raise build_error(
                    ErrorCategory.SDK_UNSUPPORTED_FEATURE,
                    f"当前 Claude Code 版本不支持 {required_flag}，无法使用 nativeOptions.{key}",
                    provider=self.agent_name,
                )

            values[key] = value

        return values

    def build_environment(self, run: AgentRunState) -> dict[str, str]:
        """
        组装子进程环境变量，api_key 模式注入 ANTHROPIC_API_KEY
        """
        extra_values: dict[str, str] = {
            "CLAUDE_CODE_ENTRYPOINT": "sdk-py",
        }
        if self.settings.auth_mode == "api_key":
            if not self.settings.api_key:
                raise build_error(ErrorCategory.AUTH_MISSING, "auth_mode=api_key 但未配置 api_key", provider=self.agent_name)

            extra_values["ANTHROPIC_API_KEY"] = self.settings.api_key

        return build_isolated_environment(self.resolve_env_allowlist(run.request), extra_values)

    def build_options(self, run: AgentRunState, probe: ClaudeProbeResult, mapper: ClaudeMessageMapper, stderr_tail: StderrTail) -> ClaudeAgentOptions:
        """
        组装 ClaudeAgentOptions，审批统一经由 can_use_tool 回调
        """

        async def can_use_tool(tool_name: str, tool_input: dict[str, Any], context: ToolPermissionContext) -> PermissionResult:
            approval_id = context.tool_use_id or ""
            approval = ApprovalRequest(
                approval_id=approval_id,
                kind="tool",
                summary=tool_name,
                agent=self.agent_name,
                run_id=run.run_id,
                raw={
                    "tool_name": tool_name,
                    "input": tool_input,
                },
            )
            decision = await decide_approval(run, approval)
            for event in mapper.record_approval(PendingApproval(approval_id=approval_id, summary=tool_name, decision=decision)):
                run.emit(event)

            if decision == "approve":
                return PermissionResultAllow()

            if decision == "approve_for_session":
                return PermissionResultAllow(updated_permissions=list(context.suggestions))

            return PermissionResultDeny(message="审批处理器拒绝了该工具调用", interrupt=decision == "cancel")

        allowed_tools = self.settings.allowed_tools
        if run.request.allowed_tools is not None:
            allowed_tools = run.request.allowed_tools

        option_values: dict[str, Any] = {
            "cwd": run.working_directory,
            "cli_path": probe.executable_path,
            "env": self.build_environment(run),
            "allowed_tools": list(allowed_tools),
            "can_use_tool": can_use_tool,
            "include_partial_messages": True,
            "max_buffer_size": self.settings.max_line_bytes,
            "stderr": stderr_tail.append,
        }
        permission_mode = self.resolve_permission_mode(run, probe)
        if permission_mode is not None:
            option_values["permission_mode"] = permission_mode

        if run.request.model:
            option_values["model"] = run.request.model

        if run.request.session_id:
            option_values["resume"] = run.request.session_id

        for key, value in self.build_native_option_values(run, probe).items():
            option_values[key] = value

        return ClaudeAgentOptions(**option_values)

    async def execute_run(self, run: AgentRunState) -> None:
        probe = await self.ensure_probe()
        mapper = ClaudeMessageMapper(run.factory, self.settings.max_tool_output_chars)
        stderr_tail = StderrTail(self.settings.max_stderr_bytes)
        options = self.build_options(run, probe, mapper, stderr_tail)
        client = ClaudeSDKClient(options=options)
        output_bytes = 0

        async def interrupt_client() -> None:
            await client.interrupt()

        try:
            await client.connect()
            run.interrupt_callback = interrupt_client
            if run.is_interrupt_requested:
                await client.interrupt()

            await client.query(run.request.prompt)
            async for message in client.receive_response():
                output_bytes += estimate_message_bytes(message)
                if output_bytes > self.settings.max_output_bytes:
                    raise build_error(
                        ErrorCategory.CLI_PROCESS_FAILED,
                        f"Claude Code 输出超过上限 {self.settings.max_output_bytes} 字节",
                        provider=self.agent_name,
                    )

                for event in mapper.map_message(message, run.is_interrupt_requested):
                    run.emit(event)

                if run.is_terminated:
                    return

            raise build_error(ErrorCategory.CLI_PROCESS_FAILED, "Claude Code 进程结束但没有返回 result", provider=self.agent_name)
        except ClaudeSDKError as error:
            raise self.map_sdk_error(error, stderr_tail) from error
        finally:
            run.interrupt_callback = None
            await client.disconnect()

    def map_sdk_error(self, error: ClaudeSDKError, stderr_tail: StderrTail) -> CommonLlmError:
        """
        把 SDK 异常映射为统一错误分类，附带 stderr 末尾内容
        """
        stderr_text = stderr_tail.text()
        suffix = ""
        if stderr_text:
            suffix = f"；stderr：{stderr_text}"

        if isinstance(error, CLINotFoundError):
            return build_error(ErrorCategory.CLI_NOT_FOUND, f"找不到 Claude Code 可执行文件{suffix}", provider=self.agent_name, cause=error)

        if isinstance(error, CLIJSONDecodeError):
            return build_error(ErrorCategory.CLI_PROTOCOL_ERROR, f"Claude Code 输出不符合 stream-json 协议{suffix}", provider=self.agent_name, cause=error)

        if isinstance(error, ProcessError):
            return build_error(
                ErrorCategory.CLI_PROCESS_FAILED,
                f"Claude Code 进程异常退出，退出码 {error.exit_code}{suffix}",
                provider=self.agent_name,
                status_code=error.exit_code,
                cause=error,
            )

        if isinstance(error, CLIConnectionError):
            return build_error(ErrorCategory.CLI_PROCESS_FAILED, f"Claude Code 进程通信失败{suffix}", provider=self.agent_name, cause=error)

        return build_error(ErrorCategory.CLI_PROTOCOL_ERROR, f"Claude Code 消息解析失败{suffix}", provider=self.agent_name, cause=error)

    def map_exception(self, error: Exception) -> CommonLlmError:
        if isinstance(error, ClaudeSDKError):
            return self.map_sdk_error(error, StderrTail(0))

        return build_error(ErrorCategory.INTERNAL_ERROR, "Claude Code 适配层内部错误", provider=self.agent_name, cause=error)

    async def release_resources(self) -> None:
        self._probe_result = None


def estimate_message_bytes(message: Any) -> int:
    """
    估算单条消息的输出字节数，SDK 不暴露原始行，按消息字段重新序列化计算
    """
    try:
        payload = dataclasses.asdict(cast(Any, message))
    except TypeError:
        return 0

    return len(json.dumps(payload, ensure_ascii=False, default=str).encode("utf-8"))
