package io.github.charleshyf.commonllm.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型输出格式要求，支持纯文本与 JSON Schema
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class ResponseFormat {

	private final boolean isJsonSchema;
	private final String name;
	private final Map<String, Object> schema;
	private final Boolean strict;

	private ResponseFormat(boolean isJsonSchema, String name, Map<String, Object> schema, Boolean strict) {
		this.isJsonSchema = isJsonSchema;
		this.name = name;
		this.schema = schema;
		this.strict = strict;
	}

	public static ResponseFormat text() {
		return new ResponseFormat(false, null, null, null);
	}

	public static ResponseFormat jsonSchema(String name, Map<String, Object> schema, Boolean strict) {
		return new ResponseFormat(true, name, Collections.unmodifiableMap(new LinkedHashMap<>(schema)), strict);
	}

	public boolean isJsonSchema() {
		return isJsonSchema;
	}

	public String getName() {
		return name;
	}

	public Map<String, Object> getSchema() {
		return schema;
	}

	public Boolean getStrict() {
		return strict;
	}
}
