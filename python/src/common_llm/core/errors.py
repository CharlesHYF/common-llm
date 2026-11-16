# -*- coding: utf-8 -*-
"""
统一错误分类、ErrorInfo 结构与 CommonLlmError 异常
创建日期：2026-11-16
修改日期：2026-11-16
"""
from enum import StrEnum

from common_llm.core.contract import ContractModel
from common_llm.core.redaction import redact_text

# cause 字段保留的最大字符数，避免把超长底层消息写进错误对象
MAX_CAUSE_CHARS = 500


class ErrorCategory(StrEnum):
    """
    跨语言统一的错误分类
    """

    CONFIG_INVALID = "CONFIG_INVALID"
    AUTH_MISSING = "AUTH_MISSING"
    AUTH_FAILED = "AUTH_FAILED"
    PERMISSION_DENIED = "PERMISSION_DENIED"
    MODEL_UNAVAILABLE = "MODEL_UNAVAILABLE"
    RATE_LIMITED = "RATE_LIMITED"
    QUOTA_EXCEEDED = "QUOTA_EXCEEDED"
    TIMEOUT = "TIMEOUT"
    CANCELLED = "CANCELLED"
    NETWORK_ERROR = "NETWORK_ERROR"
    PROVIDER_ERROR = "PROVIDER_ERROR"
    SDK_UNSUPPORTED_FEATURE = "SDK_UNSUPPORTED_FEATURE"
    CLI_NOT_FOUND = "CLI_NOT_FOUND"
    CLI_VERSION_UNSUPPORTED = "CLI_VERSION_UNSUPPORTED"
    CLI_PROCESS_FAILED = "CLI_PROCESS_FAILED"
    CLI_PROTOCOL_ERROR = "CLI_PROTOCOL_ERROR"
    AGENT_APPROVAL_REQUIRED = "AGENT_APPROVAL_REQUIRED"
    SANDBOX_DENIED = "SANDBOX_DENIED"
    INTERNAL_ERROR = "INTERNAL_ERROR"


RETRIABLE_CATEGORIES = (
    ErrorCategory.RATE_LIMITED,
    ErrorCategory.TIMEOUT,
    ErrorCategory.NETWORK_ERROR,
)


class ErrorInfo(ContractModel):
    """
    脱敏后的错误详情，message 与 cause 不含密钥与 prompt 正文
    """

    category: ErrorCategory
    message: str
    provider: str | None = None
    retriable: bool = False
    request_id: str | None = None
    vendor_code: str | None = None
    status_code: int | None = None
    cause: str | None = None


class CommonLlmError(Exception):
    """
    本库对外抛出的唯一异常类型，携带 ErrorInfo
    """

    def __init__(self, info: ErrorInfo) -> None:
        super().__init__(info.message)
        self.info = info

    @property
    def category(self) -> ErrorCategory:
        return self.info.category

    def __str__(self) -> str:
        return f"[{self.info.category.value}] {self.info.message}"

    def __repr__(self) -> str:
        return f"CommonLlmError(category={self.info.category.value!r}, provider={self.info.provider!r}, message={self.info.message!r})"


def describe_cause(error: BaseException | None) -> str | None:
    """
    把底层异常转为脱敏后的 "类型: 消息" 文本
    """
    if error is None:
        return None

    text = f"{type(error).__name__}: {error}"
    redacted = redact_text(text) or ""
    return redacted[:MAX_CAUSE_CHARS]


def build_error_info(
    category: ErrorCategory,
    message: str,
    *,
    provider: str | None = None,
    retriable: bool | None = None,
    request_id: str | None = None,
    vendor_code: str | None = None,
    status_code: int | None = None,
    cause: BaseException | None = None,
) -> ErrorInfo:
    """
    组装 ErrorInfo，retriable 未指定时按分类取默认值，消息统一脱敏
    """
    resolved_retriable = retriable
    if resolved_retriable is None:
        resolved_retriable = category in RETRIABLE_CATEGORIES

    return ErrorInfo(
        category=category,
        message=redact_text(message) or "",
        provider=provider,
        retriable=resolved_retriable,
        request_id=request_id,
        vendor_code=vendor_code,
        status_code=status_code,
        cause=describe_cause(cause),
    )


def build_error(
    category: ErrorCategory,
    message: str,
    *,
    provider: str | None = None,
    retriable: bool | None = None,
    request_id: str | None = None,
    vendor_code: str | None = None,
    status_code: int | None = None,
    cause: BaseException | None = None,
) -> CommonLlmError:
    """
    组装 CommonLlmError，参数含义同 build_error_info
    """
    info = build_error_info(
        category,
        message,
        provider=provider,
        retriable=retriable,
        request_id=request_id,
        vendor_code=vendor_code,
        status_code=status_code,
        cause=cause,
    )
    return CommonLlmError(info)


def classify_http_status(status_code: int, is_quota: bool, is_auth_failure: bool) -> tuple[ErrorCategory, bool]:
    """
    按 HTTP 状态码与厂商错误码返回错误分类与是否可重试
    """
    if status_code == 401 or is_auth_failure:
        return ErrorCategory.AUTH_FAILED, False

    if status_code == 403:
        return ErrorCategory.PERMISSION_DENIED, False

    if status_code == 404:
        return ErrorCategory.MODEL_UNAVAILABLE, False

    if status_code == 408:
        return ErrorCategory.TIMEOUT, True

    if status_code == 429 and is_quota:
        return ErrorCategory.QUOTA_EXCEEDED, False

    if status_code == 429:
        return ErrorCategory.RATE_LIMITED, True

    if status_code >= 500:
        return ErrorCategory.PROVIDER_ERROR, True

    return ErrorCategory.PROVIDER_ERROR, False
