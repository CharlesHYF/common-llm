package io.github.charleshyf.commonllm.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 修改的单个文件及修改类别
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class FileChange {

	private final String path;
	private final String kind;

	public FileChange(String path, String kind) {
		this.path = path;
		this.kind = kind;
	}

	public String getPath() {
		return path;
	}

	/**
	 * 修改类别：add / update / delete
	 */
	public String getKind() {
		return kind;
	}

	public Map<String, Object> toContractMap() {
		Map<String, Object> contractMap = new LinkedHashMap<>();
		contractMap.put("path", path);
		contractMap.put("kind", kind);
		return contractMap;
	}
}
