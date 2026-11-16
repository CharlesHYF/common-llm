package io.github.charleshyf.commonllm.core.process;

import io.github.charleshyf.commonllm.core.CommonLlmException;
import io.github.charleshyf.commonllm.core.ErrorCategory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 解析 CLI 可执行文件的绝对路径
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class ExecutableResolver {

	private ExecutableResolver() {
	}

	/**
	 * 含路径分隔符时直接校验该文件，否则在 PATH 中查找，找不到报 CLI_NOT_FOUND
	 */
	public static Path resolve(String executablePath, String searchPath, String agent) {
		if (executablePath == null || executablePath.isBlank()) {
			throw CommonLlmException.of(ErrorCategory.CONFIG_INVALID, "executable_path 不能为空", agent);
		}

		if (executablePath.contains("/") || executablePath.contains(File.separator)) {
			Path candidate = Paths.get(executablePath).toAbsolutePath().normalize();

			if (isExecutableFile(candidate)) {
				return candidate;
			}

			throw CommonLlmException.of(ErrorCategory.CLI_NOT_FOUND, "找不到可执行文件：" + executablePath, agent);
		}

		if (searchPath != null) {
			for (String directory : searchPath.split(File.pathSeparator)) {
				if (directory.isEmpty()) {
					continue;
				}

				Path candidate = Paths.get(directory, executablePath);

				if (isExecutableFile(candidate)) {
					return candidate.toAbsolutePath().normalize();
				}
			}
		}

		throw CommonLlmException.of(ErrorCategory.CLI_NOT_FOUND, "在 PATH 中找不到可执行文件：" + executablePath, agent);
	}

	private static boolean isExecutableFile(Path candidate) {
		return Files.isRegularFile(candidate) && Files.isExecutable(candidate);
	}
}
