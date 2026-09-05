package com.easysubway.route.application.service;

import com.easysubway.journey.application.ActiveJourneySnapshotPort.ActiveJourneySnapshot;
import com.easysubway.journey.application.JourneyRealtimePort;
import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeUpdate;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeUpdates;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class JourneyRealtimeAdapter implements JourneyRealtimePort {

	private final JourneyTimetableRealtimeResolver resolver;
	private final Clock clock;
	private final Duration freshnessTtl;
	private final RouteTimetableRaptorPlanner planner = new RouteTimetableRaptorPlanner();

	public JourneyRealtimeAdapter(
		JourneyTimetableRealtimeResolver resolver,
		Clock clock,
		Duration freshnessTtl
	) {
		this.resolver = Objects.requireNonNull(resolver, "resolver");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.freshnessTtl = requirePositive(freshnessTtl);
	}

	@Override
	public RealtimeObservation requireFresh(
		JourneyRequest request,
		ActiveJourneySnapshot snapshot,
		Instant effectiveInstant
	) {
		JourneyRequest requiredRequest = Objects.requireNonNull(request, "request");
		ActiveJourneySnapshot requiredSnapshot = Objects.requireNonNull(snapshot, "snapshot");
		Instant requiredEffectiveInstant = Objects.requireNonNull(effectiveInstant, "effectiveInstant");
		if (requiredRequest.timePolicy() != JourneyRequest.TimePolicy.REALTIME_REQUIRED) {
			throw new IllegalArgumentException("Journey realtime adapter requires REALTIME_REQUIRED");
		}
		requireNotCancelled(requiredRequest);

		RaptorRouteBundleRuntimeView routeRuntime = requireRouteRuntime(requiredSnapshot);
		List<com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeQuery> queries =
			planner.realtimeQueries(
				JourneyRaptorQuery.from(requiredRequest, requiredEffectiveInstant),
				routeRuntime.compiledTimetable()
			);
		if (queries.size() != 1) {
			throw new IllegalArgumentException("Journey realtime requires exactly one origin query");
		}
		requireNotCancelled(requiredRequest);

		TimetableRealtimeUpdates updates = resolver.resolve(List.copyOf(queries));
		requireNotCancelled(requiredRequest);
		ValidatedObservation validated = validateUpdates(updates, clock.instant(), requiredEffectiveInstant);
		RaptorRealtimeRuntimeView realtimeRuntime = RaptorRealtimeRuntimeView.compile(
			validated.identity(), routeRuntime, updates);
		requireNotCancelled(requiredRequest);
		return new RealtimeObservation(
			validated.identity(),
			requiredSnapshot.routeBundleSha256(),
			realtimeRuntime,
			validated.validUntil(),
			true
		);
	}

	private ValidatedObservation validateUpdates(
		TimetableRealtimeUpdates updates,
		Instant capturedNow,
		Instant effectiveInstant
	) {
		if (updates == null) {
			throw new IllegalArgumentException("realtime updates are required");
		}
		if (!updates.available() || updates.updates().isEmpty()) {
			throw new IllegalArgumentException("realtime updates are unavailable");
		}
		String identity = requireSingleIdentity(updates.version());
		Instant validUntil = null;
		for (TimetableRealtimeUpdate update : updates.updates()) {
			if (!identity.equals(update.providerSnapshotId())) {
				throw new IllegalArgumentException("realtime update identity is mixed");
			}
			Instant observedAt = update.providerObservedAt();
			Duration age = Duration.between(observedAt, capturedNow);
			if (age.isNegative()) {
				throw new IllegalArgumentException("realtime observation time is in the future");
			}
			Instant expiresAt;
			try {
				expiresAt = observedAt.plus(freshnessTtl);
			} catch (DateTimeException | ArithmeticException exception) {
				throw new IllegalArgumentException("realtime observation expiry is invalid", exception);
			}
			if (age.compareTo(freshnessTtl) >= 0 || !expiresAt.isAfter(capturedNow)) {
				throw new IllegalArgumentException("realtime observation is expired");
			}
			if (validUntil == null || expiresAt.isBefore(validUntil)) {
				validUntil = expiresAt;
			}
		}
		if (validUntil == null || !validUntil.isAfter(effectiveInstant)) {
			throw new IllegalArgumentException("realtime observation is invalid for the effective instant");
		}
		return new ValidatedObservation(identity, validUntil);
	}

	private static RaptorRouteBundleRuntimeView requireRouteRuntime(ActiveJourneySnapshot snapshot) {
		if (!(snapshot.runtimeView() instanceof RaptorRouteBundleRuntimeView runtime)) {
			throw new IllegalArgumentException("unsupported Journey realtime runtime view");
		}
		if (!snapshot.routeBundleSha256().equals(runtime.routeBundleSha256())
			|| snapshot.generation() != runtime.generation()) {
			throw new IllegalArgumentException("Journey realtime runtime view does not match snapshot");
		}
		return runtime;
	}

	private static String requireSingleIdentity(String identity) {
		if (identity == null || identity.isBlank() || identity.indexOf('+') >= 0) {
			throw new IllegalArgumentException("realtime updates require a single provider identity");
		}
		return identity;
	}

	private static Duration requirePositive(Duration value) {
		Objects.requireNonNull(value, "freshnessTtl");
		if (value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException("freshnessTtl must be positive");
		}
		return value;
	}

	private static void requireNotCancelled(JourneyRequest request) {
		if (request.isCancelled()) {
			throw new IllegalStateException("Journey realtime resolution was cancelled");
		}
	}

	private record ValidatedObservation(String identity, Instant validUntil) {
	}
}
