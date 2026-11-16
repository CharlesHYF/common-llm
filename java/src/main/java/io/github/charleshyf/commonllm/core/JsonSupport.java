package io.github.charleshyf.commonllm.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 共享的 JSON 解析与序列化工具
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class JsonSupport {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private JsonSupport() {
	}

	public static ObjectMapper mapper() {
		return OBJECT_MAPPER;
	}

	/**
	 * 序列化为紧凑 JSON 字符串
	 */
	public static String toJson(Object value) {
		try {
			return OBJECT_MAPPER.writeValueAsString(value);
		} catch (JsonProcessingException exception) {
			throw new CommonLlmException(ErrorInfo.builder(ErrorCategory.INTERNAL_ERROR, "JSON 序列化失败").cause(exception).build(), exception);
		}
	}

	/**
	 * 解析 JSON 文本为树结构，失败时抛出 JsonProcessingException
	 */
	public static JsonNode parseTree(String json) throws JsonProcessingException {
		return OBJECT_MAPPER.readTree(json);
	}

	/**
	 * 把任意对象转换为 JSON 树
	 */
	public static JsonNode toTree(Object value) {
		return OBJECT_MAPPER.valueToTree(value);
	}

	/**
	 * 把 JSON 对象节点转换为有序 Map，非对象返回 null
	 */
	public static Map<String, Object> toMap(JsonNode node) {
		if (node == null || !node.isObject()) {
			return null;
		}

		return OBJECT_MAPPER.convertValue(node, MAP_TYPE);
	}

	/**
	 * 把 JSON 文本解析为有序 Map，解析失败抛出 JsonProcessingException
	 */
	public static Map<String, Object> parseMap(String json) throws JsonProcessingException {
		return OBJECT_MAPPER.readValue(json, MAP_TYPE);
	}

	public static ObjectNode newObject() {
		return OBJECT_MAPPER.createObjectNode();
	}

	/**
	 * 读取对象字段的文本值，缺失或 null 返回 null
	 */
	public static String textOrNull(JsonNode node, String fieldName) {
		if (node == null) {
			return null;
		}

		JsonNode fieldNode = node.get(fieldName);

		if (fieldNode == null || fieldNode.isNull()) {
			return null;
		}

		if (fieldNode.isTextual()) {
			return fieldNode.asText();
		}

		return fieldNode.toString();
	}

	/**
	 * 读取对象字段的整数值，缺失、null 或非数字返回 null
	 */
	public static Long longOrNull(JsonNode node, String fieldName) {
		if (node == null) {
			return null;
		}

		JsonNode fieldNode = node.get(fieldName);

		if (fieldNode == null || !fieldNode.isNumber()) {
			return null;
		}

		return fieldNode.asLong();
	}

	/**
	 * 读取对象字段的浮点值，缺失、null 或非数字返回 null
	 */
	public static Double doubleOrNull(JsonNode node, String fieldName) {
		if (node == null) {
			return null;
		}

		JsonNode fieldNode = node.get(fieldName);

		if (fieldNode == null || !fieldNode.isNumber()) {
			return null;
		}

		return fieldNode.asDouble();
	}

	/**
	 * 读取对象字段，缺失或 null 返回 null
	 */
	public static JsonNode fieldOrNull(JsonNode node, String fieldName) {
		if (node == null) {
			return null;
		}

		JsonNode fieldNode = node.get(fieldName);

		if (fieldNode == null || fieldNode.isNull()) {
			return null;
		}

		return fieldNode;
	}

	/**
	 * 判断对象字段是否为 true
	 */
	public static boolean isTrue(JsonNode node, String fieldName) {
		JsonNode fieldNode = fieldOrNull(node, fieldName);
		return fieldNode != null && fieldNode.isBoolean() && fieldNode.asBoolean();
	}
}
