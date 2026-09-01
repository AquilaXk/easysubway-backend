package com.easysubway.journey.application;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Applies a caller-supplied bounded deadline to one non-wire temporal profile execution. */
public final class JourneyProfileDeadlineExecutor {

	private final JourneyProfileApplicationService service;
	private final ExecutorService executor;

	public JourneyProfileDeadlineExecutor(JourneyProfileApplicationService service, ExecutorService executor) {
		this.service = Objects.requireNonNull(service, "service");
		this.executor = Objects.requireNonNull(executor, "executor");
	}

	public Outcome execute(JourneyRaptorQuery query, Duration deadline) {
		JourneyRaptorQuery original = Objects.requireNonNull(query, "query");
		long deadlineNanos = positiveNanos(deadline);
		if (original.isCancelled()) {
			return new Completed(new JourneyProfileExecutionResult.Failure(
				JourneyProfileExecutionResult.Reason.CANCELLED));
		}
		long startedAt = System.nanoTime();
		var cancelled = new AtomicBoolean();
		JourneyRaptorQuery bounded = copyWithCancellation(original, () -> original.isCancelled() || cancelled.get());
		Future<JourneyProfileExecutionResult> future;
		try {
			future = executor.submit(() -> service.execute(bounded));
		} catch (RuntimeException exception) {
			cancelled.set(true);
			throw new DeadlineExecutionException(Reason.TASK_FAILED, exception);
		}
		long remaining = deadlineNanos - (System.nanoTime() - startedAt);
		if (remaining <= 0) return cancel(future, cancelled);
		try {
			JourneyProfileExecutionResult result = future.get(remaining, TimeUnit.NANOSECONDS);
			if (cancelled.get() || original.isCancelled() || System.nanoTime() - startedAt >= deadlineNanos) {
				return cancel(future, cancelled);
			}
			return new Completed(result);
		} catch (TimeoutException exception) {
			return cancel(future, cancelled);
		} catch (CancellationException exception) {
			cancelled.set(true);
			throw new DeadlineExecutionException(Reason.TASK_FAILED, exception);
		} catch (InterruptedException exception) {
			cancelled.set(true);
			future.cancel(true);
			Thread.currentThread().interrupt();
			throw new DeadlineExecutionException(Reason.CALLER_INTERRUPTED, exception);
		} catch (ExecutionException exception) {
			cancelled.set(true);
			throw new DeadlineExecutionException(Reason.TASK_FAILED, exception.getCause());
		}
	}

	private static TimedOut cancel(Future<?> future, AtomicBoolean cancelled) {
		cancelled.set(true);
		future.cancel(true);
		return new TimedOut();
	}

	private static long positiveNanos(Duration deadline) {
		Objects.requireNonNull(deadline, "deadline");
		try {
			long nanos = deadline.toNanos();
			if (nanos <= 0) throw new IllegalArgumentException("deadline must be positive");
			return nanos;
		} catch (ArithmeticException exception) {
			throw new IllegalArgumentException("deadline is too large", exception);
		}
	}

	private static JourneyRaptorQuery copyWithCancellation(
		JourneyRaptorQuery query,
		java.util.function.BooleanSupplier cancellationSignal
	) {
		return new JourneyRaptorQuery(query.requestId(), query.originStationId(), query.destinationStationId(),
			query.temporalQuery(), query.timePolicy(), query.walkingPace(), query.mobilityProfile(),
			query.constraintMode(), query.maxTransfers(), query.alternativeCount(), cancellationSignal);
	}

	public sealed interface Outcome permits Completed, TimedOut {
	}

	public record Completed(JourneyProfileExecutionResult result) implements Outcome {
		public Completed {
			result = Objects.requireNonNull(result, "result");
		}
	}

	public record TimedOut() implements Outcome {
	}

	public static final class DeadlineExecutionException extends RuntimeException {
		private final Reason reason;

		private DeadlineExecutionException(Reason reason, Throwable cause) {
			super("Journey profile deadline execution failed: " + Objects.requireNonNull(reason, "reason"), cause);
			this.reason = reason;
		}

		public Reason reason() {
			return reason;
		}
	}

	public enum Reason {
		CALLER_INTERRUPTED,
		TASK_FAILED
	}
}
