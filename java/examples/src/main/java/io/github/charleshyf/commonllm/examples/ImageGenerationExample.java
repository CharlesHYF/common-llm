package io.github.charleshyf.commonllm.examples;

import io.github.charleshyf.commonllm.CommonLlm;
import io.github.charleshyf.commonllm.core.CommonLlmException;
import io.github.charleshyf.commonllm.core.GeneratedImage;
import io.github.charleshyf.commonllm.core.ImageRequest;
import io.github.charleshyf.commonllm.core.ImageResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 图片生成示例：文生图并把结果保存到文件
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class ImageGenerationExample {

	private ImageGenerationExample() {
	}

	public static void main(String[] arguments) throws IOException {
		String provider = arguments.length > 0 ? arguments[0] : "openai";
		Path outputDirectory = Path.of(arguments.length > 1 ? arguments[1] : "generated-images");

		try (CommonLlm commonLlm = CommonLlm.create()) {
			ImageResponse response = commonLlm.generateImage(ImageRequest.builder()
				.provider(provider)
				.prompt("一只橘色的猫坐在窗台上，水彩风格")
				.build());
			Files.createDirectories(outputDirectory);
			List<GeneratedImage> images = response.getImages();

			for (int index = 0; index < images.size(); index++) {
				GeneratedImage image = images.get(index);
				String extension = image.getMimeType().substring(image.getMimeType().indexOf('/') + 1);
				Path target = outputDirectory.resolve("image-" + index + "." + extension);
				Files.write(target, image.getData());
				System.out.println("已保存 " + target + "，revisedPrompt=" + image.getRevisedPrompt());
			}

			System.out.println("usage=" + response.getUsage());
		} catch (CommonLlmException exception) {
			System.err.println("图片生成失败：" + exception.getErrorInfo());
		}
	}
}
