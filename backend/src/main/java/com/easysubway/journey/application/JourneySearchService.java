package com.easysubway.journey.application;

import com.easysubway.journey.application.JourneyExecutionPorts.ActiveBundleSnapshotProvider;
import com.easysubway.journey.application.JourneyExecutionPorts.BundleSnapshot;
import com.easysubway.journey.application.JourneyExecutionPorts.JourneyPlan;
import com.easysubway.journey.application.JourneyExecutionPorts.RaptorExecutor;
import com.easysubway.journey.application.JourneyExecutionPorts.RaptorQuery;
import com.easysubway.journey.application.JourneyExecutionPorts.RealtimeQuery;
import com.easysubway.journey.application.JourneyExecutionPorts.RealtimeSnapshot;
import com.easysubway.journey.application.JourneyExecutionPorts.StrictRealtimeProvider;
import com.easysubway.journey.application.JourneySearchException.Code;
import com.easysubway.journey.application.JourneySearchUseCase.ConstraintMode;
import com.easysubway.journey.application.JourneySearchUseCase.DepartureNow;
import com.easysubway.journey.application.JourneySearchUseCase.DepartureScheduled;
import com.easysubway.journey.application.JourneySearchUseCase.JourneyCandidate;
import com.easysubway.journey.application.JourneySearchUseCase.JourneyRequestPolicy;
import com.easysubway.journey.application.JourneySearchUseCase.JourneySearchCommand;
import com.easysubway.journey.application.JourneySearchUseCase.JourneySearchResult;
import com.easysubway.journey.application.JourneySearchUseCase.JourneySourceIdentity;
import com.easysubway.journey.application.JourneySearchUseCase.TimePolicy;
import com.easysubway.journey.application.JourneySearchUseCase.TimeSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class JourneySearchService implements JourneySearchUseCase {

	private final Clock clock;
	private final ActiveBundleSnapshotProvider activeBundles;
	private final StrictRealtimeProvider realtimeProvider;
	private final RaptorExecutor raptorExecutor;
	private final ExecutorService executor;
	private final long timeoutNanos;

	public JourneySearchService(
		Clock clock,
		ActiveBundleSnapshotProvider activeBundles,
		StrictRealtimeProvider realtimeProvider,
		RaptorExecutor raptorExecutor,
		ExecutorService executor,
		Duration timeout
	) {
		this.clock = Objects.requireNonNull(clock, "clock");
		this.activeBundles = Objects.requireNonNull(activeBundles, "activeBundles");
		this.realtimeProvider = Objects.requireNonNull(realtimeProvider, "realtimeProvider");
		this.raptorExecutor = Objects.requireNonNull(raptorExecutor, "raptorExecutor");
		this.executor = Objects.requireNonNull(executor, "executor");
		timeout = Objects.requireNonNull(timeout, "timeout");
		if (timeout.isZero() || timeout.isNegative()) {
			throw new IllegalArgumentException("timeout must be positive");
		}
		try {
			this.timeoutNanos = timeout.toNanos();
		} catch (ArithmeticException exception) {
			throw new IllegalArgumentException("timeout is too large", exception);
		}
	}

	@Override
	public JourneySearchResult search(JourneySearchCommand command) {
		command = Objects.requireNonNull(command, "command");
		Instant acceptedAt = clock.instant();
		Future<JourneySearchResult> future;
		try {
			JourneySearchCommand immutableCommand = command;
			future = executor.submit(() -> execute(immutableCommand, acceptedAt));
		} catch (RejectedExecutionException exception) {
			throw failure(Code.ROUTE_SERVICE_UNAVAILABLE);
		}
		try {
			return future.get(timeoutNanos, TimeUnit.NANOSECONDS);
		} catch (TimeoutException exception) {
			future.cancel(true);
			throw failure(Code.JOURNEY_SEARCH_TIMEOUT);
		} catch (InterruptedException exception) {
			future.cancel(true);
			Thread.currentThread().interrupt();
			throw failure(Code.ROUTE_SERVICE_UNAVAILABLE);
		} catch (ExecutionException exception) {
			Throwable cause = exception.getCause();
			if (cause instanceof JourneySearchException journeyFailure) {
				throw journeyFailure;
			}
			throw failure(Code.ROUTE_SERVICE_UNAVAILABLE);
		}
	}

	private JourneySearchResult execute(JourneySearchCommand command, Instant acceptedAt) {
		BundleSnapshot bundle = captureBundle(acceptedAt);
		if (!bundle.freshUntil().isAfter(acceptedAt)) {
			throw failure(Code.ROUTING_BUNDLE_STALE);
		}
		Instant effectiveDeparture = switch (command.departure()) {
			case DepartureNow ignored -> acceptedAt;
			case DepartureScheduled scheduled -> scheduled.requestedAt().toInstant();
		};
		RealtimeSnapshot realtime = command.timePolicy() == TimePolicy.REALTIME_REQUIRED
			? captureRealtime(command, acceptedAt, effectiveDeparture, bundle)
			: null;
		JourneyPlan plan = executeRaptor(command, acceptedAt, effectiveDeparture, bundle, realtime);
		validatePlan(command, bundle, realtime, plan);
		Instant validUntil = realtime == null || bundle.freshUntil().isBefore(realtime.freshUntil())
			? bundle.freshUntil()
			: realtime.freshUntil();
		ZoneId serviceZone = ZoneId.of(bundle.serviceTimezone());
		OffsetDateTime effectiveDepartureTime = OffsetDateTime.ofInstant(effectiveDeparture, serviceZone);
		return new JourneySearchResult(
			command.requestId(),
			command.requestId() + ":" + bundle.generation(),
			acceptedAt,
			validUntil,
			effectiveDepartureTime,
			effectiveDepartureTime.toLocalDate(),
			bundle.serviceTimezone(),
			new JourneySourceIdentity(
				bundle.routeBundleId(),
				bundle.routeBundleSha256(),
				bundle.timetableSnapshotId(),
				bundle.accessibilitySnapshotId(),
				realtime == null ? null : realtime.snapshotId()
			),
			new JourneyRequestPolicy(
				command.timePolicy(),
				command.mobilityProfile(),
				command.constraintMode(),
				command.maxTransfers(),
				command.alternativeCount()
			),
			plan.candidates()
		);
	}

	private BundleSnapshot captureBundle(Instant acceptedAt) {
		try {
			BundleSnapshot bundle = activeBundles.capture(acceptedAt);
			if (bundle == null) {
				throw failure(Code.ROUTING_BUNDLE_UNAVAILABLE);
			}
			return bundle;
		} catch (JourneySearchException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw failure(Code.ROUTING_BUNDLE_UNAVAILABLE);
		}
	}

	private RealtimeSnapshot captureRealtime(
		JourneySearchCommand command,
		Instant acceptedAt,
		Instant effectiveDeparture,
		BundleSnapshot bundle
	) {
		RealtimeSnapshot realtime;
		try {
			realtime = realtimeProvider.load(new RealtimeQuery(
				acceptedAt,
				effectiveDeparture,
				command,
				bundle
			));
		} catch (JourneySearchException exception) {
			if (exception.code() == Code.REALTIME_REQUIRED_UNAVAILABLE
				|| exception.code() == Code.ROUTING_IDENTITY_MISMATCH) {
				throw exception;
			}
			throw failure(Code.REALTIME_REQUIRED_UNAVAILABLE);
		} catch (RuntimeException exception) {
			throw failure(Code.REALTIME_REQUIRED_UNAVAILABLE);
		}
		if (realtime == null) {
			throw failure(Code.REALTIME_REQUIRED_UNAVAILABLE);
		}
		if (!bundle.routeBundleSha256().equals(realtime.routeBundleSha256())) {
			throw failure(Code.ROUTING_IDENTITY_MISMATCH);
		}
		if (realtime.observedAt().isAfter(acceptedAt) || !realtime.freshUntil().isAfter(acceptedAt)) {
			throw failure(Code.REALTIME_REQUIRED_UNAVAILABLE);
		}
		return realtime;
	}

	private JourneyPlan executeRaptor(
		JourneySearchCommand command,
		Instant acceptedAt,
		Instant effectiveDeparture,
		BundleSnapshot bundle,
		RealtimeSnapshot realtime
	) {
		try {
			JourneyPlan plan = raptorExecutor.execute(new RaptorQuery(
				acceptedAt,
				effectiveDeparture,
				command,
				bundle,
				realtime
			));
			if (plan == null) {
				throw failure(Code.ROUTE_SERVICE_UNAVAILABLE);
			}
			return plan;
		} catch (JourneySearchException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw failure(Code.ROUTE_SERVICE_UNAVAILABLE);
		}
	}

	private static void validatePlan(
		JourneySearchCommand command,
		BundleSnapshot bundle,
		RealtimeSnapshot realtime,
		JourneyPlan plan
	) {
		String realtimeSnapshotId = realtime == null ? null : realtime.snapshotId();
		if (!bundle.routeBundleSha256().equals(plan.routeBundleSha256())
			|| !bundle.timetableSnapshotId().equals(plan.timetableSnapshotId())
			|| !bundle.accessibilitySnapshotId().equals(plan.accessibilitySnapshotId())
			|| !Objects.equals(realtimeSnapshotId, plan.realtimeSnapshotId())) {
			throw failure(Code.ROUTING_IDENTITY_MISMATCH);
		}
		List<JourneyCandidate> candidates = plan.candidates();
		if (candidates.isEmpty()) {
			throw failure(Code.ROUTE_NOT_FOUND);
		}
		if (candidates.size() > command.alternativeCount()) {
			throw failure(Code.ROUTE_SERVICE_UNAVAILABLE);
		}
		if (candidates.stream().anyMatch(candidate -> candidate.transferCount() > command.maxTransfers())
			|| new HashSet<>(candidates.stream().map(JourneyCandidate::journeyId).toList()).size()
				!= candidates.size()) {
			throw failure(Code.ROUTE_SERVICE_UNAVAILABLE);
		}
		TimeSource requiredTimeSource = command.timePolicy() == TimePolicy.TIMETABLE_REQUIRED
			? TimeSource.TIMETABLE
			: TimeSource.REALTIME;
		if (candidates.stream().anyMatch(candidate -> candidate.timeSource() != requiredTimeSource)) {
			throw failure(command.timePolicy() == TimePolicy.REALTIME_REQUIRED
				? Code.REALTIME_REQUIRED_UNAVAILABLE
				: Code.ROUTING_IDENTITY_MISMATCH);
		}
		if (command.constraintMode() == ConstraintMode.REQUIRE_STEP_FREE
			&& candidates.stream().anyMatch(candidate -> !candidate.stairFree())) {
			throw failure(Code.ACCESSIBILITY_CONSTRAINT_UNSATISFIED);
		}
	}

	private static JourneySearchException failure(Code code) {
		return new JourneySearchException(code);
	}
}
