package io.github.charleshyf.commonllm.core;

/**
 * 统一的图片生成客户端
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public interface ImageClient extends AutoCloseable {

	/**
	 * 同步生成图片，失败时抛出 CommonLlmException
	 */
	ImageResponse generate(ImageRequest request);

	/**
	 * 流式生成图片，不抛异常，失败以唯一的 error 事件结束
	 */
	ImageStream stream(ImageRequest request);

	/**
	 * 查询是否支持指定能力，未知能力返回 false
	 */
	boolean supports(String feature);

	ProviderCapability capability();

	/**
	 * 返回底层官方 SDK 客户端，与同一 Provider 的 ModelClient 共用
	 */
	Object nativeClient();

	/**
	 * 释放图片客户端自身的线程资源，共用的 SDK 客户端由 ModelClient 关闭
	 */
	@Override
	void close();
}
