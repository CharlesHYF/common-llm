package io.github.charleshyf.commonllm.core.process;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 结束子进程及其全部后代进程
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class ProcessTreeTerminator {

	private ProcessTreeTerminator() {
	}

	/**
	 * 先快照后代进程再发送结束信号，等待 grace 后强制结束仍存活的进程
	 */
	public static void terminate(ProcessHandle root, Duration grace) {
		List<ProcessHandle> handles = new ArrayList<>();
		handles.add(root);
		// JDK 只以 Stream 形式返回后代进程，这里仅取迭代器逐个读取
		Iterator<ProcessHandle> descendants = root.descendants().iterator();

		while (descendants.hasNext()) {
			handles.add(descendants.next());
		}

		for (ProcessHandle handle : handles) {
			handle.destroy();
		}

		long deadline = System.nanoTime() + grace.toNanos();

		while (System.nanoTime() < deadline && hasAlive(handles)) {
			sleepQuietly();
		}

		for (ProcessHandle handle : handles) {
			if (handle.isAlive()) {
				handle.destroyForcibly();
			}
		}
	}

	private static boolean hasAlive(List<ProcessHandle> handles) {
		for (ProcessHandle handle : handles) {
			if (handle.isAlive()) {
				return true;
			}
		}

		return false;
	}

	private static void sleepQuietly() {
		try {
			TimeUnit.MILLISECONDS.sleep(50);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
	}
}
