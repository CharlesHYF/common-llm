# -*- coding: utf-8 -*-
"""
图片生成示例：文生图并把结果保存为文件
创建日期：2026-11-16
修改日期：2026-11-16
"""
import asyncio
import sys
from pathlib import Path

from common_llm import CommonLlm, CommonLlmError, ImageRequest, ImageResponse


async def generate(provider: str) -> ImageResponse | None:
    llm = CommonLlm.create()
    try:
        return await llm.generate_image(ImageRequest(provider=provider, prompt="一只橘猫坐在窗台上，水彩风格", n=1))
    except CommonLlmError as error:
        print(f"生成失败：{error}")
        return None
    finally:
        await llm.close()


def save_images(response: ImageResponse, output_directory: Path) -> None:
    """
    按 MIME 类型确定扩展名，逐张写入输出目录
    """
    output_directory.mkdir(parents=True, exist_ok=True)
    for index, image in enumerate(response.images):
        extension = image.mime_type.split("/")[-1]
        path = output_directory / f"image-{index}.{extension}"
        image.save(path)
        print(f"已保存 {path}，改写后的提示词：{image.revised_prompt}")


if __name__ == "__main__":
    result = asyncio.run(generate(sys.argv[1] if len(sys.argv) > 1 else "openai"))
    if result is not None:
        save_images(result, Path(sys.argv[2] if len(sys.argv) > 2 else "output"))
