/**
 * 示例：调用图片生成并把结果保存为文件
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";

import { CommonLlm, isCommonLlmError } from "@charleshyf/common-llm";

const provider = process.argv[2] ?? "openai";
const outputDirectory = process.argv[3] ?? "./output";
const llm = await CommonLlm.create();

try {
	const response = await llm.generateImage({
		provider,
		prompt: "一只橘色的猫坐在窗台上，水彩风格",
		n: 1,
	});
	await mkdir(outputDirectory, {
		recursive: true,
	});

	for (const [index, image] of response.images.entries()) {
		const extension = image.mimeType.split("/")[1] ?? "png";
		const filePath = path.join(outputDirectory, `image-${index}.${extension}`);
		await writeFile(filePath, image.data);
		console.log(`已保存 ${filePath}`);
	}

	console.log(`用量：${JSON.stringify(response.usage)}`);
} catch (error) {
	if (isCommonLlmError(error)) {
		console.error(`${error.category}: ${error.message}`);
	} else {
		throw error;
	}
} finally {
	await llm.close();
}
