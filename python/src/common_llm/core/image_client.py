# -*- coding: utf-8 -*-
"""
ImageClient 抽象接口与 OpenAI、Gemini 图片生成共用的校验、多张循环、流式事件与错误处理流程
创建日期：2026-11-16
修改日期：2026-11-16
"""
import asyncio
import base64
import binascii
import contextlib
import time
from abc import ABC, abstractmethod
from collections.abc import AsyncGenerator, AsyncIterator
from contextlib import aclosing
from dataclasses import dataclass
from typing import Any

from common_llm.core.errors import CommonLlmError, ErrorCategory, ErrorInfo, build_error
from common_llm.core.ids import IdFactory, resolve_trace_id
from common_llm.core.model_client import BaseModelClient
from common_llm.core.model_support import merge_provider_options, sum_counts
from common_llm.core.settings import ProviderSettings
from common_llm.core.types import (
    GeneratedImage,
    ImageCompletedEvent,
    ImageErrorEvent,
    ImageEvent,
    ImageGeneratedEvent,
    ImageInput,
    ImagePartialEvent,
    ImageRequest,
    ImageResponse,
    ImageStartedEvent,
    ImageUsageEvent,
    ProviderCapability,
    Usage,
)
from common_llm.observability.logging import log_model_call

# 输出格式与 MIME 类型的对应关系，Images API 与 Responses 图片工具不直接返回 MIME 类型
OUTPUT_FORMAT_MIME_TYPES = {
    "png": "image/png",
    "jpeg": "image/jpeg",
    "jpg": "image/jpeg",
    "webp": "image/webp",
    "gif": "image/gif",
}


def resolve_output_mime_type(output_format: str | None) -> str:
    """
    按 output_format 推出 MIME 类型，缺省为 PNG
    """
    if not output_format:
        return "image/png"

    return OUTPUT_FORMAT_MIME_TYPES.get(output_format, f"image/{output_format}")


def decode_image_data(encoded: str | None) -> bytes:
    """
    解码厂商返回的 base64 图片数据
    """
    if not encoded:
        return b""

    return base64.b64decode(encoded)


def decode_image_input(image: ImageInput, provider: str) -> bytes:
    """
    解码输入图片的 base64 内容，格式非法时报 CONFIG_INVALID
    """
    try:
        return base64.b64decode(image.base64 or "", validate=True)
    except (binascii.Error, ValueError) as error:
        raise build_error(ErrorCategory.CONFIG_INVALID, "输入图片的 base64 内容不合法", provider=provider, cause=error) from error


def build_data_url(image: ImageInput) -> str:
    """
    url 原样返回，base64 转为 data URL
    """
    if image.url:
        return image.url

    return f"data:{image.mime_type};base64,{image.base64}"


@dataclass
class ImageCallContext:
    """
    单次图片生成在适配层内部使用的已校验参数
    """

    request: ImageRequest
    model: str
    trace_id: str
    timeout_seconds: float
    extra_body: dict[str, Any]
    call_count: int


@dataclass
class StreamStarted:
    """
    单次厂商调用开始
    """

    model: str | None
    response_id: str | None


@dataclass
class StreamPartial:
    """
    单次厂商调用内的部分图片，image_index 为本次调用内的序号
    """

    image_index: int
    partial_index: int
    data: bytes
    mime_type: str


@dataclass
class StreamImage:
    """
    单次厂商调用内完成的一张图片
    """

    image_index: int
    image: GeneratedImage


@dataclass
class StreamFinished:
    """
    单次厂商调用结束，response 为该次调用的完整结果，has_usage 表示厂商是否返回过用量
    """

    response: ImageResponse
    has_usage: bool


@dataclass
class StreamFailed:
    """
    库内循环中某次流式调用失败
    """

    error: CommonLlmError


StreamItem = StreamStarted | StreamPartial | StreamImage | StreamFinished


def combine_image_responses(responses: list[ImageResponse], include_raw: bool) -> ImageResponse:
    """
    合并库内循环的结果：图片按调用顺序拼接，用量逐项求和，文字按换行连接，id、model、requestId 取最后一次
    """
    if len(responses) == 1:
        return responses[0]

    images: list[GeneratedImage] = []
    texts: list[str] = []
    usage_values: dict[str, int | None] = {
        "input_tokens": 0,
        "output_tokens": 0,
        "cached_input_tokens": 0,
        "reasoning_tokens": 0,
        "total_tokens": 0,
    }
    raw_responses: list[Any] = []
    for response in responses:
        for image in response.images:
            images.append(image)

        if response.text:
            texts.append(response.text)

        for key in usage_values:
            usage_values[key] = sum_counts(usage_values[key], getattr(response.usage, key))

        raw_responses.append(response.raw_response)

    last = responses[-1]
    raw_response: list[Any] | None = None
    if include_raw:
        raw_response = raw_responses

    return ImageResponse(
        provider=last.provider,
        model=last.model,
        id=last.id,
        images=images,
        text="\n".join(texts),
        usage=Usage(**usage_values),
        trace_id=last.trace_id,
        request_id=last.request_id,
        raw_response=raw_response,
    )


class ImageEventSequencer:
    """
    为流式图片事件填充 traceId、provider 与递增 sequence
    """

    def __init__(self, provider: str, trace_id: str) -> None:
        self.provider = provider
        self.trace_id = trace_id
        self._next_sequence = 0

    def common_fields(self) -> dict[str, Any]:
        sequence = self._next_sequence
        self._next_sequence += 1
        return {
            "trace_id": self.trace_id,
            "sequence": sequence,
            "provider": self.provider,
        }

    def error(self, info: ErrorInfo) -> ImageErrorEvent:
        return ImageErrorEvent(error=info, **self.common_fields())


class ImageClient(ABC):
    """
    单个 Provider 实例的图片生成接口
    """

    @property
    @abstractmethod
    def name(self) -> str:
        """
        Provider 实例名
        """

    @abstractmethod
    async def generate(self, request: ImageRequest) -> ImageResponse:
        """
        生成图片，失败时抛出 CommonLlmError
        """

    @abstractmethod
    def stream(self, request: ImageRequest) -> AsyncIterator[ImageEvent]:
        """
        流式生成图片，错误以 error 终止事件返回
        """

    @abstractmethod
    def capability(self) -> ProviderCapability:
        """
        获取图片生成能力集合
        """

    def supports(self, feature: str) -> bool:
        """
        查询是否支持指定能力，未知能力返回 False
        """
        return feature in self.capability().features

    @abstractmethod
    def native_client(self) -> Any:
        """
        获取底层官方 SDK 客户端，与同名 ModelClient 共用
        """

    @abstractmethod
    async def close(self) -> None:
        """
        等待进行中的请求结束，SDK 客户端由同名 ModelClient 关闭
        """


class BaseImageClient(ImageClient):
    """
    图片生成适配层基类，统一处理校验、参数合并、库内循环、错误映射、日志与流终止事件
    """

    features: tuple[str, ...] = ()
    mapped_option_keys: tuple[str, ...] = ()
    # 当前路径不支持的请求字段，传入时报 SDK_UNSUPPORTED_FEATURE
    unsupported_fields: tuple[str, ...] = ()
    is_native_multiple = False
    is_url_input_supported = False
    is_partial_image_supported = False
    max_input_images: int | None = None
    max_native_n: int | None = None

    def __init__(self, settings: ProviderSettings, model_client: BaseModelClient, trace_id_factory: IdFactory | None = None) -> None:
        self.settings = settings
        self.model_client = model_client
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

    def native_client(self) -> Any:
        return self.model_client.native_client()

    @abstractmethod
    async def invoke_generate(self, context: ImageCallContext) -> ImageResponse:
        """
        完成一次厂商调用并映射响应，原生支持多张的路径在一次调用中处理 n
        """

    @abstractmethod
    def invoke_stream(self, context: ImageCallContext) -> AsyncGenerator[StreamItem, None]:
        """
        完成一次流式厂商调用，必须以 StreamFinished 结束
        """

    def map_exception(self, error: Exception) -> CommonLlmError:
        """
        复用同名 ModelClient 的错误映射
        """
        return self.model_client.map_exception(error)

    def validate_request(self, request: ImageRequest, is_stream: bool) -> None:
        """
        校验请求结构与当前路径支持的字段
        """
        if request.provider != self.name:
            raise build_error(ErrorCategory.CONFIG_INVALID, f"请求的 provider {request.provider} 与客户端 {self.name} 不一致", provider=self.name)

        if not request.prompt:
            raise build_error(ErrorCategory.CONFIG_INVALID, "prompt 不能为空", provider=self.name)

        if request.n is not None and request.n < 1:
            raise build_error(ErrorCategory.CONFIG_INVALID, "n 必须大于等于 1", provider=self.name)

        if self.max_native_n is not None and request.n is not None and request.n > self.max_native_n:
            raise build_error(ErrorCategory.CONFIG_INVALID, f"n 最大为 {self.max_native_n}", provider=self.name)

        for field_name in self.unsupported_fields:
            if getattr(request, field_name) is not None:
                raise build_error(ErrorCategory.SDK_UNSUPPORTED_FEATURE, f"当前图片生成路径不支持 {field_name}", provider=self.name)

        if request.partial_images:
            if not is_stream:
                raise build_error(ErrorCategory.SDK_UNSUPPORTED_FEATURE, "partialImages 只适用于 streamImage", provider=self.name)

            if not self.is_partial_image_supported:
                raise build_error(ErrorCategory.SDK_UNSUPPORTED_FEATURE, "当前图片生成路径不支持部分图片", provider=self.name)

        input_images: list[ImageInput] = list(request.images or [])
        if self.max_input_images is not None and len(input_images) > self.max_input_images:
            raise build_error(ErrorCategory.CONFIG_INVALID, f"输入图片最多 {self.max_input_images} 张", provider=self.name)

        if request.mask is not None:
            if not input_images:
                raise build_error(ErrorCategory.CONFIG_INVALID, "使用 mask 时必须同时提供 images", provider=self.name)

            input_images.append(request.mask)

        for image in input_images:
            self.validate_image_input(image)

    def validate_image_input(self, image: ImageInput) -> None:
        """
        校验输入图片只提供 url 或 base64 加 mimeType 其中一种
        """
        has_url = bool(image.url)
        has_base64 = bool(image.base64)
        if has_url == has_base64:
            raise build_error(ErrorCategory.CONFIG_INVALID, "输入图片必须且只能提供 url 或 base64 其中之一", provider=self.name)

        if has_base64 and not image.mime_type:
            raise build_error(ErrorCategory.CONFIG_INVALID, "无法识别输入图片的 MIME 类型，base64 图片必须提供 mimeType", provider=self.name)

        if has_url and not self.is_url_input_supported:
            raise build_error(ErrorCategory.SDK_UNSUPPORTED_FEATURE, "当前图片生成路径不支持 URL 输入图片，请改用 base64", provider=self.name)

    def build_context(self, request: ImageRequest, is_stream: bool) -> ImageCallContext:
        """
        校验请求并组装调用上下文，原生不支持多张的路径按 n 计算循环次数
        """
        if self._is_closed:
            raise build_error(ErrorCategory.CONFIG_INVALID, f"provider {self.name} 已关闭", provider=self.name)

        self.validate_request(request, is_stream)
        model = request.model or self.settings.default_image_model
        if not model:
            raise build_error(ErrorCategory.CONFIG_INVALID, f"请求未指定 model，且 provider {self.name} 未配置 default_image_model", provider=self.name)

        # 配置级 native_options 面向模型调用，图片请求只透传请求级 providerOptions
        extra_body = merge_provider_options({}, request.provider_options, self.mapped_option_keys, self.name)
        timeout_seconds = request.timeout_seconds or self.settings.image_timeout_seconds or self.settings.timeout_seconds
        call_count = 1
        if not self.is_native_multiple and request.n is not None:
            call_count = request.n

        trace_id = resolve_trace_id(request.trace_id, self._trace_id_factory)
        return ImageCallContext(
            request=request,
            model=model,
            trace_id=trace_id,
            timeout_seconds=timeout_seconds,
            extra_body=extra_body,
            call_count=call_count,
        )

    def _enter_request(self) -> None:
        self._active_requests += 1
        self._idle_event.clear()

    def _leave_request(self) -> None:
        self._active_requests -= 1
        if self._active_requests == 0:
            self._idle_event.set()

    async def invoke_with_mapping(self, context: ImageCallContext) -> ImageResponse:
        """
        完成一次调用，SDK 异常映射为 CommonLlmError
        """
        try:
            return await self.invoke_generate(context)
        except CommonLlmError:
            raise
        except Exception as error:
            raise self.map_exception(error) from error

    async def generate_looped(self, context: ImageCallContext) -> ImageResponse:
        """
        按 image_concurrency 并发执行 call_count 次调用，任一次失败即取消其余调用并返回该次错误
        """
        semaphore = asyncio.Semaphore(self.settings.image_concurrency)
        results: dict[int, ImageResponse] = {}

        async def call_once(index: int) -> None:
            async with semaphore:
                results[index] = await self.invoke_with_mapping(context)

        try:
            async with asyncio.TaskGroup() as group:
                for index in range(context.call_count):
                    group.create_task(call_once(index))
        except BaseExceptionGroup as group_error:
            raise first_common_error(group_error) from None

        ordered: list[ImageResponse] = []
        for index in range(context.call_count):
            ordered.append(results[index])

        return combine_image_responses(ordered, context.request.include_raw_response is True)

    async def generate(self, request: ImageRequest) -> ImageResponse:
        started_at = time.monotonic()
        context = self.build_context(request, False)
        self._enter_request()
        category: str | None = None
        usage: dict[str, Any] | None = None
        try:
            if context.call_count == 1:
                response = await self.invoke_with_mapping(context)
            else:
                response = await self.generate_looped(context)

            usage = response.usage.to_contract_dict()
            return response
        except CommonLlmError as error:
            category = error.category.value
            raise
        finally:
            self._leave_request()
            duration_ms = (time.monotonic() - started_at) * 1000
            log_model_call(provider=self.name, model=context.model, duration_ms=duration_ms, trace_id=context.trace_id, category=category, usage=usage)

    async def stream(self, request: ImageRequest) -> AsyncIterator[ImageEvent]:
        started_at = time.monotonic()
        trace_id = resolve_trace_id(request.trace_id, self._trace_id_factory)
        sequencer = ImageEventSequencer(self.name, trace_id)
        try:
            context = self.build_context(request.model_copy(update={"trace_id": trace_id}), True)
        except CommonLlmError as error:
            yield sequencer.error(error.info)
            return

        self._enter_request()
        category: str | None = None
        try:
            async with aclosing(self.stream_calls(context, sequencer)) as events:
                async for event in events:
                    yield event
        except CommonLlmError as error:
            category = error.category.value
            yield sequencer.error(error.info)
        except Exception as error:
            mapped = self.map_exception(error)
            category = mapped.category.value
            yield sequencer.error(mapped.info)
        finally:
            self._leave_request()
            duration_ms = (time.monotonic() - started_at) * 1000
            log_model_call(provider=self.name, model=context.model, duration_ms=duration_ms, trace_id=trace_id, category=category, usage=None)

    async def stream_calls(self, context: ImageCallContext, sequencer: ImageEventSequencer) -> AsyncGenerator[ImageEvent, None]:
        """
        按 image_concurrency 并发执行流式调用，按调用顺序输出事件：后完成的调用等前面的图片发完再输出，只发一个 started
        """
        semaphore = asyncio.Semaphore(self.settings.image_concurrency)
        queues: list[asyncio.Queue[StreamItem | StreamFailed]] = []
        tasks: list[asyncio.Task[None]] = []
        failures: list[StreamFailed] = []
        failure_event = asyncio.Event()
        for _ in range(context.call_count):
            queues.append(asyncio.Queue())

        async def run_call(index: int) -> None:
            try:
                async with semaphore:
                    async with aclosing(self.invoke_stream(context)) as items:
                        async for item in items:
                            await queues[index].put(item)
            except CommonLlmError as error:
                failures.append(StreamFailed(error=error))
                failure_event.set()
            except Exception as error:
                failures.append(StreamFailed(error=self.map_exception(error)))
                failure_event.set()

        for index in range(context.call_count):
            tasks.append(asyncio.create_task(run_call(index)))

        try:
            responses: list[ImageResponse] = []
            has_usage = False
            image_offset = 0
            is_started = False
            for index in range(context.call_count):
                finished: StreamFinished | None = None
                while finished is None:
                    item = await self.next_stream_item(queues[index], tasks[index], failures, failure_event)
                    if isinstance(item, StreamFailed):
                        raise item.error

                    if isinstance(item, StreamFinished):
                        finished = item
                    elif isinstance(item, StreamStarted):
                        if not is_started:
                            is_started = True
                            yield ImageStartedEvent(model=item.model, id=item.response_id, **sequencer.common_fields())
                    elif isinstance(item, StreamPartial):
                        yield ImagePartialEvent(
                            image_index=image_offset + item.image_index,
                            partial_index=item.partial_index,
                            data=item.data,
                            mime_type=item.mime_type,
                            **sequencer.common_fields(),
                        )
                    else:
                        yield ImageGeneratedEvent(image_index=image_offset + item.image_index, image=item.image, **sequencer.common_fields())

                responses.append(finished.response)
                has_usage = has_usage or finished.has_usage
                image_offset += len(finished.response.images)

            # 流式不提供 rawResponse
            response = combine_image_responses(responses, False).model_copy(update={"raw_response": None})
            if has_usage:
                yield ImageUsageEvent(usage=response.usage, **sequencer.common_fields())

            yield ImageCompletedEvent(response=response, **sequencer.common_fields())
        finally:
            for task in tasks:
                task.cancel()

            for task in tasks:
                with contextlib.suppress(asyncio.CancelledError):
                    await task

    async def next_stream_item(
        self,
        queue: "asyncio.Queue[StreamItem | StreamFailed]",
        task: "asyncio.Task[None]",
        failures: list[StreamFailed],
        failure_event: asyncio.Event,
    ) -> StreamItem | StreamFailed:
        """
        读取某次调用的下一个条目；任一次调用失败时立即返回该错误，调用结束却没有完成事件时报 PROVIDER_ERROR
        """
        if failures:
            return failures[0]

        if not queue.empty():
            return queue.get_nowait()

        if task.done():
            return StreamFailed(error=build_error(ErrorCategory.PROVIDER_ERROR, "图片流在收到完成事件之前结束", provider=self.name, retriable=True))

        getter = asyncio.ensure_future(queue.get())
        failure_waiter = asyncio.ensure_future(failure_event.wait())
        await asyncio.wait([getter, task, failure_waiter], return_when=asyncio.FIRST_COMPLETED)
        failure_waiter.cancel()
        if getter.done():
            return getter.result()

        getter.cancel()
        if failures:
            return failures[0]

        if not queue.empty():
            return queue.get_nowait()

        return StreamFailed(error=build_error(ErrorCategory.PROVIDER_ERROR, "图片流在收到完成事件之前结束", provider=self.name, retriable=True))

    async def close(self) -> None:
        self._is_closed = True
        await self._idle_event.wait()


def first_common_error(group_error: BaseExceptionGroup[BaseException]) -> BaseException:
    """
    从 TaskGroup 的异常组中取出第一个 CommonLlmError，其余异常原样返回
    """
    for error in group_error.exceptions:
        if isinstance(error, BaseExceptionGroup):
            return first_common_error(error)

        if isinstance(error, CommonLlmError):
            return error

    return group_error.exceptions[0]
