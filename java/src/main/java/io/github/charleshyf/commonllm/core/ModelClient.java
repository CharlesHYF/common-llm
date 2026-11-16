package io.github.charleshyf.commonllm.core;

/**
 * 统一的模型调用客户端
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public interface ModelClient extends AutoCloseable {

	/**
	 * 同步调用模型，失败时抛出 CommonLlmException
	 */
	ModelResponse generate(ModelRequest request);

	/**
	 * 流式调用模型，厂商错误以 error 事件结束
	 */
	ModelStream stream(ModelRequest request);

	/**
	 * 查询是否支持指定能力，未知能力返回 false
	 */
	boolean supports(String feature);

	ProviderCapability capability();

	/**
	 * 返回底层官方 SDK 客户端，用于调用尚未统一的能力
	 */
	Object nativeClient();

	@Override
	void close();
}
