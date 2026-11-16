package io.github.charleshyf.commonllm.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

/**
 * 路径不支持原生多张时按并发上限循环调用并合并结果
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class ImageGenerationLoop {

	private ImageGenerationLoop() {
	}

	/**
	 * 调用 count 次：任一次失败即停止发出新请求、中断进行中的请求并抛出该次错误，结果按调用顺序合并
	 */
	public static ImageResponse run(int count, int concurrency, IntFunction<ImageResponse> call) {
		if (count == 1) {
			return call.apply(0);
		}

		ImageResponse[] results = new ImageResponse[count];
		int workerCount = Math.min(concurrency, count);
		AtomicInteger nextIndex = new AtomicInteger();
		AtomicReference<CommonLlmException> firstFailure = new AtomicReference<>();
		ExecutorService executor = Executors.newFixedThreadPool(workerCount, runnable -> {
			Thread thread = new Thread(runnable, "common-llm-image-loop");
			thread.setDaemon(true);
			return thread;
		});
		List<Future<?>> futures = new ArrayList<>();

		try {
			for (int workerIndex = 0; workerIndex < workerCount; workerIndex++) {
				futures.add(executor.submit(() -> runWorker(count, call, results, nextIndex, firstFailure, executor)));
			}

			for (Future<?> future : futures) {
				awaitQuietly(future);
			}
		} finally {
			executor.shutdownNow();
		}

		if (firstFailure.get() != null) {
			throw firstFailure.get();
		}

		List<ImageResponse> orderedResults = new ArrayList<>();

		for (ImageResponse result : results) {
			orderedResults.add(result);
		}

		return ImageResponse.combine(orderedResults);
	}

	private static void runWorker(int count, IntFunction<ImageResponse> call, ImageResponse[] results, AtomicInteger nextIndex, AtomicReference<CommonLlmException> firstFailure, ExecutorService executor) {
		while (firstFailure.get() == null) {
			int callIndex = nextIndex.getAndIncrement();

			if (callIndex >= count) {
				return;
			}

			try {
				results[callIndex] = call.apply(callIndex);
			} catch (CommonLlmException exception) {
				recordFailure(firstFailure, exception, executor);
				return;
			} catch (RuntimeException exception) {
				recordFailure(firstFailure, new CommonLlmException(ErrorInfo.builder(ErrorCategory.INTERNAL_ERROR, "图片生成循环调用失败").cause(exception).build(), exception), executor);
				return;
			}
		}
	}

	/**
	 * 只保留第一次失败，并中断其余进行中的请求
	 */
	private static void recordFailure(AtomicReference<CommonLlmException> firstFailure, CommonLlmException exception, ExecutorService executor) {
		if (firstFailure.compareAndSet(null, exception)) {
			executor.shutdownNow();
		}
	}

	private static void awaitQuietly(Future<?> future) {
		try {
			future.get();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		} catch (ExecutionException exception) {
			// 工作线程内部已记录失败
		}
	}
}
