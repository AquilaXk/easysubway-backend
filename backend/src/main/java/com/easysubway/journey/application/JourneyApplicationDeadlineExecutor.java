package com.easysubway.journey.application;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class JourneyApplicationDeadlineExecutor {

	private final JourneyApplicationService service;
	private final ExecutorService executor;
	private final long timeoutNanos;

	public JourneyApplicationDeadlineExecutor(
		JourneyApplicationService service,
		ExecutorService executor,
		Duration timeout
	) {
		this.service = Objects.requireNonNull(service, "service");
		this.executor = Objects.requireNonNull(executor, "executor");
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
		var cancellation = new AtomicBoolean();
		JourneyRequest boundedRequest = copyWithCancellation(original,
			() -> original.isCancelled() || cancellation.get() || Thread.currentThread().isInterrupted());

		Future<JourneyExecutionResult> future;
		try {
			future = executor.submit(() -> service.execute(boundedRequest));
		} catch (RuntimeException exception) {
			cancellation.set(true);
			throw failure(DeadlineExecutionException.Reason.TASK_FAILED, exception);
		}

		try {
			return new Completed(future.get(timeoutNanos, TimeUnit.NANOSECONDS));
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
			Throwable cause = exception.getCause() == null ? exception : exception.getCause();
			throw failure(DeadlineExecutionException.Reason.TASK_FAILED, cause);
		}
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

	public record Completed(JourneyExecutionResult result) implements Outcome {
		public Completed {
			result = Objects.requireNonNull(result, "result");
		}
	}

	public record TimedOut() implements Outcome {
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
