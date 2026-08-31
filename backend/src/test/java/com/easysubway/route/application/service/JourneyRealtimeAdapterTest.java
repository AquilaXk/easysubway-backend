package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.journey.application.ActiveJourneySnapshotPort.ActiveJourneySnapshot;
import com.easysubway.journey.application.JourneyCandidate;
import com.easysubway.journey.application.JourneyRaptorRuntimeView;
import com.easysubway.journey.application.JourneyRealtimePort.RealtimeObservation;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.application.JourneyRequestMeasurement;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeQuery;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeUpdate;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeUpdates;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayEdge;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteEdgeEvidence;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendar;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitRoute;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitStopTime;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitTrip;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class JourneyRealtimeAdapterTest {

	private static final String REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
	private static final String ROUTE_BUNDLE_SHA = "a".repeat(64);
	private static final long GENERATION = 7;
	private static final Instant EFFECTIVE = Instant.parse("2026-06-30T23:50:00Z");
	private static final Instant NOW = Instant.parse("2026-06-30T23:50:30Z");
	private static final Duration FRESHNESS_TTL = Duration.ofSeconds(90);

	@Test
	void producesOneFreshSameHandleObservationAndCompleteDirectCandidate() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, directTimetable());
		var resolverCalls = new AtomicInteger();
		var capturedQueries = new AtomicReference<List<TimetableRealtimeQuery>>();
		var adapter = new JourneyRealtimeAdapter(queries -> {
			resolverCalls.incrementAndGet();
			capturedQueries.set(queries);
			return updates("realtime-1", NOW.minusSeconds(10));
		}, fixedClock(), FRESHNESS_TTL);

		RealtimeObservation observation = adapter.requireFresh(
			request(JourneyRequest.TimePolicy.REALTIME_REQUIRED, EFFECTIVE, "station-a", () -> false),
			snapshot(runtime),
			EFFECTIVE
		);

		assertThat(resolverCalls).hasValue(1);
		assertThat(capturedQueries.get()).singleElement().satisfies(query -> {
			assertThat(query.stationId()).isEqualTo("station-a");
			assertThat(query.lineId()).isEqualTo("line");
			assertThat(query.readyAt()).isEqualTo(EFFECTIVE);
			assertThat(query.departures()).extracting(departure -> departure.tripId())
				.containsExactly("trip", "trip-late");
		});
		assertThat(observation.identity()).isEqualTo("realtime-1");
		assertThat(observation.routeBundleSha256()).isEqualTo(ROUTE_BUNDLE_SHA);
		assertThat(observation.validUntil()).isEqualTo(NOW.plusSeconds(80));
		assertThat(observation.fresh()).isTrue();
		assertThat(observation.runtimeView()).isInstanceOf(RaptorRealtimeRuntimeView.class);
		assertThat(((RaptorRealtimeRuntimeView) observation.runtimeView()).routeRuntimeView()).isSameAs(runtime);

		JourneyCandidate candidate = new JourneyRaptorAdapter().plan(
			request(JourneyRequest.TimePolicy.REALTIME_REQUIRED, EFFECTIVE, "station-a", () -> false),
			snapshot(runtime),
			EFFECTIVE,
			observation,
			new JourneyRequestMeasurement(REQUEST_ID)
		).candidates().getFirst();
		assertThat(candidate.timeSource()).isEqualTo(JourneyCandidate.TimeSource.REALTIME);
		assertThat(candidate.realtimeDepartureTime()).isEqualTo(EFFECTIVE);
		assertThat(candidate.realtimeArrivalTime()).isEqualTo(Instant.parse("2026-07-01T00:12:00Z"));
		assertThat(candidate.legs()).filteredOn(JourneyCandidate.Ride.class::isInstance)
			.singleElement().isEqualTo(new JourneyCandidate.Ride(
				"line", "trip", "station-b", "station-a", "station-b",
				Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-01T00:10:00Z"),
				Instant.parse("2026-07-01T00:01:00Z"), Instant.parse("2026-07-01T00:11:00Z")));
	}

	@Test
	void rejectsTimetableUnknownAndNonExactOriginQueryCountsBeforeResolver() {
		var calls = new AtomicInteger();
		JourneyTimetableRealtimeResolver resolver = queries -> {
			calls.incrementAndGet();
			return updates("realtime-1", NOW.minusSeconds(10));
		};
		var adapter = new JourneyRealtimeAdapter(resolver, fixedClock(), FRESHNESS_TTL);
		var directRuntime = RaptorRouteBundleRuntimeView.compile(
			ROUTE_BUNDLE_SHA, GENERATION, directTimetable());

		assertThatThrownBy(() -> adapter.requireFresh(
			request(JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, EFFECTIVE, "station-a", () -> false),
			snapshot(directRuntime), EFFECTIVE))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("REALTIME_REQUIRED");

		JourneyRaptorRuntimeView unknown = new JourneyRaptorRuntimeView() {
			@Override
			public String routeBundleSha256() {
				return ROUTE_BUNDLE_SHA;
			}

			@Override
			public long generation() {
				return GENERATION;
			}
		};
		assertThatThrownBy(() -> adapter.requireFresh(
			request(JourneyRequest.TimePolicy.REALTIME_REQUIRED, EFFECTIVE, "station-a", () -> false),
			snapshot(unknown), EFFECTIVE))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("runtime view");

		var mismatchedShaRuntime = RaptorRouteBundleRuntimeView.compile(
			"b".repeat(64), GENERATION, directTimetable());
		assertThatThrownBy(() -> adapter.requireFresh(
			request(JourneyRequest.TimePolicy.REALTIME_REQUIRED, EFFECTIVE, "station-a", () -> false),
			snapshot(mismatchedShaRuntime), EFFECTIVE))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("does not match snapshot");

		var mismatchedGenerationRuntime = RaptorRouteBundleRuntimeView.compile(
			ROUTE_BUNDLE_SHA, GENERATION + 1, directTimetable());
		assertThatThrownBy(() -> adapter.requireFresh(
			request(JourneyRequest.TimePolicy.REALTIME_REQUIRED, EFFECTIVE, "station-a", () -> false),
			snapshot(mismatchedGenerationRuntime), EFFECTIVE))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("does not match snapshot");

		assertThatThrownBy(() -> adapter.requireFresh(
			request(JourneyRequest.TimePolicy.REALTIME_REQUIRED, EFFECTIVE, "station-missing", () -> false),
			snapshot(directRuntime), EFFECTIVE))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("exactly one");

		var multiLineRuntime = RaptorRouteBundleRuntimeView.compile(
			ROUTE_BUNDLE_SHA, GENERATION, multiLineTimetable());
		assertThatThrownBy(() -> adapter.requireFresh(
			request(JourneyRequest.TimePolicy.REALTIME_REQUIRED, EFFECTIVE, "station-a", () -> false),
			snapshot(multiLineRuntime), EFFECTIVE))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("exactly one");

		assertThat(calls).hasValue(0);
	}

	@Test
	void rejectsUnavailableNullAndIdentityMixedUpdatesAfterOneAttempt() {
		assertRejectedAfterOneCall(null, EFFECTIVE, "updates");
		assertRejectedAfterOneCall(
			TimetableRealtimeUpdates.unavailable("PROVIDER_UNAVAILABLE"), EFFECTIVE, "unavailable");
		assertRejectedAfterOneCall(new TimetableRealtimeUpdates(
			"realtime-1", true, List.of(new TimetableRealtimeUpdate(
				"trip", 60, 60, false, "realtime-2", NOW.minusSeconds(10))), null),
			EFFECTIVE, "identity");
		assertRejectedAfterOneCall(new TimetableRealtimeUpdates(
			"realtime-1+realtime-2", true, List.of(new TimetableRealtimeUpdate(
				"trip", 60, 60, false, "realtime-1+realtime-2", NOW.minusSeconds(10))), null),
			EFFECTIVE, "single");
	}

	@Test
	void rejectsFutureExpiredAndEffectiveInstantInvalidObservations() {
		assertRejectedAfterOneCall(updates("realtime-1", NOW.plusSeconds(1)), EFFECTIVE, "future");
		assertRejectedAfterOneCall(updates("realtime-1", NOW.minusSeconds(91)), EFFECTIVE, "expired");
		assertRejectedAfterOneCall(
			updates("realtime-1", NOW.minusSeconds(10)), NOW.plusSeconds(81), "effective instant");
	}

	@Test
	void cancellationAndResolverFailureNeverCreateASecondAttempt() {
		var calls = new AtomicInteger();
		var cancelledBefore = new JourneyRealtimeAdapter(queries -> {
			calls.incrementAndGet();
			return updates("realtime-1", NOW.minusSeconds(10));
		}, fixedClock(), FRESHNESS_TTL);
		assertThatThrownBy(() -> cancelledBefore.requireFresh(
			request(JourneyRequest.TimePolicy.REALTIME_REQUIRED, EFFECTIVE, "station-a", () -> true),
			snapshot(RaptorRouteBundleRuntimeView.compile(
				ROUTE_BUNDLE_SHA, GENERATION, directTimetable())), EFFECTIVE))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("cancelled");
		assertThat(calls).hasValue(0);

		var cancelled = new AtomicBoolean();
		var cancelledAfter = new JourneyRealtimeAdapter(queries -> {
			calls.incrementAndGet();
			cancelled.set(true);
			return updates("realtime-1", NOW.minusSeconds(10));
		}, fixedClock(), FRESHNESS_TTL);
		assertThatThrownBy(() -> cancelledAfter.requireFresh(
			request(JourneyRequest.TimePolicy.REALTIME_REQUIRED, EFFECTIVE, "station-a", cancelled::get),
			snapshot(RaptorRouteBundleRuntimeView.compile(
				ROUTE_BUNDLE_SHA, GENERATION, directTimetable())), EFFECTIVE))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("cancelled");
		assertThat(calls).hasValue(1);

		var failed = new JourneyRealtimeAdapter(queries -> {
			calls.incrementAndGet();
			throw new IllegalStateException("provider failed");
		}, fixedClock(), FRESHNESS_TTL);
		assertThatThrownBy(() -> failed.requireFresh(
			request(JourneyRequest.TimePolicy.REALTIME_REQUIRED, EFFECTIVE, "station-a", () -> false),
			snapshot(RaptorRouteBundleRuntimeView.compile(
				ROUTE_BUNDLE_SHA, GENERATION, directTimetable())), EFFECTIVE))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("provider failed");
		assertThat(calls).hasValue(2);
	}

	private static void assertRejectedAfterOneCall(
		TimetableRealtimeUpdates updates,
		Instant effectiveInstant,
		String message
	) {
		var calls = new AtomicInteger();
		var adapter = new JourneyRealtimeAdapter(queries -> {
			calls.incrementAndGet();
			return updates;
		}, fixedClock(), FRESHNESS_TTL);
		var runtime = RaptorRouteBundleRuntimeView.compile(
			ROUTE_BUNDLE_SHA, GENERATION, directTimetable());

		assertThatThrownBy(() -> adapter.requireFresh(
			request(JourneyRequest.TimePolicy.REALTIME_REQUIRED, effectiveInstant, "station-a", () -> false),
			snapshot(runtime), effectiveInstant))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining(message);
		assertThat(calls).hasValue(1);
	}

	private static Clock fixedClock() {
		return Clock.fixed(NOW, ZoneOffset.UTC);
	}

	private static TimetableRealtimeUpdates updates(String identity, Instant observedAt) {
		return new TimetableRealtimeUpdates(identity, true, List.of(
			new TimetableRealtimeUpdate("trip", 60, 60, false, identity, observedAt)), null);
	}

	private static JourneyRequest request(
		JourneyRequest.TimePolicy timePolicy,
		Instant effectiveInstant,
		String originStationId,
		java.util.function.BooleanSupplier cancellationSignal
	) {
		return new JourneyRequest(
			REQUEST_ID, originStationId, "station-b",
			new JourneyRequest.Departure.Scheduled(effectiveInstant), timePolicy,
			JourneyRequest.WalkingPace.STANDARD, JourneyRequest.MobilityProfile.STANDARD,
			JourneyRequest.ConstraintMode.NONE,
			0, 1, cancellationSignal);
	}

	private static ActiveJourneySnapshot snapshot(JourneyRaptorRuntimeView runtime) {
		return new ActiveJourneySnapshot(
			"snapshot-1", "bundle-1", ROUTE_BUNDLE_SHA, "timetable-1", "accessibility-1",
			GENERATION, runtime, NOW.plusSeconds(3_600), true,
			com.easysubway.journey.application.ActiveJourneySnapshotPort.ActiveServingEvidence.unobservable(),
			com.easysubway.journey.application.ActiveJourneySnapshotPort.SnapshotBoundaryReceipt.observed(0, 0));
	}

	private static RouteTimetable directTimetable() {
		var calendar = calendar();
		var route = new TransitRoute("route", "line", "L", "Line", "station-b", "Asia/Seoul");
		var trips = List.of(
			new TransitTrip("trip", "route", "daily", "춘천행", "down", "SUBWAY", "LOCAL", "1001", 0),
			new TransitTrip(
				"trip-late", "route", "daily", "station-b", "down", "SUBWAY", "LOCAL", "1002", 0));
		var stopTimes = List.of(
			new TransitStopTime("trip", 1, "station-a", "line", 32_400, 32_400, 0, 0),
			new TransitStopTime("trip", 2, "station-b", "line", 33_000, 33_000, 0, 0),
			new TransitStopTime("trip-late", 1, "station-a", "line", 36_000, 36_000, 0, 0),
			new TransitStopTime("trip-late", 2, "station-b", "line", 36_600, 36_600, 0, 0));
		return new RouteTimetable(
			List.of(calendar), List.of(), List.of(route), trips, stopTimes,
			List.of(), List.of(), null, verifiedAccess());
	}

	private static RouteTimetable multiLineTimetable() {
		var routes = List.of(
			new TransitRoute("route-a", "line-a", "A", "Line A", "station-b", "Asia/Seoul"),
			new TransitRoute("route-b", "line-b", "B", "Line B", "station-b", "Asia/Seoul"));
		var trips = List.of(
			new TransitTrip("trip", "route-a", "daily", "station-b", "down", "SUBWAY", "LOCAL", "1001", 0),
			new TransitTrip(
				"trip-second", "route-b", "daily", "station-b", "down", "SUBWAY", "LOCAL", "2001", 0));
		var stopTimes = List.of(
			new TransitStopTime("trip", 1, "station-a", "line-a", 32_400, 32_400, 0, 0),
			new TransitStopTime("trip", 2, "station-b", "line-a", 33_000, 33_000, 0, 0),
			new TransitStopTime("trip-second", 1, "station-a", "line-b", 32_500, 32_500, 0, 0),
			new TransitStopTime("trip-second", 2, "station-b", "line-b", 33_100, 33_100, 0, 0));
		return new RouteTimetable(
			List.of(calendar()), List.of(), routes, trips, stopTimes,
			List.of(), List.of(), null, LoadRouteTimetablePort.RouteAccessData.empty());
	}

	private static ServiceCalendar calendar() {
		return new ServiceCalendar(
			"daily", true, true, true, true, true, true, true,
			LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "Asia/Seoul");
	}

	private static LoadRouteTimetablePort.RouteAccessData verifiedAccess() {
		var edges = List.of(
			new PathwayEdge(
				"entry", "entrance", "platform-a", 120, 60, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"),
			new PathwayEdge(
				"exit", "platform-b", "outside", 60, 40, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"));
		var evidence = List.of(
			new RouteEdgeEvidence(
				"entry-evidence", "station-a", "line", "entry", "ENTRY",
				"OFFICIAL_SOURCE", "VERIFIED", true, null),
			new RouteEdgeEvidence(
				"exit-evidence", "station-b", "line", "exit", "EXIT",
				"OFFICIAL_SOURCE", "VERIFIED", true, null));
		return new LoadRouteTimetablePort.RouteAccessData(
			List.of(
				new PathwayNode("entrance", "station-a", null, "ENTRANCE"),
				new PathwayNode("platform-a", "station-a", "line", "PLATFORM"),
				new PathwayNode("platform-b", "station-b", "line", "PLATFORM"),
				new PathwayNode("outside", "station-b", null, "EXIT")),
			edges, List.of(), evidence);
	}
}
