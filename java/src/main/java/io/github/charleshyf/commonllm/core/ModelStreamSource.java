package io.github.charleshyf.commonllm.core;

/**
 * 模型流的底层事件来源，由各 Provider 实现
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public interface ModelStreamSource {

	/**
	 * 读取下一个厂商事件并写入 emitter，厂商流结束时返回 false
	 */
	boolean readNext(ModelEventEmitter emitter);

	/**
	 * 关闭底层厂商流与连接
	 */
	void close();
}
