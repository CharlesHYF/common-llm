# -*- coding: utf-8 -*-
"""
Gemini 图片生成适配器，经 generateContent 原生出图，n 大于 1 时库内循环
创建日期：2026-11-16
修改日期：2026-11-16
"""
from collections.abc import AsyncGenerator
from typing import Any

from google import genai
from google.genai import types

from common_llm.core.errors import CommonLlmError, ErrorCategory, build_error
from common_llm.core.image_client import (
    BaseImageClient,
    ImageCallContext,
    StreamFinished,
    StreamImage,
    StreamItem,
    StreamStarted,
    decode_image_input,
)
from common_llm.core.model_support import dump_sdk_object
from common_llm.core.types import FEATURE_IMAGE_EDIT, FEATURE_IMAGE_GENERATION, GeneratedImage, ImageResponse
from common_llm.providers.gemini.client import map_gemini_usage, read_finish_reason

MAPPED_KEYS = (
    "contents",
    "systemInstruction",
    "system_instruction",
)
GENERATION_CONFIG_KEYS = ("generationConfig", "generation_config")
MAPPED_GENERATION_KEYS = (
    "responseModalities",
    "response_modalities",
    "imageConfig",
    "image_config",
)


def collect_image_parts(response: types.GenerateContentResponse) -> tuple[list[GeneratedImage], str, str | None]:
    """
    提取首个候选的 inlineData 图片与文本，跳过 thought 部分
    """
    images: list[GeneratedImage] = []
    text_parts: list[str] = []
    if not response.candidates:
        return images, "", None

    candidate = response.candidates[0]
    parts: list[types.Part] = []
    if candidate.content is not None and candidate.content.parts is not None:
        parts = candidate.content.parts

    for part in parts:
        if part.thought is True:
            continue

        if part.inline_data is not None:
            images.append(GeneratedImage(data=part.inline_data.data or b"", mime_type=part.inline_data.mime_type or "image/png"))
        elif part.text is not None:
            text_parts.append(part.text)

    return images, "".join(text_parts), read_finish_reason(candidate)


def build_no_image_error(provider: str, finish_reason: str | None) -> CommonLlmError:
    """
    响应中没有图片时报 PROVIDER_ERROR，vendorCode 为 finishReason
    """
    return build_error(
        ErrorCategory.PROVIDER_ERROR,
        f"Gemini 未返回图片，finishReason={finish_reason}",
        provider=provider,
        retriable=False,
        vendor_code=finish_reason,
    )


class GeminiImageClient(BaseImageClient):
    """
    Gemini 图片生成：responseModalities 为 TEXT 与 IMAGE，imageConfig 传 aspectRatio 与 imageSize
    """

    features = (FEATURE_IMAGE_GENERATION, FEATURE_IMAGE_EDIT)
    mapped_option_keys = MAPPED_KEYS
    unsupported_fields = ("mask", "size", "quality", "output_format", "output_compression", "background")

    def build_extra_body(self, context: ImageCallContext) -> dict[str, Any]:
        """
        校验 generationConfig 中与已映射字段重名的键
        """
        for config_key in GENERATION_CONFIG_KEYS:
            generation_config = context.extra_body.get(config_key)
            if not isinstance(generation_config, dict):
                continue

            for key in generation_config:
                if key in MAPPED_GENERATION_KEYS:
                    raise build_error(ErrorCategory.CONFIG_INVALID, f"providerOptions.{config_key}.{key} 与已映射字段重名", provider=self.name)

        return context.extra_body

    def build_request(self, context: ImageCallContext) -> tuple[list[types.Content], types.GenerateContentConfig]:
        """
        组装 contents 与 GenerateContentConfig，单次超时与 extra_body 经 http_options 传入
        """
        request = context.request
        parts: list[types.Part] = [types.Part(text=request.prompt)]
        for image in request.images or []:
            parts.append(types.Part(inline_data=types.Blob(data=decode_image_input(image, self.name), mime_type=image.mime_type)))

        extra_body = self.build_extra_body(context)
        config = types.GenerateContentConfig(
            response_modalities=["TEXT", "IMAGE"],
            http_options=types.HttpOptions(timeout=int(context.timeout_seconds * 1000), extra_body=extra_body or None),
        )
        if request.aspect_ratio is not None or request.image_size is not None:
            config.image_config = types.ImageConfig(aspect_ratio=request.aspect_ratio, image_size=request.image_size)

        return [types.Content(role="user", parts=parts)], config

    def map_response(self, response: types.GenerateContentResponse, context: ImageCallContext) -> ImageResponse:
        """
        映射 GenerateContentResponse，没有图片时报错
        """
        images, text, finish_reason = collect_image_parts(response)
        if not images:
            raise build_no_image_error(self.name, finish_reason)

        raw_response: dict[str, Any] | None = None
        if context.request.include_raw_response is True:
            raw_response = dump_sdk_object(response)

        return ImageResponse(
            provider=self.name,
            model=response.model_version,
            id=response.response_id,
            images=images,
            text=text,
            usage=map_gemini_usage(response.usage_metadata),
            trace_id=context.trace_id,
            request_id=None,
            raw_response=raw_response,
        )

    async def invoke_generate(self, context: ImageCallContext) -> ImageResponse:
        client: genai.Client = self.native_client()
        contents, config = self.build_request(context)
        response = await client.aio.models.generate_content(model=context.model, contents=contents, config=config)
        return self.map_response(response, context)

    async def invoke_stream(self, context: ImageCallContext) -> AsyncGenerator[StreamItem, None]:
        client: genai.Client = self.native_client()
        contents, config = self.build_request(context)
        chunks = await client.aio.models.generate_content_stream(model=context.model, contents=contents, config=config)
        is_started = False
        model: str | None = None
        response_id: str | None = None
        images: list[GeneratedImage] = []
        text_parts: list[str] = []
        finish_reason: str | None = None
        usage_metadata: types.GenerateContentResponseUsageMetadata | None = None
        try:
            async for chunk in chunks:
                if not is_started:
                    is_started = True
                    model = chunk.model_version
                    response_id = chunk.response_id
                    yield StreamStarted(model=model, response_id=response_id)

                chunk_images, chunk_text, chunk_finish = collect_image_parts(chunk)
                text_parts.append(chunk_text)
                for image in chunk_images:
                    yield StreamImage(image_index=len(images), image=image)
                    images.append(image)

                if chunk_finish:
                    finish_reason = chunk_finish

                if chunk.usage_metadata is not None:
                    usage_metadata = chunk.usage_metadata
        finally:
            close_stream = getattr(chunks, "aclose", None)
            if close_stream is not None:
                await close_stream()

        if not images:
            raise build_no_image_error(self.name, finish_reason)

        response = ImageResponse(
            provider=self.name,
            model=model,
            id=response_id,
            images=images,
            text="".join(text_parts),
            usage=map_gemini_usage(usage_metadata),
            trace_id=context.trace_id,
            request_id=None,
        )
        yield StreamFinished(response=response, has_usage=usage_metadata is not None)
