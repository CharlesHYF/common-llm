package io.github.charleshyf.commonllm.core.process;

import io.github.charleshyf.commonllm.core.CommonLlmException;
import io.github.charleshyf.commonllm.core.ErrorCategory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 校验并解析 Agent 工作目录为真实绝对路径
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class WorkingDirectoryResolver {

	private WorkingDirectoryResolver() {
	}

	/**
	 * 请求值优先于配置值，两者都没有或目录无效时报 CONFIG_INVALID
	 */
	public static Path resolve(String requestedDirectory, String configuredDirectory, String agent) {
		String directory = requestedDirectory;

		if (directory == null || directory.isBlank()) {
			directory = configuredDirectory;
		}

		if (directory == null || directory.isBlank()) {
			throw CommonLlmException.of(ErrorCategory.CONFIG_INVALID, "未指定工作目录：请求 workingDirectory 与配置 working_directory 均为空", agent);
		}

		Path path = Paths.get(directory);

		if (!Files.exists(path)) {
			throw CommonLlmException.of(ErrorCategory.CONFIG_INVALID, "工作目录不存在：" + directory, agent);
		}

		if (!Files.isDirectory(path)) {
			throw CommonLlmException.of(ErrorCategory.CONFIG_INVALID, "工作目录不是目录：" + directory, agent);
		}

		try {
			return path.toRealPath();
		} catch (IOException exception) {
			throw CommonLlmException.of(ErrorCategory.CONFIG_INVALID, "无法解析工作目录：" + directory, agent);
		}
	}
}
