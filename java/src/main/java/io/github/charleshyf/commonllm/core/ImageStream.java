package io.github.charleshyf.commonllm.core;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * 同步迭代的图片流，保证有且只有一个终止事件
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class ImageStream implements Iterator<ImageEvent>, AutoCloseable {

	private final ImageEventEmitter emitter;
	private final ImageStreamSource source;
	private Runnable closeCallback;
	private boolean isClosed;
	private boolean isSourceFinished;

	public ImageStream(ImageEventEmitter emitter, ImageStreamSource source) {
		this.emitter = emitter;
		this.source = source;
	}

	/**
	 * 组装只含一个 error 终止事件的流，用于预检失败
	 */
	public static ImageStream failed(String traceId, String provider, ErrorInfo error) {
		ImageEventEmitter emitter = new ImageEventEmitter(traceId, provider);
		emitter.emitError(error);
		ImageStream stream = new ImageStream(emitter, null);
		stream.isSourceFinished = true;
		return stream;
	}

	/**
	 * 设置流关闭时的回调，读完终止事件或提前关闭都会触发一次
	 */
	public ImageStream onClose(Runnable callback) {
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
	public ImageEvent next() {
		if (!hasNext()) {
			throw new NoSuchElementException("图片流已结束");
		}

		ImageEvent event = emitter.pollEvent();

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

		if (source != null) {
			source.close();
		}

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
			emitter.emitError(ErrorInfo.builder(ErrorCategory.INTERNAL_ERROR, "图片流处理失败").provider(emitter.getProvider()).cause(exception).build());
			isSourceFinished = true;
		}

		// 厂商流结束却没有终止事件时补发错误，保证终止事件唯一
		if (isSourceFinished && !emitter.hasTerminalEvent()) {
			emitter.emitError(ErrorInfo.builder(ErrorCategory.PROVIDER_ERROR, "厂商流在完成前结束").provider(emitter.getProvider()).build());
		}
	}
}
