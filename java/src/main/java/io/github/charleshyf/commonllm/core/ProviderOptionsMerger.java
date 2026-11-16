package io.github.charleshyf.commonllm.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 合并配置 native_options 与请求 providerOptions 并检查字段冲突
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class ProviderOptionsMerger {

	private ProviderOptionsMerger() {
	}

	/**
	 * 请求级覆盖配置级；与已映射字段重名时报 CONFIG_INVALID，不做静默覆盖
	 */
	public static Map<String, Object> merge(Map<String, Object> nativeOptions, Map<String, Object> providerOptions, Set<String> mappedFields, String provider) {
		Map<String, Object> merged = new LinkedHashMap<>();

		if (nativeOptions != null) {
			merged.putAll(nativeOptions);
		}

		if (providerOptions != null) {
			merged.putAll(providerOptions);
		}

		for (String key : merged.keySet()) {
			if (mappedFields.contains(key)) {
				throw CommonLlmException.of(ErrorCategory.CONFIG_INVALID, "providerOptions 字段与已映射字段冲突：" + key, provider);
			}
		}

		return merged;
	}
}
