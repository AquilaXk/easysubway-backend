package com.easysubway.route.application.service;

import com.easysubway.journey.application.ActiveJourneySnapshotPort.ActiveJourneySnapshot;
import com.easysubway.journey.application.JourneyRealtimePort;
import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.ScanWorkspacePool;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class JourneyRealtimeAdapter implements JourneyRealtimePort {

	private final JourneyTimetableRealtimeResolver resolver;
	private final Clock clock;
	private final Duration freshnessTtl;
	private final RouteTimetableRaptorPlanner planner;

	public JourneyRealtimeAdapter(
		JourneyTimetableRealtimeResolver resolver,
		Clock clock,
		Duration freshnessTtl
	) {
		this(resolver, clock, freshnessTtl, ScanWorkspacePool.shared());
	}

	public JourneyRealtimeAdapter(
		JourneyTimetableRealtimeResolver resolver,
		Clock clock,
		Duration freshnessTtl,
		ScanWorkspacePool workspacePool
	) {
		this.resolver = Objects.requireNonNull(resolver, "resolver");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.freshnessTtl = requirePositive(freshnessTtl);
		this.planner = new RouteTimetableRaptorPlanner(Objects.requireNonNull(workspacePool, "workspacePool"));
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
		JourneyRaptorQuery raptorQuery = JourneyRaptorQuery.from(requiredRequest, requiredEffectiveInstant);
		List<JourneyTimetableRealtimeResolver.Query> queries =
			planner.realtimeQueries(raptorQuery, routeRuntime.compiledTimetable());
		if (queries.isEmpty()) {
			throw new IllegalArgumentException("Journey realtime requires at least one origin query");
		}
		Set<LocalDate> coveredServiceDates = Set.copyOf(planner.realtimeQueryServiceDates(raptorQuery));
		requireNotCancelled(requiredRequest);

		JourneyTimetableRealtimeResolver.Updates updates = resolver.resolve(List.copyOf(queries));
		requireNotCancelled(requiredRequest);
		ValidatedObservation validated = validateUpdates(updates, clock.instant(), requiredEffectiveInstant);
		RaptorRealtimeRuntimeView realtimeRuntime = RaptorRealtimeRuntimeView.compile(
			validated.identity(), routeRuntime, updates, coveredServiceDates);
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
		JourneyTimetableRealtimeResolver.Updates updates,
		Instant capturedNow,
		Instant effectiveInstant
	) {
		if (updates == null) {
			throw new IllegalArgumentException("realtime updates are required");
		}
		if (!updates.available() || updates.updates().isEmpty()) {
			throw new IllegalArgumentException("realtime updates are unavailable");
		}
		String identity = updates.version();
		Set<String> declaredIdentities = parseDeclaredIdentities(identity);
		Set<String> observedIdentities = new LinkedHashSet<>();
		Instant validUntil = null;
		for (JourneyTimetableRealtimeResolver.Update update : updates.updates()) {
			if (update == null || !declaredIdentities.contains(update.providerSnapshotId())) {
				throw new IllegalArgumentException("realtime update identity is mixed");
			}
			observedIdentities.add(update.providerSnapshotId());
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
		if (!observedIdentities.equals(declaredIdentities)) {
			throw new IllegalArgumentException("realtime update identity is mixed");
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

	private static Set<String> parseDeclaredIdentities(String identity) {
		if (identity == null || identity.isBlank() || identity.indexOf('+') >= 0) {
			throw new IllegalArgumentException("realtime updates require a single provider identity");
		}
		if (identity.indexOf('|') < 0) {
			return Set.of(identity);
		}
		String[] parts = identity.split("\\|", -1);
		if (parts.length < 2) {
			throw new IllegalArgumentException("realtime updates require a single provider identity");
		}
		Set<String> parsed = new LinkedHashSet<>(parts.length);
		for (String part : parts) {
			if (part.isBlank() || !parsed.add(part)) {
				throw new IllegalArgumentException("realtime update identity is mixed");
			}
		}
		return Set.copyOf(parsed);
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
