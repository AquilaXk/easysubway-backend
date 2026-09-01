package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
	void rejectsCrossServiceDayUntilTheProfileSplitterOwnsBothDays() {
		var query = new JourneyRaptorQuery(
			REQUEST_ID, "station-a", "station-b",
			new JourneyRaptorQuery.DepartBetween(instantAt(96_000), instantAt(97_200)),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD,
			JourneyRequest.ConstraintMode.NONE,
			0, 1, () -> false);

		assertThatThrownBy(() -> planner.departureProfile(
			query, planner.compile(timetable()), RouteTimetableRaptorPlanner.RealtimeOverlay.empty()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("one service day");
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

	private static LoadRouteTimetablePort.TransitTrip trip(String id) {
		return new LoadRouteTimetablePort.TransitTrip(id, "route", "daily", "Terminal", "0", "LOCAL", 0);
	}

	private static LoadRouteTimetablePort.TransitStopTime stop(
		String tripId, int sequence, String stationId, int seconds
	) {
		return new LoadRouteTimetablePort.TransitStopTime(
			tripId, sequence, stationId, "line", seconds, seconds, 0, 0);
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
		return new LoadRouteTimetablePort.RouteEdgeEvidence(
			id, stationId, "line", edgeId, edgeType,
			"OFFICIAL_SOURCE", "VERIFIED", true, null);
	}
}
