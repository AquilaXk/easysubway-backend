package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.journey.application.ActiveJourneySnapshotPort;
import com.easysubway.journey.application.JourneyCandidate;
import com.easysubway.journey.application.JourneyRaptorPort;
import com.easysubway.journey.application.JourneyRaptorRuntimeView;
import com.easysubway.journey.application.JourneyRealtimePort;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeUpdate;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeUpdates;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendar;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitRoute;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitStopTime;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitTrip;
import com.easysubway.route.domain.ConstraintMode;
import com.easysubway.route.domain.ProfileWalkTimeCalculator.MobilityPreset;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class JourneyRaptorAdapterTest {

	private static final String REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
	private static final String ROUTE_BUNDLE_SHA = "a".repeat(64);
	private static final long GENERATION = 7;
	private static final Instant EFFECTIVE = Instant.parse("2026-06-30T23:50:00Z");
	private static final Instant VALID_UNTIL = Instant.parse("2026-07-01T02:00:00Z");

	@Test
	void plansOneTimetableCandidateFromTheCapturedCompiledRuntimeOnly() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, timetable(true));
		var adapter = new JourneyRaptorAdapter();

		JourneyRaptorPort.PlanResult result = adapter.plan(
			request(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED),
			snapshot(runtime),
			EFFECTIVE,
			null
		);

		assertThat(result.queryId()).isEqualTo(REQUEST_ID);
		assertThat(result.candidates()).singleElement().satisfies(candidate -> {
			assertThat(candidate.journeyId()).matches("[a-f0-9]{64}");
			assertThat(candidate.plannedDepartureTime()).isEqualTo(EFFECTIVE);
			assertThat(candidate.plannedArrivalTime()).isEqualTo(Instant.parse("2026-07-01T00:11:00Z"));
			assertThat(candidate.realtimeDepartureTime()).isNull();
			assertThat(candidate.realtimeArrivalTime()).isNull();
			assertThat(candidate.durationSeconds()).isEqualTo(1_260);
			assertThat(candidate.transferCount()).isZero();
			assertThat(candidate.walkingDistanceMeters()).isEqualTo(100);
			assertThat(candidate.timeSource()).isEqualTo(JourneyCandidate.TimeSource.TIMETABLE);
			assertThat(candidate.accessibility().stairFree()).isTrue();
			assertThat(candidate.accessibility().reasonCodes()).containsExactly("ACCESSIBILITY_VERIFIED");
			assertThat(candidate.legs()).hasSize(3);
			assertThat(candidate.legs().get(0)).isEqualTo(new JourneyCandidate.Entry("station-a", 120));
			assertThat(candidate.legs().get(1)).isEqualTo(new JourneyCandidate.Ride(
				"line", "trip", "station-b", "station-a", "station-b",
				Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-01T00:10:00Z"), null, null));
			assertThat(candidate.legs().get(2)).isEqualTo(new JourneyCandidate.Exit("station-b", 60));
		});
	}

	@Test
	void preservesPlannedAndCompleteRealtimePairsFromTheSameRuntime() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, timetable(true));
		var realtimeRuntime = RaptorRealtimeRuntimeView.compile(
			"realtime-1",
			runtime,
			new TimetableRealtimeUpdates("overlay-v1", true, List.of(
				new TimetableRealtimeUpdate(
					"trip", 60, 60, false, "realtime-1", Instant.parse("2026-06-30T23:49:30Z"))
			), null)
		);
		var observation = new JourneyRealtimePort.RealtimeObservation(
			"realtime-1", ROUTE_BUNDLE_SHA, realtimeRuntime, VALID_UNTIL, true);

		var candidate = new JourneyRaptorAdapter().plan(
			request(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE,
				JourneyRequest.TimePolicy.REALTIME_REQUIRED),
			snapshot(runtime), EFFECTIVE, observation).candidates().getFirst();

		assertThat(candidate.plannedDepartureTime()).isEqualTo(EFFECTIVE);
		assertThat(candidate.plannedArrivalTime()).isEqualTo(Instant.parse("2026-07-01T00:11:00Z"));
		assertThat(candidate.realtimeDepartureTime()).isEqualTo(EFFECTIVE);
		assertThat(candidate.realtimeArrivalTime()).isEqualTo(Instant.parse("2026-07-01T00:12:00Z"));
		assertThat(candidate.timeSource()).isEqualTo(JourneyCandidate.TimeSource.REALTIME);
		assertThat(candidate.legs()).filteredOn(JourneyCandidate.Ride.class::isInstance)
			.singleElement().isEqualTo(new JourneyCandidate.Ride(
				"line", "trip", "station-b", "station-a", "station-b",
				Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-01T00:10:00Z"),
				Instant.parse("2026-07-01T00:01:00Z"), Instant.parse("2026-07-01T00:11:00Z")));
	}

	@Test
	void rejectsSparseRealtimeThatDoesNotCoverTheSelectedRide() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, timetable(true));
		var realtimeRuntime = RaptorRealtimeRuntimeView.compile(
			"realtime-1",
			runtime,
			new TimetableRealtimeUpdates("overlay-v1", true, List.of(
				new TimetableRealtimeUpdate(
					"trip-late", 60, 60, false, "realtime-1", Instant.parse("2026-06-30T23:49:30Z"))
			), null)
		);
		var observation = new JourneyRealtimePort.RealtimeObservation(
			"realtime-1", ROUTE_BUNDLE_SHA, realtimeRuntime, VALID_UNTIL, true);

		assertThatThrownBy(() -> new JourneyRaptorAdapter().plan(
			request(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE,
				JourneyRequest.TimePolicy.REALTIME_REQUIRED),
			snapshot(runtime), EFFECTIVE, observation))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("incomplete");
	}

	@Test
	void mapsAllJourneyProfilesAndConstraintsWithoutChangingTheWireProfile() {
		assertCommand(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE,
			MobilityType.LUGGAGE, MobilityPreset.STANDARD, ConstraintMode.ALLOW_WITH_WARNINGS);
		assertCommand(JourneyRequest.MobilityProfile.SLOW, JourneyRequest.ConstraintMode.NONE,
			MobilityType.SENIOR, MobilityPreset.SLOW, ConstraintMode.ALLOW_WITH_WARNINGS);
		assertCommand(JourneyRequest.MobilityProfile.NO_STAIRS, JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE,
			MobilityType.LUGGAGE, MobilityPreset.NO_STAIRS, ConstraintMode.STRICT_STEP_FREE);
		assertCommand(JourneyRequest.MobilityProfile.STEP_FREE, JourneyRequest.ConstraintMode.NONE,
			MobilityType.WHEELCHAIR, MobilityPreset.STEP_FREE, ConstraintMode.PREFER_STEP_FREE);
		assertCommand(JourneyRequest.MobilityProfile.STEP_FREE, JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE,
			MobilityType.WHEELCHAIR, MobilityPreset.STEP_FREE, ConstraintMode.STRICT_STEP_FREE);
	}

	@Test
	void rejectsUnknownAndGenerationMixedRuntimeHandlesBeforePlanning() {
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
		var unknownSnapshot = new ActiveJourneySnapshotPort.ActiveJourneySnapshot(
			"snapshot-1", "bundle-1", ROUTE_BUNDLE_SHA, "timetable-1", "accessibility-1",
			GENERATION, unknown, VALID_UNTIL, true);

		assertThatThrownBy(() -> new JourneyRaptorAdapter().plan(
			request(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED), unknownSnapshot, EFFECTIVE, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("runtime view");

		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, timetable(true));
		assertThatThrownBy(() -> new ActiveJourneySnapshotPort.ActiveJourneySnapshot(
			"snapshot-1", "bundle-1", ROUTE_BUNDLE_SHA, "timetable-1", "accessibility-1",
			GENERATION + 1, runtime, VALID_UNTIL, true))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("generation");
	}

	@Test
	void returnsNoCandidateForUnverifiedAccessInsteadOfPublishingBestEffort() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, timetable(false));
		assertThat(new JourneyRaptorAdapter().plan(
			request(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED),
			snapshot(runtime), EFFECTIVE, null).candidates()).isEmpty();
	}

	private static void assertCommand(
		JourneyRequest.MobilityProfile profile,
		JourneyRequest.ConstraintMode journeyConstraint,
		MobilityType mobilityType,
		MobilityPreset mobilityPreset,
		ConstraintMode routeConstraint
	) {
		var command = JourneyRaptorAdapter.toCommand(
			request(profile, journeyConstraint, JourneyRequest.TimePolicy.TIMETABLE_REQUIRED), EFFECTIVE);
		assertThat(command.mobilityType()).isEqualTo(mobilityType);
		assertThat(command.mobilityPreset()).isEqualTo(mobilityPreset);
		assertThat(command.constraintMode()).isEqualTo(routeConstraint);
	}

	private static JourneyRequest request(
		JourneyRequest.MobilityProfile profile,
		JourneyRequest.ConstraintMode constraint,
		JourneyRequest.TimePolicy timePolicy
	) {
		return new JourneyRequest(
			REQUEST_ID, "station-a", "station-b", new JourneyRequest.Departure.Scheduled(EFFECTIVE),
			timePolicy, profile, constraint, 0, 1, () -> false);
	}

	private static ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot(
		RaptorRouteBundleRuntimeView runtime
	) {
		return new ActiveJourneySnapshotPort.ActiveJourneySnapshot(
			"snapshot-1", "bundle-1", ROUTE_BUNDLE_SHA, "timetable-1", "accessibility-1",
			GENERATION, runtime, VALID_UNTIL, true);
	}

	private static RouteTimetable timetable(boolean verifiedAccess) {
		var calendar = new ServiceCalendar(
			"daily", true, true, true, true, true, true, true,
			LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "Asia/Seoul");
		var route = new TransitRoute("route", "line", "L", "Line", "station-b", "Asia/Seoul");
		var trip = new TransitTrip(
			"trip", "route", "daily", "station-b", "down", "SUBWAY", "LOCAL", "1001", 0);
		var lateTrip = new TransitTrip(
			"trip-late", "route", "daily", "station-b", "down", "SUBWAY", "LOCAL", "1002", 0);
		var stopTimes = List.of(
			new TransitStopTime("trip", 1, "station-a", "line", 32_400, 32_400, 0, 0),
			new TransitStopTime("trip", 2, "station-b", "line", 33_000, 33_000, 0, 0),
			new TransitStopTime("trip-late", 1, "station-a", "line", 36_000, 36_000, 0, 0),
			new TransitStopTime("trip-late", 2, "station-b", "line", 36_600, 36_600, 0, 0));
		return new RouteTimetable(
			List.of(calendar), List.of(), List.of(route), List.of(trip, lateTrip), stopTimes, List.of(), List.of(), null,
			verifiedAccess ? verifiedAccess() : LoadRouteTimetablePort.RouteAccessData.empty());
	}

	private static LoadRouteTimetablePort.RouteAccessData verifiedAccess() {
		var edges = List.of(
			new LoadRouteTimetablePort.PathwayEdge(
				"entry", "entrance", "platform-a", 120, 60, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"),
			new LoadRouteTimetablePort.PathwayEdge(
				"exit", "platform-b", "outside", 60, 40, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"));
		var evidence = List.of(
			new LoadRouteTimetablePort.RouteEdgeEvidence(
				"entry-evidence", "station-a", "line", "entry", "ENTRY",
				"OFFICIAL_SOURCE", "VERIFIED", true, null),
			new LoadRouteTimetablePort.RouteEdgeEvidence(
				"exit-evidence", "station-b", "line", "exit", "EXIT",
				"OFFICIAL_SOURCE", "VERIFIED", true, null));
		return new LoadRouteTimetablePort.RouteAccessData(
			List.of(
				new LoadRouteTimetablePort.PathwayNode("entrance", "station-a", null, "ENTRANCE"),
				new LoadRouteTimetablePort.PathwayNode("platform-a", "station-a", "line", "PLATFORM"),
				new LoadRouteTimetablePort.PathwayNode("platform-b", "station-b", "line", "PLATFORM"),
				new LoadRouteTimetablePort.PathwayNode("outside", "station-b", null, "EXIT")),
			edges, List.of(), evidence);
	}
}
