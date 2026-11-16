package io.github.charleshyf.commonllm.providers.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.openai.core.JsonValue;
import com.openai.core.ObjectMappers;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import io.github.charleshyf.commonllm.core.CommonLlmException;
import io.github.charleshyf.commonllm.core.ErrorCategory;
import io.github.charleshyf.commonllm.core.ErrorInfo;
import io.github.charleshyf.commonllm.core.HttpErrorClassifier;
import io.github.charleshyf.commonllm.core.Usage;

import java.io.InterruptedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 适配层共用的 JSON 转换与错误映射
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
final class OpenAiSupport {

	static final String REQUEST_ID_HEADER = "x-request-id";
	private static final String QUOTA_ERROR_CODE = "insufficient_quota";

	private OpenAiSupport() {
	}

	/**
	 * 把业务 Map 转换为 SDK 的 JsonValue Map，用于 JSON Schema 与 extra body
	 */
	static Map<String, JsonValue> toJsonValueMap(Map<String, Object> source) {
		Map<String, JsonValue> converted = new LinkedHashMap<>();

		if (source == null) {
			return converted;
		}

		for (Map.Entry<String, Object> entry : source.entrySet()) {
			converted.put(entry.getKey(), JsonValue.from(entry.getValue()));
		}

		return converted;
	}

	/**
	 * 用 SDK 自带的 ObjectMapper 把响应对象转换为 JSON 树
	 */
	static JsonNode toNode(Object sdkObject) {
		return ObjectMappers.jsonMapper().valueToTree(sdkObject);
	}

	/**
	 * 读取响应头中的请求 ID
	 */
	static String readRequestId(com.openai.core.http.Headers headers) {
		List<String> values = headers.values(REQUEST_ID_HEADER);

		if (values.isEmpty()) {
			return null;
		}

		return values.get(0);
	}

	/**
	 * 把 SDK 异常映射为统一错误分类
	 */
	static CommonLlmException mapException(RuntimeException exception, String provider) {
		if (exception instanceof CommonLlmException) {
			return (CommonLlmException) exception;
		}

		if (exception instanceof OpenAIServiceException) {
			OpenAIServiceException serviceException = (OpenAIServiceException) exception;
			String errorCode = serviceException.code().orElse(null);
			String vendorCode = errorCode != null ? errorCode : serviceException.type().orElse(null);
			boolean isQuota = QUOTA_ERROR_CODE.equals(errorCode);
			ErrorInfo error = HttpErrorClassifier.buildError(provider, serviceException.statusCode(), vendorCode, serviceException.getMessage(), readRequestId(serviceException.headers()), isQuota, false, null);
			return new CommonLlmException(error, exception);
		}

		if (exception instanceof OpenAIIoException) {
			boolean isTimeout = hasTimeoutCause(exception);
			ErrorCategory category = isTimeout ? ErrorCategory.TIMEOUT : ErrorCategory.NETWORK_ERROR;
			String message = isTimeout ? "请求 OpenAI 超时" : "连接 OpenAI 失败";
			return new CommonLlmException(ErrorInfo.builder(category, message).provider(provider).retriable(true).cause(exception.getCause()).build(), exception);
		}

		if (exception instanceof OpenAIInvalidDataException) {
			return new CommonLlmException(ErrorInfo.builder(ErrorCategory.PROVIDER_ERROR, "OpenAI 响应数据不符合预期").provider(provider).cause(exception).build(), exception);
		}

		return new CommonLlmException(ErrorInfo.builder(ErrorCategory.INTERNAL_ERROR, "调用 OpenAI SDK 失败").provider(provider).cause(exception).build(), exception);
	}

	/**
	 * 映射 Responses API 的 usage 字段
	 */
	static Usage mapResponsesUsage(JsonNode usageNode) {
		if (usageNode == null || !usageNode.isObject()) {
			return Usage.empty();
		}

		JsonNode inputDetails = usageNode.get("input_tokens_details");
		JsonNode outputDetails = usageNode.get("output_tokens_details");
		return new Usage(
			readLong(usageNode, "input_tokens"),
			readLong(usageNode, "output_tokens"),
			readLong(inputDetails, "cached_tokens"),
			readLong(outputDetails, "reasoning_tokens"),
			readLong(usageNode, "total_tokens")
		);
	}

	/**
	 * 映射 Chat Completions 的 usage 字段
	 */
	static Usage mapChatUsage(JsonNode usageNode) {
		if (usageNode == null || !usageNode.isObject()) {
			return Usage.empty();
		}

		JsonNode promptDetails = usageNode.get("prompt_tokens_details");
		JsonNode completionDetails = usageNode.get("completion_tokens_details");
		return new Usage(
			readLong(usageNode, "prompt_tokens"),
			readLong(usageNode, "completion_tokens"),
			readLong(promptDetails, "cached_tokens"),
			readLong(completionDetails, "reasoning_tokens"),
			readLong(usageNode, "total_tokens")
		);
	}

	private static Long readLong(JsonNode node, String fieldName) {
		if (node == null || !node.isObject()) {
			return null;
		}

		JsonNode fieldNode = node.get(fieldName);

		if (fieldNode == null || !fieldNode.isNumber()) {
			return null;
		}

		return fieldNode.asLong();
	}

	private static boolean hasTimeoutCause(Throwable throwable) {
		Throwable current = throwable;

		while (current != null) {
			if (current instanceof InterruptedIOException) {
				return true;
			}

			current = current.getCause();
		}

		return false;
	}
}
