package io.github.charleshyf.commonllm.core;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 按契约规则生成带序号的模型事件并缓存待读取事件
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class ModelEventEmitter {

	private final String traceId;
	private final String provider;
	private final Deque<ModelEvent> pendingEvents = new ArrayDeque<>();
	private long nextSequence;
	private boolean hasTerminalEvent;

	public ModelEventEmitter(String traceId, String provider) {
		this.traceId = traceId;
		this.provider = provider;
	}

	public void emitStarted(String model, String responseId) {
		add(newBuilder(ModelEventType.STARTED).model(model).responseId(responseId));
	}

	/**
	 * 发出文本增量，空字符串不发出
	 */
	public void emitTextDelta(String text) {
		if (text == null || text.isEmpty()) {
			return;
		}

		add(newBuilder(ModelEventType.TEXT_DELTA).text(text));
	}

	/**
	 * 发出工具调用增量，id、name 与参数都为空时不发出
	 */
	public void emitToolCallDelta(int index, String toolCallId, String toolName, String argumentsDelta) {
		String safeDelta = argumentsDelta == null ? "" : argumentsDelta;

		if (toolCallId == null && toolName == null && safeDelta.isEmpty()) {
			return;
		}

		add(newBuilder(ModelEventType.TOOL_CALL_DELTA).index(index).toolCallId(toolCallId).toolName(toolName).argumentsDelta(safeDelta));
	}

	public void emitUsage(Usage usage) {
		add(newBuilder(ModelEventType.USAGE).usage(usage));
	}

	public void emitCompleted(ModelResponse response) {
		add(newBuilder(ModelEventType.COMPLETED).response(response));
	}

	public void emitError(ErrorInfo error) {
		add(newBuilder(ModelEventType.ERROR).error(error));
	}

	public boolean hasTerminalEvent() {
		return hasTerminalEvent;
	}

	public boolean hasPendingEvents() {
		return !pendingEvents.isEmpty();
	}

	public ModelEvent pollEvent() {
		return pendingEvents.pollFirst();
	}

	public String getTraceId() {
		return traceId;
	}

	public String getProvider() {
		return provider;
	}

	private ModelEvent.Builder newBuilder(ModelEventType type) {
		return ModelEvent.builder(type, traceId, nextSequence, provider);
	}

	private void add(ModelEvent.Builder builder) {
		// 终止事件之后不再产生任何事件
		if (hasTerminalEvent) {
			return;
		}

		ModelEvent event = builder.build();
		nextSequence++;
		pendingEvents.addLast(event);

		if (event.getType().isTerminal()) {
			hasTerminalEvent = true;
		}
	}
}
