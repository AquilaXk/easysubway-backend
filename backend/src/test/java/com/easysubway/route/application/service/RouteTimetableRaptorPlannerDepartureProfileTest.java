package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.journey.application.JourneyProfileRaptorPort;
import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.application.JourneyRequestMeasurement;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("#308 event-driven forward departure profile")
class RouteTimetableRaptorPlannerDepartureProfileTest {

	private static final String REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
	private static final String ROUTE_BUNDLE_SHA = "a".repeat(64);
	private static final long GENERATION = 1L;
	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 7, 1);
	private static final int ENTRY_SECONDS = 300;
	private static final int SENIOR_ENTRY_SECONDS = 405;
	private static final int SENIOR_SLACK_SECONDS = 90;
	private final RouteTimetableRaptorPlanner planner = new RouteTimetableRaptorPlanner();

	@Test
	@DisplayName("profiles scheduled, frequency, and 24-hour departures from ENTRY plus slack breakpoints")
	void profilesCanonicalEventsLatestFirstAndMatchesIndependentPointOracle() {
		var compiled = planner.compile(timetable());
		var query = new JourneyRaptorQuery(
			REQUEST_ID, "station-a", "station-b",
			new JourneyRaptorQuery.DepartBetween(instantAt(32_000), instantAt(87_000)),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.SLOW,
			JourneyRequest.MobilityProfile.SLOW,
			JourneyRequest.ConstraintMode.NONE,
			0, 1, () -> false);
		var profile = planner.departureProfile(
			query, compiled, RouteTimetableRaptorPlanner.RealtimeOverlay.empty());

		assertThat(profile)
			.extracting(RouteTimetableRaptorPlanner.JourneyDepartureProfilePoint::readyAtSeconds)
			.containsExactly(87_000 - SENIOR_ENTRY_SECONDS - SENIOR_SLACK_SECONDS,
				34_200 - SENIOR_ENTRY_SECONDS - SENIOR_SLACK_SECONDS,
				33_600 - SENIOR_ENTRY_SECONDS - SENIOR_SLACK_SECONDS,
				33_000 - SENIOR_ENTRY_SECONDS - SENIOR_SLACK_SECONDS);
		for (int index = 1; index < profile.size(); index += 1) {
			assertThat(profile.get(index).scanMetrics().expandedRoutes())
				.isGreaterThan(profile.get(index - 1).scanMetrics().expandedRoutes());
		}
		assertThat(profile).allSatisfy(point -> {
			var pointQuery = new JourneyRaptorQuery(
				REQUEST_ID, "station-a", "station-b",
				new JourneyRaptorQuery.DepartAt(instantAt(point.readyAtSeconds())),
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
				JourneyRequest.WalkingPace.SLOW,
				JourneyRequest.MobilityProfile.SLOW,
				JourneyRequest.ConstraintMode.NONE,
				0, 1, () -> false);
			assertThat(point.itineraries()).isEqualTo(planner.journeyItineraries(
				pointQuery, compiled, RouteTimetableRaptorPlanner.RealtimeOverlay.empty(),
				new JourneyRequestMeasurement(REQUEST_ID), REQUEST_ID, ROUTE_BUNDLE_SHA, GENERATION).itineraries());
		});
	}

	@Test
	void splitsCrossServiceDayWithoutMixingActiveCalendars() {
		var query = new JourneyRaptorQuery(
			REQUEST_ID, "station-a", "station-b",
			new JourneyRaptorQuery.DepartBetween(instantAt(96_000), instantAt(99_000)),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD,
			JourneyRequest.ConstraintMode.NONE,
			0, 1, () -> false);

		var profile = planner.departureProfile(
			query, planner.compile(crossCutoffTimetable()),
			RouteTimetableRaptorPlanner.RealtimeOverlay.empty());

		assertThat(profile)
			.extracting(point -> point.serviceDate() + ":" + point.readyAtSeconds())
			.containsExactly(
				SERVICE_DATE.plusDays(1) + ":11640",
				SERVICE_DATE + ":96240");
		assertThat(onlyRide(profile.get(0)).tripId()).isEqualTo("after-cutoff");
		assertThat(onlyRide(profile.get(1)).tripId()).isEqualTo("before-cutoff");
	}

	@Test
	@DisplayName("profile keeps non-dominated transfer paths sharing the final incoming line")
	void retainsWalkingAndConnectionSlackRepresentativeBeforePublicAlternativeSelection() {
		var query = new JourneyRaptorQuery(
			REQUEST_ID, "origin", "destination",
			new JourneyRaptorQuery.DepartBetween(instantAt(29_000), instantAt(29_001)),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD,
			JourneyRequest.ConstraintMode.NONE,
			1, 3, () -> false);

		var profile = planner.departureProfile(
			query, planner.compile(frontierCollisionTimetable()), RouteTimetableRaptorPlanner.RealtimeOverlay.empty());

		assertThat(profile).singleElement().satisfies(point -> {
			assertThat(point.readyAtSeconds()).isEqualTo(29_000);
			assertThat(point.itineraries())
				.extracting(itinerary -> itinerary.legs().stream()
					.filter(RouteTimetableRaptorPlanner.JourneyRideProjection.class::isInstance)
					.map(RouteTimetableRaptorPlanner.JourneyRideProjection.class::cast)
					.map(RouteTimetableRaptorPlanner.JourneyRideProjection::tripId)
					.toList())
				.containsExactly(
					List.of("origin-a", "shared-fast"),
					List.of("origin-b", "shared-safe"));
			assertThat(point.itineraries())
				.extracting(RouteTimetableRaptorPlanner.JourneyItinerary::metrics)
				.containsExactly(
					new JourneyProfileRaptorPort.ItineraryMetrics(
						1, 1_020, 1_010, 0,
						new JourneyProfileRaptorPort.MinimumTransferSeconds(0)),
					new JourneyProfileRaptorPort.ItineraryMetrics(
						1, 308, 120, 0,
						new JourneyProfileRaptorPort.MinimumTransferSeconds(332)));
		});
	}

	private static RouteTimetableRaptorPlanner.JourneyRideProjection onlyRide(
		RouteTimetableRaptorPlanner.JourneyDepartureProfilePoint point
	) {
		return point.itineraries().getFirst().legs().stream()
			.filter(RouteTimetableRaptorPlanner.JourneyRideProjection.class::isInstance)
			.map(RouteTimetableRaptorPlanner.JourneyRideProjection.class::cast)
			.findFirst()
			.orElseThrow();
	}

	private static Instant instantAt(int readyAtSeconds) {
		return SERVICE_DATE.atStartOfDay().plusSeconds(readyAtSeconds)
			.atOffset(ZoneOffset.ofHours(9)).toInstant();
	}

	private static RouteTimetable timetable() {
		var calendar = new LoadRouteTimetablePort.ServiceCalendar(
			"daily", true, true, true, true, true, true, true,
			SERVICE_DATE.minusDays(1), SERVICE_DATE.plusDays(1), "Asia/Seoul");
		var route = new LoadRouteTimetablePort.TransitRoute(
			"route", "line", "L", "Line", "Terminal", "Asia/Seoul");
		return new RouteTimetable(
			List.of(calendar),
			List.of(),
			List.of(route),
			List.of(
				trip("scheduled"), trip("frequency"), trip("overnight")),
			List.of(
				stop("scheduled", 1, "station-a", 33_000), stop("scheduled", 2, "station-b", 33_600),
				stop("frequency", 1, "station-a", 33_600), stop("frequency", 2, "station-b", 34_200),
				stop("overnight", 1, "station-a", 87_000), stop("overnight", 2, "station-b", 87_600)),
			List.of(new LoadRouteTimetablePort.TransitFrequency("frequency", 33_600, 34_800, 600, false)),
			List.of(),
			null,
			accessData());
	}

	private static RouteTimetable crossCutoffTimetable() {
		var route = new LoadRouteTimetablePort.TransitRoute(
			"route", "line", "L", "Line", "Terminal", "Asia/Seoul");
		return new RouteTimetable(
			List.of(
				calendar("day-before-cutoff", SERVICE_DATE),
				calendar("day-after-cutoff", SERVICE_DATE.plusDays(1))),
			List.of(),
			List.of(route),
			List.of(
				trip("before-cutoff", "day-before-cutoff"),
				trip("after-cutoff", "day-after-cutoff")),
			List.of(
				stop("before-cutoff", 1, "station-a", 96_600),
				stop("before-cutoff", 2, "station-b", 96_900),
				stop("after-cutoff", 1, "station-a", 12_000),
				stop("after-cutoff", 2, "station-b", 12_300)),
			List.of(),
			List.of(),
			null,
			accessData());
	}

	private static RouteTimetable frontierCollisionTimetable() {
		var calendar = new LoadRouteTimetablePort.ServiceCalendar(
			"daily", true, true, true, true, true, true, true,
			SERVICE_DATE, SERVICE_DATE, "Asia/Seoul");
		return new RouteTimetable(
			List.of(calendar),
			List.of(),
			List.of(
				route("route-origin-a", "line-a"),
				route("route-origin-b", "line-b"),
				route("route-shared-fast", "shared"),
				route("route-shared-safe", "shared")),
			List.of(
				trip("origin-a", "daily", "route-origin-a"),
				trip("origin-b", "daily", "route-origin-b"),
				trip("shared-fast", "daily", "route-shared-fast"),
				trip("shared-safe", "daily", "route-shared-safe")),
			List.of(
				stop("origin-a", 1, "origin", "line-a", 29_300),
				stop("origin-a", 2, "hub", "line-a", 29_300),
				stop("origin-b", 1, "origin", "line-b", 29_300),
				stop("origin-b", 2, "hub", "line-b", 29_600),
				stop("shared-fast", 1, "hub", "shared", 30_080),
				stop("shared-fast", 2, "destination", "shared", 30_300),
				stop("shared-safe", 1, "hub", "shared", 30_000),
				stop("shared-safe", 2, "destination", "shared", 30_400)),
			List.of(),
			List.of(),
			null,
			frontierCollisionAccessData());
	}

	private static LoadRouteTimetablePort.ServiceCalendar calendar(String id, LocalDate date) {
		return new LoadRouteTimetablePort.ServiceCalendar(
			id, true, true, true, true, true, true, true, date, date, "Asia/Seoul");
	}

	private static LoadRouteTimetablePort.TransitTrip trip(String id) {
		return trip(id, "daily");
	}

	private static LoadRouteTimetablePort.TransitTrip trip(String id, String serviceId) {
		return new LoadRouteTimetablePort.TransitTrip(
			id, "route", serviceId, "Terminal", "0", "LOCAL", 0);
	}

	private static LoadRouteTimetablePort.TransitTrip trip(String id, String serviceId, String routeId) {
		return new LoadRouteTimetablePort.TransitTrip(
			id, routeId, serviceId, "Terminal", "0", "LOCAL", 0);
	}

	private static LoadRouteTimetablePort.TransitStopTime stop(
		String tripId, int sequence, String stationId, int seconds
	) {
		return stop(tripId, sequence, stationId, "line", seconds);
	}

	private static LoadRouteTimetablePort.TransitStopTime stop(
		String tripId, int sequence, String stationId, String lineId, int seconds
	) {
		return new LoadRouteTimetablePort.TransitStopTime(
			tripId, sequence, stationId, lineId, seconds, seconds, 0, 0);
	}

	private static LoadRouteTimetablePort.RouteAccessData frontierCollisionAccessData() {
		return new LoadRouteTimetablePort.RouteAccessData(
			List.of(
				new LoadRouteTimetablePort.PathwayNode("entry-a-outside", "origin", null, "ENTRANCE"),
				new LoadRouteTimetablePort.PathwayNode("entry-a-platform", "origin", "line-a", "PLATFORM"),
				new LoadRouteTimetablePort.PathwayNode("entry-b-outside", "origin", null, "ENTRANCE"),
				new LoadRouteTimetablePort.PathwayNode("entry-b-platform", "origin", "line-b", "PLATFORM"),
				new LoadRouteTimetablePort.PathwayNode("transfer-a-from", "hub", "line-a", "PLATFORM"),
				new LoadRouteTimetablePort.PathwayNode("transfer-a-to", "hub", "shared", "PLATFORM"),
				new LoadRouteTimetablePort.PathwayNode("transfer-b-from", "hub", "line-b", "PLATFORM"),
				new LoadRouteTimetablePort.PathwayNode("transfer-b-to", "hub", "shared", "PLATFORM"),
				new LoadRouteTimetablePort.PathwayNode("exit-platform", "destination", "shared", "PLATFORM"),
				new LoadRouteTimetablePort.PathwayNode("exit-outside", "destination", null, "EXIT")),
			List.of(
				pathway("entry-a", "entry-a-outside", "entry-a-platform", 240, 100),
				pathway("entry-b", "entry-b-outside", "entry-b-platform", 240, 100),
				pathway("transfer-a", "transfer-a-from", "transfer-a-to", 720, 900),
				pathway("transfer-b", "transfer-b-from", "transfer-b-to", 8, 10),
				pathway("exit", "exit-platform", "exit-outside", 60, 10)),
			List.of(
				transfer("transfer-a-rule", "line-a", "transfer-a", 720),
				transfer("transfer-b-rule", "line-b", "transfer-b", 8)),
			List.of(
				evidence("entry-a-evidence", "origin", "line-a", "entry-a", "ENTRY"),
				evidence("entry-b-evidence", "origin", "line-b", "entry-b", "ENTRY"),
				evidence("transfer-a-evidence", "hub", "shared", "transfer-a", "TRANSFER"),
				evidence("transfer-b-evidence", "hub", "shared", "transfer-b", "TRANSFER"),
				evidence("exit-evidence", "destination", "shared", "exit", "EXIT")));
	}

	private static LoadRouteTimetablePort.PathwayEdge pathway(
		String id, String from, String to, int seconds, int distanceMeters
	) {
		return new LoadRouteTimetablePort.PathwayEdge(
			id, from, to, seconds, distanceMeters, false, false, 100,
			"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED");
	}

	private static LoadRouteTimetablePort.TransferRule transfer(
		String id, String fromLineId, String edgeId, int seconds
	) {
		return new LoadRouteTimetablePort.TransferRule(
			id, "hub", fromLineId, "hub", "shared", "IN_STATION", seconds,
			edgeId, edgeId, "VERIFIED");
	}

	private static LoadRouteTimetablePort.RouteAccessData accessData() {
		return new LoadRouteTimetablePort.RouteAccessData(
			List.of(
				new LoadRouteTimetablePort.PathwayNode("entry-from", "station-a", null, "ENTRANCE"),
				new LoadRouteTimetablePort.PathwayNode("entry-to", "station-a", "line", "PLATFORM"),
				new LoadRouteTimetablePort.PathwayNode("exit-from", "station-b", "line", "PLATFORM"),
				new LoadRouteTimetablePort.PathwayNode("exit-to", "station-b", null, "EXIT")),
			List.of(
				edge("entry", "entry-from", "entry-to", ENTRY_SECONDS),
				edge("exit", "exit-from", "exit-to", 120)),
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

	private static LoadRouteTimetablePort.RouteEdgeEvidence evidence(
		String id, String stationId, String edgeId, String edgeType
	) {
		return evidence(id, stationId, "line", edgeId, edgeType);
	}

	private static LoadRouteTimetablePort.RouteEdgeEvidence evidence(
		String id, String stationId, String lineId, String edgeId, String edgeType
	) {
		return new LoadRouteTimetablePort.RouteEdgeEvidence(
			id, stationId, lineId, edgeId, edgeType,
			"OFFICIAL_SOURCE", "VERIFIED", true, null);
	}
}
