package io.github.charleshyf.commonllm.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * 有上限的 Agent 事件缓存，超限时优先丢弃最早的 raw_vendor_event
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class AgentEventBuffer {

	private final Deque<AgentEvent> events = new ArrayDeque<>();
	private final int maxBufferedEvents;
	private boolean isFinished;
	private boolean isAbandoned;

	public AgentEventBuffer(int maxBufferedEvents) {
		this.maxBufferedEvents = maxBufferedEvents;
	}

	/**
	 * 写入事件，缓存满且没有可丢弃事件时等待消费方读取
	 */
	public synchronized void add(AgentEvent event) {
		if (isAbandoned) {
			return;
		}

		while (events.size() >= maxBufferedEvents && !isAbandoned) {
			if (dropOldestRawEvent()) {
				break;
			}

			waitQuietly();
		}

		if (isAbandoned) {
			return;
		}

		events.addLast(event);

		if (event.getType().isTerminal()) {
			isFinished = true;
		}

		notifyAll();
	}

	/**
	 * 读取下一个事件，流结束且缓存为空时返回 null
	 */
	public synchronized AgentEvent take() {
		while (events.isEmpty() && !isFinished && !isAbandoned) {
			waitQuietly();
		}

		AgentEvent event = events.pollFirst();
		notifyAll();
		return event;
	}

	/**
	 * 消费方放弃读取，清空缓存并唤醒等待中的写入方
	 */
	public synchronized void abandon() {
		isAbandoned = true;
		events.clear();
		notifyAll();
	}

	public synchronized boolean isFinished() {
		return isFinished;
	}

	private boolean dropOldestRawEvent() {
		Iterator<AgentEvent> iterator = events.iterator();

		while (iterator.hasNext()) {
			AgentEvent bufferedEvent = iterator.next();

			if (bufferedEvent.getType() == AgentEventType.RAW_VENDOR_EVENT) {
				iterator.remove();
				return true;
			}
		}

		return false;
	}

	private void waitQuietly() {
		try {
			wait();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			isAbandoned = true;
		}
	}
}
