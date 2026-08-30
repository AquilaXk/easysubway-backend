package com.easysubway.journey.application;

import java.time.Duration;
import java.lang.management.ManagementFactory;
import com.sun.management.ThreadMXBean;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class JourneyApplicationDeadlineExecutor {

	private final JourneyApplicationService service;
	private final ExecutorService executor;
	private final ExecutorService measurementExecutor;
	private final long timeoutNanos;

	public JourneyApplicationDeadlineExecutor(
		JourneyApplicationService service,
		ExecutorService executor,
		ExecutorService measurementExecutor,
		Duration timeout
	) {
		this.service = Objects.requireNonNull(service, "service");
		this.executor = Objects.requireNonNull(executor, "executor");
		this.measurementExecutor = Objects.requireNonNull(measurementExecutor, "measurementExecutor");
		Objects.requireNonNull(timeout, "timeout");
		try {
			this.timeoutNanos = timeout.toNanos();
		} catch (ArithmeticException exception) {
			throw new IllegalArgumentException("timeout is too large", exception);
		}
		if (timeoutNanos <= 0) throw new IllegalArgumentException("timeout must be positive");
	}

	public Outcome execute(JourneyRequest request) {
		JourneyRequest original = Objects.requireNonNull(request, "request");
		long startedAtNanos = System.nanoTime();
		var cancellation = new AtomicBoolean();
		JourneyRequest boundedRequest = copyWithCancellation(original,
			() -> original.isCancelled() || cancellation.get());

		Future<JourneyExecutionResult> future;
		try {
			future = executor.submit(() -> service.execute(boundedRequest));
		} catch (RuntimeException exception) {
			cancellation.set(true);
			throw failure(DeadlineExecutionException.Reason.TASK_FAILED, exception);
		}

		long remainingNanos = timeoutNanos - (System.nanoTime() - startedAtNanos);
		if (remainingNanos <= 0) {
			cancellation.set(true);
			future.cancel(true);
			return new TimedOut();
		}
		try {
			return new Completed(future.get(remainingNanos, TimeUnit.NANOSECONDS));
		} catch (TimeoutException exception) {
			cancellation.set(true);
			future.cancel(true);
			return new TimedOut();
		} catch (InterruptedException exception) {
			cancellation.set(true);
			future.cancel(true);
			Thread.currentThread().interrupt();
			throw failure(DeadlineExecutionException.Reason.CALLER_INTERRUPTED, exception);
		} catch (ExecutionException exception) {
			cancellation.set(true);
			throw failure(DeadlineExecutionException.Reason.TASK_FAILED, exception.getCause());
		} catch (CancellationException exception) {
			cancellation.set(true);
			future.cancel(true);
			throw failure(DeadlineExecutionException.Reason.TASK_FAILED, exception);
		}
	}

	/** Executes the deployed benchmark path and measures only its worker-thread search work. */
	public MeasuredOutcome executeMeasured(JourneyRequest request) {
		JourneyRequest original = Objects.requireNonNull(request, "request");
		ThreadMXBean allocations = allocationBean();
		long startedAtNanos = System.nanoTime();
		var cancellation = new AtomicBoolean();
		JourneyRequest boundedRequest = copyWithCancellation(original,
			() -> original.isCancelled() || cancellation.get());

		Future<MeasuredValue> future;
		try {
			future = measurementExecutor.submit(() -> {
				long threadId = Thread.currentThread().threadId();
				long allocatedBefore = allocations.getThreadAllocatedBytes(threadId);
				if (allocatedBefore < 0) throw new IllegalStateException("worker allocation measurement is unavailable");
				long executionStarted = System.nanoTime();
				JourneyExecutionResult result = service.execute(boundedRequest);
				long elapsed = System.nanoTime() - executionStarted;
				long allocatedAfter = allocations.getThreadAllocatedBytes(threadId);
				if (elapsed < 0 || allocatedAfter < allocatedBefore) {
					throw new IllegalStateException("worker measurement is unavailable");
				}
				return new MeasuredValue(result, elapsed, allocatedAfter - allocatedBefore);
			});
		} catch (RuntimeException exception) {
			cancellation.set(true);
			throw failure(DeadlineExecutionException.Reason.TASK_FAILED, exception);
		}

		long remainingNanos = timeoutNanos - (System.nanoTime() - startedAtNanos);
		if (remainingNanos <= 0) {
			cancellation.set(true);
			future.cancel(true);
			return new TimedOut();
		}
		try {
			var measured = future.get(remainingNanos, TimeUnit.NANOSECONDS);
			return new MeasuredCompleted(measured.result(), measured.executionNanos(), measured.allocatedBytes());
		} catch (TimeoutException exception) {
			cancellation.set(true);
			future.cancel(true);
			return new TimedOut();
		} catch (InterruptedException exception) {
			cancellation.set(true);
			future.cancel(true);
			Thread.currentThread().interrupt();
			throw failure(DeadlineExecutionException.Reason.CALLER_INTERRUPTED, exception);
		} catch (ExecutionException exception) {
			cancellation.set(true);
			throw failure(DeadlineExecutionException.Reason.TASK_FAILED, exception.getCause());
		} catch (CancellationException exception) {
			cancellation.set(true);
			future.cancel(true);
			throw failure(DeadlineExecutionException.Reason.TASK_FAILED, exception);
		}
	}

	private static ThreadMXBean allocationBean() {
		if (!(ManagementFactory.getThreadMXBean() instanceof ThreadMXBean bean)
			|| !bean.isThreadAllocatedMemorySupported() || !bean.isThreadAllocatedMemoryEnabled()) {
			throw new IllegalStateException("worker allocation measurement is unavailable");
		}
		return bean;
	}

	private static JourneyRequest copyWithCancellation(
		JourneyRequest request,
		java.util.function.BooleanSupplier cancellationSignal
	) {
		return new JourneyRequest(
			request.requestId(),
			request.originStationId(),
			request.destinationStationId(),
			request.departure(),
			request.timePolicy(),
			request.walkingPace(),
			request.mobilityProfile(),
			request.constraintMode(),
			request.maxTransfers(),
			request.alternativeCount(),
			cancellationSignal
		);
	}

	private static DeadlineExecutionException failure(
		DeadlineExecutionException.Reason reason,
		Throwable cause
	) {
		return new DeadlineExecutionException(reason, cause);
	}

	public sealed interface Outcome permits Completed, TimedOut {
	}

	public sealed interface MeasuredOutcome permits MeasuredCompleted, TimedOut {
	}

	public record Completed(JourneyExecutionResult result) implements Outcome {
		public Completed {
			result = Objects.requireNonNull(result, "result");
		}
	}

	public record MeasuredCompleted(JourneyExecutionResult result, long executionNanos, long allocatedBytes)
		implements MeasuredOutcome {
		public MeasuredCompleted {
			result = Objects.requireNonNull(result, "result");
			if (executionNanos < 0 || allocatedBytes < 0) {
				throw new IllegalArgumentException("measured values must be nonnegative");
			}
		}
	}

	public record TimedOut() implements Outcome, MeasuredOutcome {
	}

	private record MeasuredValue(JourneyExecutionResult result, long executionNanos, long allocatedBytes) {
	}

	public static final class DeadlineExecutionException extends RuntimeException {

		private final Reason reason;

		private DeadlineExecutionException(Reason reason, Throwable cause) {
			super("Journey deadline execution failed: " + Objects.requireNonNull(reason, "reason"), cause);
			this.reason = reason;
		}

		public Reason reason() {
			return reason;
		}

		public enum Reason {
			CALLER_INTERRUPTED,
			TASK_FAILED
		}
	}
}
