package io.github.charleshyf.commonllm.core;

import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 按契约规则生成带序号的图片事件并缓存待读取事件
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class ImageEventEmitter {

	private final String traceId;
	private final String provider;
	private final Deque<ImageEvent> pendingEvents = new ArrayDeque<>();
	private long nextSequence;
	private boolean hasStarted;
	private boolean hasTerminalEvent;

	public ImageEventEmitter(String traceId, String provider) {
		this.traceId = traceId;
		this.provider = provider;
	}

	/**
	 * 发出 started 事件，同一个流只发一次
	 */
	public void emitStarted(String model, String responseId) {
		if (hasStarted) {
			return;
		}

		hasStarted = true;
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("model", model);
		fields.put("id", responseId);
		add(ImageEventType.STARTED, fields);
	}

	public void emitPartialImage(int imageIndex, int partialIndex, String base64, String mimeType) {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("imageIndex", imageIndex);
		fields.put("partialIndex", partialIndex);
		fields.put("data", base64 == null ? new byte[0] : Base64.getDecoder().decode(base64));
		fields.put("mimeType", mimeType);
		add(ImageEventType.PARTIAL_IMAGE, fields);
	}

	public void emitImageCompleted(int imageIndex, GeneratedImage image) {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("imageIndex", imageIndex);
		fields.put("image", image);
		add(ImageEventType.IMAGE_COMPLETED, fields);
	}

	public void emitUsage(Usage usage) {
		add(ImageEventType.USAGE, Map.of("usage", usage));
	}

	public void emitCompleted(ImageResponse response) {
		add(ImageEventType.COMPLETED, Map.of("response", response));
	}

	public void emitError(ErrorInfo error) {
		add(ImageEventType.ERROR, Map.of("error", error));
	}

	public boolean hasStarted() {
		return hasStarted;
	}

	public boolean hasTerminalEvent() {
		return hasTerminalEvent;
	}

	public boolean hasPendingEvents() {
		return !pendingEvents.isEmpty();
	}

	public ImageEvent pollEvent() {
		return pendingEvents.pollFirst();
	}

	public String getTraceId() {
		return traceId;
	}

	public String getProvider() {
		return provider;
	}

	private void add(ImageEventType type, Map<String, Object> fields) {
		// 终止事件之后不再产生任何事件
		if (hasTerminalEvent) {
			return;
		}

		pendingEvents.addLast(new ImageEvent(type, traceId, nextSequence, provider, fields));
		nextSequence++;

		if (type.isTerminal()) {
			hasTerminalEvent = true;
		}
	}
}
