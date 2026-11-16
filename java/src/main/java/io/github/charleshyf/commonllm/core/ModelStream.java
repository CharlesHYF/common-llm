package io.github.charleshyf.commonllm.core;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * 同步迭代的模型流，保证有且只有一个终止事件
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class ModelStream implements Iterator<ModelEvent>, AutoCloseable {

	private final ModelEventEmitter emitter;
	private final ModelStreamSource source;
	private Runnable closeCallback;
	private boolean isClosed;
	private boolean isSourceFinished;

	public ModelStream(ModelEventEmitter emitter, ModelStreamSource source) {
		this.emitter = emitter;
		this.source = source;
	}

	/**
	 * 组装只含一个 error 终止事件的流，用于预检失败
	 */
	public static ModelStream failed(String traceId, String provider, ErrorInfo error) {
		ModelEventEmitter emitter = new ModelEventEmitter(traceId, provider);
		emitter.emitError(error);
		return new ModelStream(emitter, new FailedSource());
	}

	/**
	 * 设置流关闭时的回调，读完终止事件或提前关闭都会触发一次
	 */
	public ModelStream onClose(Runnable callback) {
		this.closeCallback = callback;
		return this;
	}

	@Override
	public boolean hasNext() {
		if (isClosed) {
			return false;
		}

		while (!emitter.hasPendingEvents() && !isSourceFinished) {
			readFromSource();
		}

		if (emitter.hasPendingEvents()) {
			return true;
		}

		close();
		return false;
	}

	@Override
	public ModelEvent next() {
		if (!hasNext()) {
			throw new NoSuchElementException("模型流已结束");
		}

		ModelEvent event = emitter.pollEvent();

		if (event.getType().isTerminal()) {
			isSourceFinished = true;
		}

		return event;
	}

	/**
	 * 提前关闭流视为取消，关闭底层连接且不再产生事件
	 */
	@Override
	public void close() {
		if (isClosed) {
			return;
		}

		isClosed = true;
		source.close();

		if (closeCallback != null) {
			closeCallback.run();
		}
	}

	private void readFromSource() {
		try {
			boolean hasMore = source.readNext(emitter);

			if (!hasMore || emitter.hasTerminalEvent()) {
				isSourceFinished = true;
			}
		} catch (CommonLlmException exception) {
			emitter.emitError(exception.getErrorInfo());
			isSourceFinished = true;
		} catch (RuntimeException exception) {
			emitter.emitError(ErrorInfo.builder(ErrorCategory.INTERNAL_ERROR, "模型流处理失败").provider(emitter.getProvider()).cause(exception).build());
			isSourceFinished = true;
		}

		// 厂商流结束却没有终止事件时补发协议错误，保证终止事件唯一
		if (isSourceFinished && !emitter.hasTerminalEvent()) {
			emitter.emitError(ErrorInfo.builder(ErrorCategory.PROVIDER_ERROR, "厂商流在完成前结束").provider(emitter.getProvider()).build());
		}
	}

	/**
	 * 预检失败时使用的空事件来源
	 */
	private static final class FailedSource implements ModelStreamSource {

		@Override
		public boolean readNext(ModelEventEmitter emitter) {
			return false;
		}

		@Override
		public void close() {
			// 没有底层连接需要关闭
		}
	}
}
