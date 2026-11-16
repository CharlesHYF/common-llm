package io.github.charleshyf.commonllm.core;

/**
 * 校验图片生成请求的通用规则并解析模型名与张数
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class ImageRequestValidator {

	private ImageRequestValidator() {
	}

	/**
	 * 校验 prompt、n、输出压缩率与蒙版，返回请求模型或配置 default_image_model
	 */
	public static String validateAndResolveModel(ImageRequest request, String defaultImageModel, String provider) {
		if (request.getPrompt() == null || request.getPrompt().isBlank()) {
			throw CommonLlmException.of(ErrorCategory.CONFIG_INVALID, "prompt 不能为空", provider);
		}

		if (request.getImageCount() != null && request.getImageCount() < 1) {
			throw CommonLlmException.of(ErrorCategory.CONFIG_INVALID, "n 必须大于等于 1", provider);
		}

		Integer outputCompression = request.getOutputCompression();

		if (outputCompression != null && (outputCompression < 0 || outputCompression > 100)) {
			throw CommonLlmException.of(ErrorCategory.CONFIG_INVALID, "outputCompression 取值范围为 0-100", provider);
		}

		if (request.getPartialImages() != null && request.getPartialImages() < 0) {
			throw CommonLlmException.of(ErrorCategory.CONFIG_INVALID, "partialImages 不能小于 0", provider);
		}

		if (request.getMask() != null && request.getImages().isEmpty()) {
			throw CommonLlmException.of(ErrorCategory.CONFIG_INVALID, "使用 mask 时必须同时提供 images", provider);
		}

		if (request.getModel() != null && !request.getModel().isBlank()) {
			return request.getModel();
		}

		if (defaultImageModel != null && !defaultImageModel.isBlank()) {
			return defaultImageModel;
		}

		throw CommonLlmException.of(ErrorCategory.CONFIG_INVALID, "未指定图片模型：请求 model 与配置 default_image_model 均为空", provider);
	}

	public static int resolveCount(ImageRequest request) {
		return request.getImageCount() == null ? 1 : request.getImageCount();
	}

	/**
	 * 字段有值时报 SDK_UNSUPPORTED_FEATURE，不静默忽略
	 */
	public static void rejectIfPresent(Object value, String fieldName, String pathName, String provider) {
		if (value != null) {
			throw CommonLlmException.of(ErrorCategory.SDK_UNSUPPORTED_FEATURE, pathName + " 不支持 " + fieldName, provider);
		}
	}

	/**
	 * 输入图片与蒙版不允许使用 URL 的路径调用
	 */
	public static void rejectUrlInputs(ImageRequest request, String pathName, String provider) {
		for (ImageInput image : request.getImages()) {
			if (image.isUrl()) {
				throw CommonLlmException.of(ErrorCategory.SDK_UNSUPPORTED_FEATURE, pathName + " 不支持 URL 输入图片，请改用 base64", provider);
			}
		}

		if (request.getMask() != null && request.getMask().isUrl()) {
			throw CommonLlmException.of(ErrorCategory.SDK_UNSUPPORTED_FEATURE, pathName + " 不支持 URL 蒙版，请改用 base64", provider);
		}
	}

	/**
	 * Images API 与 Responses 图片工具不直接返回 MIME 类型，按 output_format 推出，缺省为 PNG
	 */
	public static String resolveMimeType(String outputFormat) {
		if (outputFormat == null || outputFormat.isEmpty()) {
			return "image/png";
		}

		if ("jpg".equals(outputFormat)) {
			return "image/jpeg";
		}

		return "image/" + outputFormat;
	}
}
