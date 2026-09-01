package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.journey.application.ActiveJourneySnapshotPort;
import com.easysubway.journey.application.JourneyProfileRaptorPort;
import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class JourneyProfileRaptorAdapterTest {

	private static final String REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
	private static final String ROUTE_BUNDLE_SHA = "a".repeat(64);
	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 7, 1);
	private final JourneyProfileRaptorAdapter adapter = new JourneyProfileRaptorAdapter();

	@Test
	void dispatchesDepartureWindowAgainstTheCapturedRuntimeWithoutPointFallback() {
		var result = adapter.plan(query(new JourneyRaptorQuery.DepartBetween(instantAt(30_000), instantAt(37_000))),
			snapshot(), null);

		assertThat(result).isInstanceOfSatisfying(JourneyProfileRaptorPort.DepartureWindowPlan.class, plan -> {
			assertThat(plan.points()).singleElement().satisfies(point -> {
				assertThat(point.serviceDate()).isEqualTo(SERVICE_DATE);
				assertThat(point.itineraries()).singleElement().satisfies(itinerary -> {
					assertThat(itinerary.metrics()).isEqualTo(new JourneyProfileRaptorPort.ItineraryMetrics(
						0, 420, 100, 0, new JourneyProfileRaptorPort.NoTransfer()));
					assertThat(itinerary.legs()).anySatisfy(leg -> assertThat(leg)
						.isInstanceOfSatisfying(JourneyProfileRaptorPort.RideLeg.class,
							ride -> assertThat(ride.tripId()).isEqualTo("direct")));
					assertThat(itinerary.legs()).anySatisfy(leg -> assertThat(leg)
						.isInstanceOfSatisfying(JourneyProfileRaptorPort.AccessLeg.class, access -> {
							assertThat(access.verified()).isTrue();
							assertThat(access.distanceMeters()).isEqualTo(50);
							assertThat(access.verificationStatus()).isEqualTo("VERIFIED");
						}));
				});
			});
		});
	}

	@Test
	void dispatchesArriveByToTheNativeReversePrimitive() {
		var result = adapter.plan(query(new JourneyRaptorQuery.ArriveBy(instantAt(30_000), instantAt(37_000))),
			snapshot(), null);

		assertThat(result).isInstanceOfSatisfying(JourneyProfileRaptorPort.ArriveByPlan.class, plan ->
			assertThat(plan.result()).isInstanceOfSatisfying(JourneyProfileRaptorPort.ReversePlan.Found.class,
				found -> {
					assertThat(found.itinerary().metrics()).isEqualTo(new JourneyProfileRaptorPort.ItineraryMetrics(
						0, 420, 100, 0, new JourneyProfileRaptorPort.NoTransfer()));
					assertThat(found.itinerary().legs()).anySatisfy(leg -> assertThat(leg)
						.isInstanceOfSatisfying(JourneyProfileRaptorPort.RideLeg.class,
							ride -> assertThat(ride.tripId()).isEqualTo("direct")));
				}));
	}

	@Test
	void derivesTransferSlackAndAccessBurdenFromThePlannerNativeLegs() {
		Instant base = instantAt(0);
		var legs = List.<RouteTimetableRaptorPlanner.JourneyLegProjection>of(
			access(RouteTimetableRaptorPlanner.JourneyAccessKind.ENTRY, 100, 20, false),
			ride("first", base.plusSeconds(500), base.plusSeconds(1_000)),
			access(RouteTimetableRaptorPlanner.JourneyAccessKind.TRANSFER, 180, 30, true),
			ride("second", base.plusSeconds(1_300), base.plusSeconds(1_600)),
			access(RouteTimetableRaptorPlanner.JourneyAccessKind.EXIT, 50, 10, false));

		assertThat(RouteTimetableRaptorPlanner.itineraryMetrics(legs, 60))
			.isEqualTo(new JourneyProfileRaptorPort.ItineraryMetrics(
				1, 330, 60, 1, new JourneyProfileRaptorPort.MinimumTransferSeconds(60)));
	}

	@Test
	void dispatchesLastConnectionToTheNativeTerminalEventPrimitive() {
		var result = adapter.plan(query(new JourneyRaptorQuery.LastConnection(SERVICE_DATE)), snapshot(), null);

		assertThat(result).isInstanceOfSatisfying(JourneyProfileRaptorPort.LastConnectionPlan.class, plan ->
			assertThat(plan.result()).isInstanceOf(JourneyProfileRaptorPort.ReversePlan.Found.class));
	}

	@Test
	void rejectsRealtimeProfileInsteadOfUsingTimetableAsFallback() {
		var query = new JourneyRaptorQuery(
			REQUEST_ID, "station-a", "station-b",
			new JourneyRaptorQuery.LastConnection(SERVICE_DATE), JourneyRequest.TimePolicy.REALTIME_REQUIRED,
			JourneyRequest.WalkingPace.STANDARD, JourneyRequest.MobilityProfile.STANDARD,
			JourneyRequest.ConstraintMode.NONE, 0, 1, () -> false);

		assertThatThrownBy(() -> adapter.plan(query, snapshot(), null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("TIMETABLE_REQUIRED");
	}

	private static JourneyRaptorQuery query(JourneyRaptorQuery.TemporalQuery temporalQuery) {
		return new JourneyRaptorQuery(
			REQUEST_ID, "station-a", "station-b", temporalQuery, JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.STANDARD, JourneyRequest.MobilityProfile.STANDARD,
			JourneyRequest.ConstraintMode.NONE, 0, 1, () -> false);
	}

	private static ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, 1, timetable());
		return new ActiveJourneySnapshotPort.ActiveJourneySnapshot(
			"snapshot", "bundle", ROUTE_BUNDLE_SHA, "timetable", "accessibility", 1, runtime,
			Instant.parse("2026-07-03T00:00:00Z"), true,
			ActiveJourneySnapshotPort.ActiveServingEvidence.unobservable(),
			ActiveJourneySnapshotPort.SnapshotBoundaryReceipt.observed(0, 0));
	}

	private static Instant instantAt(int seconds) {
		return SERVICE_DATE.atStartOfDay().plusSeconds(seconds).atOffset(ZoneOffset.ofHours(9)).toInstant();
	}

	private static RouteTimetable timetable() {
		var calendar = new LoadRouteTimetablePort.ServiceCalendar(
			"daily", true, true, true, true, true, true, true,
			SERVICE_DATE, SERVICE_DATE, "Asia/Seoul");
		var route = new LoadRouteTimetablePort.TransitRoute(
			"route", "line", "L", "Line", "Terminal", "Asia/Seoul");
		var trip = new LoadRouteTimetablePort.TransitTrip("direct", "route", "daily", "Terminal", "0", "LOCAL", 0);
		return new RouteTimetable(
			List.of(calendar), List.of(), List.of(route), List.of(trip),
			List.of(stop("direct", 1, "station-a", 36_000), stop("direct", 2, "station-b", 36_600)),
			List.of(), List.of(), null, accessData());
	}

	private static LoadRouteTimetablePort.TransitStopTime stop(String tripId, int sequence, String station, int seconds) {
		return new LoadRouteTimetablePort.TransitStopTime(
			tripId, sequence, station, "line", seconds, seconds, 0, 0);
	}

	private static LoadRouteTimetablePort.RouteAccessData accessData() {
		return new LoadRouteTimetablePort.RouteAccessData(
			List.of(
				new LoadRouteTimetablePort.PathwayNode("entry-from", "station-a", null, "ENTRANCE"),
				new LoadRouteTimetablePort.PathwayNode("entry-to", "station-a", "line", "PLATFORM"),
				new LoadRouteTimetablePort.PathwayNode("exit-from", "station-b", "line", "PLATFORM"),
				new LoadRouteTimetablePort.PathwayNode("exit-to", "station-b", null, "EXIT")),
			List.of(edge("entry", "entry-from", "entry-to", 300), edge("exit", "exit-from", "exit-to", 120)),
			List.of(),
			List.of(
				evidence("entry-evidence", "station-a", "entry", "ENTRY"),
				evidence("exit-evidence", "station-b", "exit", "EXIT")));
	}

	private static LoadRouteTimetablePort.PathwayEdge edge(String id, String from, String to, int seconds) {
		return new LoadRouteTimetablePort.PathwayEdge(
			id, from, to, seconds, 50, false, false, 100,
			"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED");
	}

	private static RouteTimetableRaptorPlanner.JourneyAccessProjection access(
		RouteTimetableRaptorPlanner.JourneyAccessKind kind, int duration, int distance, boolean stairs
	) {
		return new RouteTimetableRaptorPlanner.JourneyAccessProjection(
			kind, "station", "station", duration, distance, stairs, true, "VERIFIED");
	}

	private static RouteTimetableRaptorPlanner.JourneyRideProjection ride(
		String tripId, Instant departure, Instant arrival
	) {
		return new RouteTimetableRaptorPlanner.JourneyRideProjection(
			"line", tripId, "terminal", "station", "station",
			departure, arrival, null, null);
	}

	private static LoadRouteTimetablePort.RouteEdgeEvidence evidence(
		String id, String stationId, String edgeId, String edgeType
	) {
		return new LoadRouteTimetablePort.RouteEdgeEvidence(
			id, stationId, "line", edgeId, edgeType, "OFFICIAL_SOURCE", "VERIFIED", true, null);
	}
}
