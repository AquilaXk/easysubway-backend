package com.easysubway.journey.application;

import static com.easysubway.journey.application.JourneyApplicationDeadlineExecutor.DeadlineExecutionException.Reason.CALLER_INTERRUPTED;
import static com.easysubway.journey.application.JourneyApplicationDeadlineExecutor.DeadlineExecutionException.Reason.TASK_FAILED;
import static com.easysubway.journey.application.JourneyExecutionFailure.Reason.CANCELLED;
import static com.easysubway.journey.application.JourneyExecutionFailure.Reason.NO_ROUTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class JourneyApplicationDeadlineExecutorTest {

	private static final JourneyRequest REQUEST = new JourneyRequest(
		"01K1Y000000000000000000000",
		"station-origin",
		"station-destination",
		new JourneyRequest.Departure.Now(),
		JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
		JourneyRequest.WalkingPace.STANDARD,
		JourneyRequest.MobilityProfile.STANDARD,
		JourneyRequest.ConstraintMode.NONE,
		2,
		3,
		() -> false
	);

	@Test
	void deadline전에끝난결과를정확히한번반환한다() {
		var service = mock(JourneyApplicationService.class);
		var result = new JourneyExecutionFailure(NO_ROUTE);
		when(service.execute(any())).thenReturn(result);

		try (var workers = Executors.newSingleThreadExecutor(); var measurementWorkers = Executors.newSingleThreadExecutor()) {
			var executor = new JourneyApplicationDeadlineExecutor(service, workers, measurementWorkers, Duration.ofSeconds(1));

			assertThat(executor.execute(REQUEST))
				.isEqualTo(new JourneyApplicationDeadlineExecutor.Completed(result));
		}

		var request = ArgumentCaptor.forClass(JourneyRequest.class);
		verify(service).execute(request.capture());
		verifyNoMoreInteractions(service);
		assertThat(request.getValue())
			.usingRecursiveComparison()
			.ignoringFields("cancellationSignal")
			.isEqualTo(REQUEST);
		assertThat(request.getValue().isCancelled()).isFalse();
	}

	@Test
	void measured실행은전용platformWorker에서실제서버측시간과할당을측정한다() {
		var workerVirtualStates = new ConcurrentLinkedQueue<Boolean>();
		var retainedBoundaryAllocation = new AtomicReference<byte[]>();
		var service = new JourneyApplicationService(
			effectiveInstant -> {
				workerVirtualStates.add(Thread.currentThread().isVirtual());
				retainedBoundaryAllocation.set(new byte[128 * 1024]);
				throw new IllegalStateException("active snapshot unavailable");
			},
			(request, snapshot, effectiveInstant) -> { throw new AssertionError("realtime must not be called"); },
			(request, snapshot, effectiveInstant, realtime) -> { throw new AssertionError("raptor must not be called"); },
			Clock.systemUTC());

		try (var searchWorkers = Executors.newVirtualThreadPerTaskExecutor();
			var measurementWorkers = Executors.newFixedThreadPool(1, Thread.ofPlatform().factory())) {
			var executor = new JourneyApplicationDeadlineExecutor(
				service, searchWorkers, measurementWorkers, Duration.ofSeconds(1));

			assertThat(executor.execute(REQUEST))
				.isInstanceOf(JourneyApplicationDeadlineExecutor.Completed.class);
			assertThat(executor.executeMeasured(REQUEST))
				.isInstanceOf(JourneyApplicationDeadlineExecutor.MeasuredCompleted.class)
				.satisfies(outcome -> {
					var measured = (JourneyApplicationDeadlineExecutor.MeasuredCompleted) outcome;
					assertThat(measured.executionNanos()).isPositive();
					assertThat(measured.allocatedBytes()).isPositive();
				});
			assertThat(workerVirtualStates).containsExactly(true, false);
			assertThat(retainedBoundaryAllocation.get()).hasSize(128 * 1024);
		}
	}

	@Test
	void measured실행의timeout과실패는typed결과로구분한다() throws Exception {
		var service = mock(JourneyApplicationService.class);
		var timedOutFuture = mock(java.util.concurrent.Future.class);
		when(timedOutFuture.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenThrow(new TimeoutException());
		var failedFuture = mock(java.util.concurrent.Future.class);
		var cause = new IllegalStateException("measurement failed");
		when(failedFuture.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenThrow(new java.util.concurrent.ExecutionException(cause));
		var workers = mock(java.util.concurrent.ExecutorService.class);
		when(workers.submit(any(Callable.class))).thenReturn(timedOutFuture, failedFuture);
		var executor = new JourneyApplicationDeadlineExecutor(service, workers, workers, Duration.ofSeconds(1));

		assertThat(executor.executeMeasured(REQUEST)).isEqualTo(new JourneyApplicationDeadlineExecutor.TimedOut());
		assertThatThrownBy(() -> executor.executeMeasured(REQUEST))
			.isInstanceOf(JourneyApplicationDeadlineExecutor.DeadlineExecutionException.class)
			.hasFieldOrPropertyWithValue("reason", TASK_FAILED)
			.hasCause(cause);
		verify(timedOutFuture).cancel(true);
	}

	@Test
	void measured값은음수를허용하지않는다() {
		var result = new JourneyExecutionFailure(NO_ROUTE);

		assertThatThrownBy(() -> new JourneyApplicationDeadlineExecutor.MeasuredCompleted(result, -1, 0))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("measured values must be nonnegative");
		assertThatThrownBy(() -> new JourneyApplicationDeadlineExecutor.MeasuredCompleted(result, 0, -1))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("measured values must be nonnegative");
	}

	@Test
	void timeout은작업을취소하고late결과를공개하지않는다() throws Exception {
		var service = mock(JourneyApplicationService.class);
		var started = new CountDownLatch(1);
		var cancellationObserved = new CountDownLatch(1);
		when(service.execute(any())).thenAnswer(invocation -> {
			JourneyRequest request = invocation.getArgument(0);
			started.countDown();
			while (!Thread.currentThread().isInterrupted()) {
				LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
			}
			assertThat(request.isCancelled()).isTrue();
			cancellationObserved.countDown();
			return new JourneyExecutionFailure(NO_ROUTE);
		});

		try (var workers = Executors.newSingleThreadExecutor(); var measurementWorkers = Executors.newSingleThreadExecutor()) {
			var executor = new JourneyApplicationDeadlineExecutor(service, workers, measurementWorkers, Duration.ofSeconds(1));

			assertThat(executor.execute(REQUEST))
				.isEqualTo(new JourneyApplicationDeadlineExecutor.TimedOut());
			assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
			assertThat(cancellationObserved.await(1, TimeUnit.SECONDS)).isTrue();
		}
		verify(service).execute(any());
		verifyNoMoreInteractions(service);
	}

	@Test
	void submit에서inline실행된late결과도deadline을우회하지않는다() throws Exception {
		var service = mock(JourneyApplicationService.class);
		var result = new JourneyExecutionFailure(NO_ROUTE);
		when(service.execute(any())).thenReturn(result);
		var inlineExecutor = mock(java.util.concurrent.ExecutorService.class);
		when(inlineExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
			Callable<JourneyExecutionResult> task = invocation.getArgument(0);
			return CompletableFuture.completedFuture(task.call());
		});
		var executor = new JourneyApplicationDeadlineExecutor(
			service, inlineExecutor, inlineExecutor, Duration.ofNanos(1));

		assertThat(executor.execute(REQUEST))
			.isEqualTo(new JourneyApplicationDeadlineExecutor.TimedOut());
		verify(service).execute(any());
		verifyNoMoreInteractions(service);
	}

	@Test
	void executor가acceptedFuture를취소하면typedFailure와cancellationSignal로닫는다() throws Exception {
		var service = mock(JourneyApplicationService.class);
		var cancellationObserved = new CountDownLatch(1);
		when(service.execute(any())).thenAnswer(invocation -> {
			JourneyRequest request = invocation.getArgument(0);
			while (!request.isCancelled()) {
				LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
			}
			cancellationObserved.countDown();
			return new JourneyExecutionFailure(CANCELLED);
		});
		var cancelledFuture = mock(java.util.concurrent.Future.class);
		when(cancelledFuture.get(anyLong(), eq(TimeUnit.NANOSECONDS)))
			.thenThrow(new CancellationException("executor cancelled accepted task"));
		var workers = mock(java.util.concurrent.ExecutorService.class);
		var worker = new AtomicReference<Thread>();
		when(workers.submit(any(Callable.class))).thenAnswer(invocation -> {
			Callable<JourneyExecutionResult> task = invocation.getArgument(0);
			worker.set(Thread.startVirtualThread(() -> {
				try {
					task.call();
				} catch (Exception exception) {
					throw new AssertionError(exception);
				}
			}));
			return cancelledFuture;
		});
		var executor = new JourneyApplicationDeadlineExecutor(service, workers, workers, Duration.ofSeconds(1));

		assertThatThrownBy(() -> executor.execute(REQUEST))
			.isInstanceOf(JourneyApplicationDeadlineExecutor.DeadlineExecutionException.class)
			.hasFieldOrPropertyWithValue("reason", TASK_FAILED)
			.hasCauseInstanceOf(CancellationException.class);
		assertThat(cancellationObserved.await(1, TimeUnit.SECONDS)).isTrue();
		worker.get().join(1_000);
		assertThat(worker.get().isAlive()).isFalse();
		verify(service).execute(any());
		verifyNoMoreInteractions(service);
	}

	@Test
	void 이미취소된요청은success가아닌application취소결과만반환한다() {
		var service = mock(JourneyApplicationService.class);
		when(service.execute(any())).thenAnswer(invocation -> {
			JourneyRequest request = invocation.getArgument(0);
			return request.isCancelled()
				? new JourneyExecutionFailure(CANCELLED)
				: new JourneyExecutionFailure(NO_ROUTE);
		});
		var cancelledRequest = copyRequest(() -> true);

		try (var workers = Executors.newSingleThreadExecutor(); var measurementWorkers = Executors.newSingleThreadExecutor()) {
			var executor = new JourneyApplicationDeadlineExecutor(service, workers, measurementWorkers, Duration.ofSeconds(1));

			assertThat(executor.execute(cancelledRequest))
				.isEqualTo(new JourneyApplicationDeadlineExecutor.Completed(
					new JourneyExecutionFailure(CANCELLED)));
		}
		verify(service).execute(any());
		verifyNoMoreInteractions(service);
	}

	@Test
	void caller중단은timeout으로바꾸지않고interrupt를복원한다() throws Exception {
		var service = mock(JourneyApplicationService.class);
		var started = new CountDownLatch(1);
		when(service.execute(any())).thenAnswer(invocation -> {
			JourneyRequest request = invocation.getArgument(0);
			started.countDown();
			while (!request.isCancelled()) {
				LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
			}
			return new JourneyExecutionFailure(CANCELLED);
		});
		var thrown = new AtomicReference<Throwable>();
		var interrupted = new AtomicBoolean();

		try (var workers = Executors.newSingleThreadExecutor(); var measurementWorkers = Executors.newSingleThreadExecutor()) {
			var executor = new JourneyApplicationDeadlineExecutor(service, workers, measurementWorkers, Duration.ofSeconds(5));
			var caller = Thread.startVirtualThread(() -> {
				try {
					executor.execute(REQUEST);
				} catch (Throwable exception) {
					thrown.set(exception);
				} finally {
					interrupted.set(Thread.currentThread().isInterrupted());
				}
			});

			assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
			caller.interrupt();
			caller.join(1_000);
			assertThat(caller.isAlive()).isFalse();
		}

		assertThat(thrown.get())
			.isInstanceOf(JourneyApplicationDeadlineExecutor.DeadlineExecutionException.class)
			.extracting("reason")
			.isEqualTo(CALLER_INTERRUPTED);
		assertThat(interrupted).isTrue();
		verify(service).execute(any());
		verifyNoMoreInteractions(service);
	}

	@Test
	void application예외는원인을보존하고대체실행하지않는다() {
		var service = mock(JourneyApplicationService.class);
		var cause = new IllegalStateException("planner failed");
		when(service.execute(any())).thenThrow(cause);

		try (var workers = Executors.newSingleThreadExecutor(); var measurementWorkers = Executors.newSingleThreadExecutor()) {
			var executor = new JourneyApplicationDeadlineExecutor(service, workers, measurementWorkers, Duration.ofSeconds(1));

			var thrown = org.assertj.core.api.Assertions.catchThrowable(() -> executor.execute(REQUEST));
			assertThat(thrown)
				.isInstanceOf(JourneyApplicationDeadlineExecutor.DeadlineExecutionException.class)
				.hasCause(cause);
			assertThat(((JourneyApplicationDeadlineExecutor.DeadlineExecutionException) thrown).reason())
				.isEqualTo(TASK_FAILED);
		}
		verify(service).execute(any());
		verifyNoMoreInteractions(service);

		var validationExecutor = mock(java.util.concurrent.ExecutorService.class);
		assertThatThrownBy(() -> new JourneyApplicationDeadlineExecutor(
			service, validationExecutor, validationExecutor, Duration.ZERO))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("timeout must be positive");
		assertThatThrownBy(() -> new JourneyApplicationDeadlineExecutor(
			service, validationExecutor, validationExecutor, Duration.ofSeconds(Long.MAX_VALUE)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("timeout is too large");

		try (var rejected = Executors.newSingleThreadExecutor()) {
			rejected.shutdown();
			var executor = new JourneyApplicationDeadlineExecutor(service, rejected, rejected, Duration.ofSeconds(1));
			assertThatThrownBy(() -> executor.execute(REQUEST))
				.isInstanceOf(JourneyApplicationDeadlineExecutor.DeadlineExecutionException.class)
				.hasFieldOrPropertyWithValue("reason", TASK_FAILED)
				.hasCauseInstanceOf(RejectedExecutionException.class);
		}
	}

	private static JourneyRequest copyRequest(java.util.function.BooleanSupplier cancellationSignal) {
		return new JourneyRequest(
			REQUEST.requestId(),
			REQUEST.originStationId(),
			REQUEST.destinationStationId(),
			REQUEST.departure(),
			REQUEST.timePolicy(),
			REQUEST.walkingPace(),
			REQUEST.mobilityProfile(),
			REQUEST.constraintMode(),
			REQUEST.maxTransfers(),
			REQUEST.alternativeCount(),
			cancellationSignal
		);
	}
}
