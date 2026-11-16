# -*- coding: utf-8 -*-
"""
OpenAI 图片生成适配器，支持 Images API 与 Responses API image_generation 工具两条路径
创建日期：2026-11-16
修改日期：2026-11-16
"""
from collections.abc import AsyncGenerator
from typing import Any

from openai import AsyncOpenAI

from common_llm.core.errors import ErrorCategory, build_error
from common_llm.core.image_client import (
    BaseImageClient,
    ImageCallContext,
    StreamFinished,
    StreamImage,
    StreamItem,
    StreamPartial,
    StreamStarted,
    build_data_url,
    decode_image_data,
    decode_image_input,
    resolve_output_mime_type,
)
from common_llm.core.model_support import dump_sdk_object, read_mapping
from common_llm.core.types import (
    FEATURE_IMAGE_EDIT,
    FEATURE_IMAGE_GENERATION,
    FEATURE_IMAGE_MASK,
    FEATURE_IMAGE_MULTIPLE,
    FEATURE_IMAGE_STREAMING,
    GeneratedImage,
    ImageInput,
    ImageResponse,
    Usage,
)
from common_llm.providers.openai.client import read_request_id, read_stream_request_id
from common_llm.providers.openai.responses_mapping import map_responses_usage

# 请求字段与 OpenAI 图片参数名的对应关系，Images API 与 Responses 图片工具相同
OPTIONAL_IMAGE_PARAMS = (
    ("size", "size"),
    ("quality", "quality"),
    ("output_format", "output_format"),
    ("output_compression", "output_compression"),
    ("background", "background"),
)
IMAGES_MAPPED_KEYS = (
    "prompt",
    "model",
    "image",
    "mask",
    "n",
    "size",
    "quality",
    "output_format",
    "output_compression",
    "background",
    "partial_images",
    "stream",
)
RESPONSES_IMAGE_MAPPED_KEYS = (
    "model",
    "input",
    "tools",
    "tool_choice",
    "stream",
)


def map_images_usage(usage: dict[str, Any] | None) -> Usage:
    """
    Images API 的用量明细只有 text_tokens 与 image_tokens，缓存与推理用量为 None
    """
    raw_usage = usage or {}
    return Usage(
        input_tokens=raw_usage.get("input_tokens"),
        output_tokens=raw_usage.get("output_tokens"),
        total_tokens=raw_usage.get("total_tokens"),
    )


def build_upload_file(image: ImageInput, index: int, provider: str) -> tuple[str, bytes, str]:
    """
    把输入图片转为 SDK FileTypes 的 (文件名, 字节, content_type) 形式
    """
    mime_type = image.mime_type or "image/png"
    extension = mime_type.split("/")[-1]
    return f"image-{index}.{extension}", decode_image_input(image, provider), mime_type


def add_optional_image_params(params: dict[str, Any], context: ImageCallContext) -> None:
    """
    把请求中出现的 size、quality 等字段写入参数
    """
    for request_field, param_name in OPTIONAL_IMAGE_PARAMS:
        value = getattr(context.request, request_field)
        if value is not None:
            params[param_name] = value


class OpenAiImagesClient(BaseImageClient):
    """
    image_api=images：无输入图片时调用 images.generate，有输入图片时调用 images.edit，原生支持 n
    """

    features = (FEATURE_IMAGE_GENERATION, FEATURE_IMAGE_EDIT, FEATURE_IMAGE_MASK, FEATURE_IMAGE_STREAMING, FEATURE_IMAGE_MULTIPLE)
    mapped_option_keys = IMAGES_MAPPED_KEYS
    unsupported_fields = ("aspect_ratio", "image_size")
    is_native_multiple = True
    is_partial_image_supported = True
    max_input_images = 16
    max_native_n = 10

    def build_params(self, context: ImageCallContext, is_stream: bool) -> dict[str, Any]:
        """
        组装 images.generate 或 images.edit 的参数，providerOptions 经 extra_body 透传
        """
        request = context.request
        params: dict[str, Any] = {
            "prompt": request.prompt,
            "model": context.model,
            "timeout": context.timeout_seconds,
        }
        if request.n is not None:
            params["n"] = request.n

        add_optional_image_params(params, context)
        if is_stream:
            params["stream"] = True
            if request.partial_images is not None:
                params["partial_images"] = request.partial_images

        if request.images:
            upload_files: list[tuple[str, bytes, str]] = []
            for index, image in enumerate(request.images):
                upload_files.append(build_upload_file(image, index, self.name))

            if len(upload_files) == 1:
                params["image"] = upload_files[0]
            else:
                params["image"] = upload_files

        if request.mask is not None:
            params["mask"] = build_upload_file(request.mask, 0, self.name)

        if context.extra_body:
            params["extra_body"] = context.extra_body

        return params

    def select_method(self, context: ImageCallContext) -> Any:
        """
        有输入图片时选择 images.edit，否则选择 images.generate
        """
        client: AsyncOpenAI = self.native_client()
        if context.request.images:
            return client.images.edit

        return client.images.generate

    def map_response(self, response: dict[str, Any], context: ImageCallContext, request_id: str | None) -> ImageResponse:
        """
        映射 ImagesResponse，MIME 类型由 output_format 推出
        """
        mime_type = resolve_output_mime_type(response.get("output_format"))
        images: list[GeneratedImage] = []
        for item in response.get("data") or []:
            images.append(
                GeneratedImage(
                    data=decode_image_data(item.get("b64_json")),
                    mime_type=mime_type,
                    url=item.get("url"),
                    revised_prompt=item.get("revised_prompt"),
                )
            )

        raw_response: dict[str, Any] | None = None
        if context.request.include_raw_response is True:
            raw_response = response

        return ImageResponse(
            provider=self.name,
            model=context.model,
            id=None,
            images=images,
            text="",
            usage=map_images_usage(response.get("usage")),
            trace_id=context.trace_id,
            request_id=request_id,
            raw_response=raw_response,
        )

    async def invoke_generate(self, context: ImageCallContext) -> ImageResponse:
        method = self.select_method(context)
        response = await method(**self.build_params(context, False))
        return self.map_response(dump_sdk_object(response), context, read_request_id(response))

    async def invoke_stream(self, context: ImageCallContext) -> AsyncGenerator[StreamItem, None]:
        method = self.select_method(context)
        stream = await method(**self.build_params(context, True))
        request_id = read_stream_request_id(stream)
        images: list[GeneratedImage] = []
        raw_usage: dict[str, Any] | None = None
        try:
            yield StreamStarted(model=context.model, response_id=None)
            async for raw_event in stream:
                event = dump_sdk_object(raw_event)
                event_type = str(event.get("type") or "")
                mime_type = resolve_output_mime_type(event.get("output_format"))
                if event_type.endswith(".partial_image"):
                    yield StreamPartial(
                        image_index=len(images),
                        partial_index=int(event.get("partial_image_index") or 0),
                        data=decode_image_data(event.get("b64_json")),
                        mime_type=mime_type,
                    )
                elif event_type.endswith(".completed"):
                    image = GeneratedImage(data=decode_image_data(event.get("b64_json")), mime_type=mime_type)
                    yield StreamImage(image_index=len(images), image=image)
                    images.append(image)
                    if event.get("usage") is not None:
                        raw_usage = event["usage"]
        finally:
            await stream.close()

        response = ImageResponse(
            provider=self.name,
            model=context.model,
            id=None,
            images=images,
            text="",
            usage=map_images_usage(raw_usage),
            trace_id=context.trace_id,
            request_id=request_id,
        )
        yield StreamFinished(response=response, has_usage=raw_usage is not None)


class OpenAiResponsesImageClient(BaseImageClient):
    """
    image_api=responses：调用 responses.create 并强制使用 image_generation 工具，n 大于 1 时库内循环
    """

    features = (FEATURE_IMAGE_GENERATION, FEATURE_IMAGE_EDIT, FEATURE_IMAGE_MASK, FEATURE_IMAGE_STREAMING)
    mapped_option_keys = RESPONSES_IMAGE_MAPPED_KEYS
    unsupported_fields = ("aspect_ratio", "image_size")
    is_url_input_supported = True
    is_partial_image_supported = True

    def build_params(self, context: ImageCallContext, is_stream: bool) -> dict[str, Any]:
        """
        组装 responses.create 参数：对话模型取 default_model，图片模型写入 image_generation 工具
        """
        request = context.request
        if not self.settings.default_model:
            raise build_error(ErrorCategory.CONFIG_INVALID, f"image_api=responses 需要配置 providers.{self.name}.default_model 作为对话模型", provider=self.name)

        content: list[dict[str, Any]] = [
            {
                "type": "input_text",
                "text": request.prompt,
            },
        ]
        for image in request.images or []:
            content.append({"type": "input_image", "image_url": build_data_url(image), "detail": "auto"})

        tool: dict[str, Any] = {
            "type": "image_generation",
            "model": context.model,
        }
        add_optional_image_params(tool, context)
        if is_stream and request.partial_images is not None:
            tool["partial_images"] = request.partial_images

        if request.mask is not None:
            tool["input_image_mask"] = {"image_url": build_data_url(request.mask)}

        params: dict[str, Any] = {
            "model": self.settings.default_model,
            "input": [
                {
                    "role": "user",
                    "content": content,
                },
            ],
            "tools": [tool],
            "tool_choice": {"type": "image_generation"},
            "timeout": context.timeout_seconds,
        }
        if is_stream:
            params["stream"] = True

        if context.extra_body:
            params["extra_body"] = context.extra_body

        return params

    def map_response(self, response: dict[str, Any], context: ImageCallContext, request_id: str | None) -> ImageResponse:
        """
        映射 Response：image_generation_call 结果为图片，文本输出拼接为 text，model 为请求的图片模型
        """
        images: list[GeneratedImage] = []
        text_parts: list[str] = []
        for output_item in response.get("output") or []:
            item_type = output_item.get("type")
            if item_type == "image_generation_call" and output_item.get("result"):
                images.append(
                    GeneratedImage(
                        data=decode_image_data(output_item.get("result")),
                        mime_type=resolve_output_mime_type(output_item.get("output_format")),
                        revised_prompt=output_item.get("revised_prompt"),
                    )
                )
            elif item_type == "message":
                for content in output_item.get("content") or []:
                    if content.get("type") == "output_text":
                        text_parts.append(str(content.get("text") or ""))

        raw_response: dict[str, Any] | None = None
        if context.request.include_raw_response is True:
            raw_response = response

        return ImageResponse(
            provider=self.name,
            model=context.model,
            id=response.get("id"),
            images=images,
            text="".join(text_parts),
            usage=map_responses_usage(response.get("usage")),
            trace_id=context.trace_id,
            request_id=request_id,
            raw_response=raw_response,
        )

    async def invoke_generate(self, context: ImageCallContext) -> ImageResponse:
        client: AsyncOpenAI = self.native_client()
        create_response: Any = client.responses.create
        response = await create_response(**self.build_params(context, False))
        return self.map_response(dump_sdk_object(response), context, read_request_id(response))

    async def invoke_stream(self, context: ImageCallContext) -> AsyncGenerator[StreamItem, None]:
        client: AsyncOpenAI = self.native_client()
        create_response: Any = client.responses.create
        stream = await create_response(**self.build_params(context, True))
        request_id = read_stream_request_id(stream)
        image_indexes: dict[str, int] = {}
        try:
            async for raw_event in stream:
                event = dump_sdk_object(raw_event)
                event_type = event.get("type")
                item = read_mapping(event, "item")
                if event_type == "response.created":
                    yield StreamStarted(model=context.model, response_id=read_mapping(event, "response").get("id"))
                elif event_type == "response.output_item.added" and item.get("type") == "image_generation_call":
                    image_indexes[str(item.get("id"))] = len(image_indexes)
                elif event_type == "response.image_generation_call.partial_image":
                    yield StreamPartial(
                        image_index=image_indexes.get(str(event.get("item_id")), 0),
                        partial_index=int(event.get("partial_image_index") or 0),
                        data=decode_image_data(event.get("partial_image_b64")),
                        mime_type=resolve_output_mime_type(event.get("output_format")),
                    )
                elif event_type == "response.output_item.done" and item.get("type") == "image_generation_call":
                    image = GeneratedImage(
                        data=decode_image_data(item.get("result")),
                        mime_type=resolve_output_mime_type(item.get("output_format")),
                        revised_prompt=item.get("revised_prompt"),
                    )
                    yield StreamImage(image_index=image_indexes.get(str(item.get("id")), 0), image=image)
                elif event_type in ("response.completed", "response.incomplete"):
                    final = read_mapping(event, "response")
                    yield StreamFinished(response=self.map_response(final, context, request_id), has_usage=final.get("usage") is not None)
                    return
                elif event_type in ("response.failed", "error"):
                    error_body = read_mapping(read_mapping(event, "response"), "error") or event
                    code = error_body.get("code")
                    raise build_error(
                        ErrorCategory.PROVIDER_ERROR,
                        f"OpenAI 图片生成失败：{error_body.get('message') or code}",
                        provider=self.name,
                        request_id=request_id,
                        vendor_code=str(code) if code is not None else None,
                    )
        finally:
            await stream.close()
