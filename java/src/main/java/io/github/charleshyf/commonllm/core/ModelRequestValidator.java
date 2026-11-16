package io.github.charleshyf.commonllm.core;

/**
 * 校验模型请求的通用必填项并解析实际模型名
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class ModelRequestValidator {

	private ModelRequestValidator() {
	}

	/**
	 * 校验 messages 非空，返回请求模型或配置默认模型，两者都没有报 CONFIG_INVALID
	 */
	public static String validateAndResolveModel(ModelRequest request, String defaultModel, String provider) {
		if (request.getMessages().isEmpty()) {
			throw CommonLlmException.of(ErrorCategory.CONFIG_INVALID, "messages 至少需要 1 条", provider);
		}

		for (Message message : request.getMessages()) {
			if (message.getContentParts().isEmpty()) {
				throw CommonLlmException.of(ErrorCategory.CONFIG_INVALID, "消息内容不能为空", provider);
			}
		}

		if (request.getModel() != null && !request.getModel().isBlank()) {
			return request.getModel();
		}

		if (defaultModel != null && !defaultModel.isBlank()) {
			return defaultModel;
		}

		throw CommonLlmException.of(ErrorCategory.CONFIG_INVALID, "未指定模型：请求 model 与配置 default_model 均为空", provider);
	}

	/**
	 * 返回请求 traceId，缺省时使用传入的新值
	 */
	public static String resolveTraceId(ModelRequest request, String generatedTraceId) {
		if (request.getTraceId() != null && !request.getTraceId().isBlank()) {
			return request.getTraceId();
		}

		return generatedTraceId;
	}
}
