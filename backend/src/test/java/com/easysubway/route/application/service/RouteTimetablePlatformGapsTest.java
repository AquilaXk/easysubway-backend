package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.journey.application.JourneyCandidate;
import com.easysubway.journey.application.JourneyProfileResourcePolicy;
import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.GapGrade;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.HeightDiffGrade;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayEdge;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.PlatformGap;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.PlatformGapKey;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteAccessData;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteEdgeEvidence;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendar;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitFrequency;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitRoute;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitStopTime;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitTrip;
import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.JourneyItinerary;
import com.easysubway.route.domain.BoardingSlackPolicy;
import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.domain.ProfileWalkTimeCalculator;
import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.JourneyRideProjection;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("#435 Journey V3 승하차 승강장 연단 간격 테스트")
class RouteTimetablePlatformGapsTest {

	private static final ZoneOffset KST = ZoneOffset.ofHours(9);
	private static final LocalDate DATE = LocalDate.of(2026, 9, 1);
	private static final String STATION_A = "station-a";
	private static final String STATION_B = "station-b";
	private static final String LINE_1 = "line-1";

	private final RouteTimetableRaptorPlanner planner = new RouteTimetableRaptorPlanner();

	@Test
	@DisplayName("(1) up trip 구간은 UP 행만 실린다")
	void upTripContainsOnlyUpGaps() {
		var upBoarding = List.of(
			new PlatformGap("본선 1-1", 1, 1, GapGrade.WIDE, HeightDiffGrade.HIGH, true),
			new PlatformGap("본선 1-2", 1, 2, GapGrade.NARROW, HeightDiffGrade.LOW, false));
		var upAlighting = List.of(
			new PlatformGap("본선 3-1", 3, 1, GapGrade.NORMAL, HeightDiffGrade.NORMAL, false));
		var gaps = Map.of(
			new PlatformGapKey(STATION_A, LINE_1, "UP"), upBoarding,
			new PlatformGapKey(STATION_A, LINE_1, "DOWN"),
			List.of(new PlatformGap("DOWN 2-2", 2, 2, GapGrade.WIDE, HeightDiffGrade.HIGH, true)),
			new PlatformGapKey(STATION_B, LINE_1, "UP"), upAlighting);

		JourneyRideProjection ride = firstRide("up", gaps);

		assertThat(ride.boardingPlatformGaps()).containsExactlyElementsOf(upBoarding);
		assertThat(ride.alightingPlatformGaps()).containsExactlyElementsOf(upAlighting);
	}

	@Test
	@DisplayName("down trip 구간은 DOWN 행만 실린다")
	void downTripContainsOnlyDownGaps() {
		var down = List.of(new PlatformGap("DOWN 2-2", 2, 2, GapGrade.WIDE, HeightDiffGrade.HIGH, true));
		var gaps = Map.of(
			new PlatformGapKey(STATION_A, LINE_1, "UP"),
			List.of(new PlatformGap("본선 1-1", 1, 1, GapGrade.NARROW, HeightDiffGrade.LOW, false)),
			new PlatformGapKey(STATION_A, LINE_1, "DOWN"), down);

		JourneyRideProjection ride = firstRide("down", gaps);

		assertThat(ride.boardingPlatformGaps()).containsExactlyElementsOf(down);
		assertThat(ride.alightingPlatformGaps()).isEmpty();
	}

	@Test
	@DisplayName("(2) increasing/decreasing/null trip은 방향 근거가 없어 빈 배열이다")
	void nonOfficialDirectionTripHasEmptyGaps() {
		var gaps = Map.of(
			new PlatformGapKey(STATION_A, LINE_1, "UP"),
			List.of(new PlatformGap("1-1", 1, 1, GapGrade.WIDE, HeightDiffGrade.HIGH, false)),
			new PlatformGapKey(STATION_A, LINE_1, "DOWN"),
			List.of(new PlatformGap("1-1", 1, 1, GapGrade.WIDE, HeightDiffGrade.HIGH, false)),
			new PlatformGapKey(STATION_B, LINE_1, "UP"),
			List.of(new PlatformGap("1-1", 1, 1, GapGrade.WIDE, HeightDiffGrade.HIGH, false)));

		for (String direction : new String[] {"increasing", "decreasing", "0", null}) {
			JourneyRideProjection ride = firstRide(direction, gaps);
			assertThat(ride.boardingPlatformGaps()).as("direction=%s", direction).isEmpty();
			assertThat(ride.alightingPlatformGaps()).as("direction=%s", direction).isEmpty();
		}
	}

	@Test
	@DisplayName("(4) 연단 간격 자료가 없는 번들은 빈 배열이고 예외가 없다")
	void missingGapsYieldEmptyLists() {
		JourneyRideProjection ride = firstRide("up", Map.of());

		assertThat(ride.boardingPlatformGaps()).isEmpty();
		assertThat(ride.alightingPlatformGaps()).isEmpty();
	}

	@Test
	@DisplayName("역방향(arriveBy) 투영에도 up trip의 UP 행만 실린다")
	void reverseProjectionCarriesDirectionalGaps() {
		var up = List.of(new PlatformGap("본선 1-1", 1, 1, GapGrade.WIDE, HeightDiffGrade.HIGH, true));
		var gaps = Map.of(
			new PlatformGapKey(STATION_A, LINE_1, "UP"), up,
			new PlatformGapKey(STATION_A, LINE_1, "DOWN"),
			List.of(new PlatformGap("DOWN 9-9", null, null, GapGrade.NARROW, HeightDiffGrade.LOW, false)));
		var compiled = planner.compile(createTimetable("up", gaps, false));

		var result = new ReverseTimetableRaptorPlanner().arriveBy(
			new ReverseTimetableRaptorPlanner.Query(
				STATION_A, STATION_B, DATE, 0, 40_000, 1,
				RouteTimetableRaptorPlanner.profileBit(
					MobilityType.WHEELCHAIR, com.easysubway.route.domain.ConstraintMode.ALLOW_WITH_WARNINGS),
				BoardingSlackPolicy.secondsFor(MobilityType.WHEELCHAIR),
				ProfileWalkTimeCalculator.MobilityPreset.SLOW, 3_600, false, () -> false),
			compiled, compiled.activeServiceDay(DATE), RouteTimetableRaptorPlanner.RealtimeOverlay.empty(),
			new JourneyProfileResourcePolicy.ProfilePlanningLimits(100_000L, 32, 32, 32));

		assertThat(result.itineraries()).isNotEmpty();
		JourneyRideProjection ride = findFirstRide(result.itineraries().getFirst());
		assertThat(ride.boardingPlatformGaps()).containsExactlyElementsOf(up);
		assertThat(ride.alightingPlatformGaps()).isEmpty();
	}

	@Test
	@DisplayName("Ride와 RideProjection은 null 연단 간격을 빈 목록으로 다룬다")
	void nullGapsBecomeEmpty() {
		var ride = new JourneyCandidate.Ride(
			LINE_1, "trip-1", "dir", STATION_A, STATION_B, "LOCAL",
			DATE.atTime(10, 0).toInstant(KST), DATE.atTime(10, 10).toInstant(KST),
			null, null,
			List.of(
				new JourneyCandidate.Stop(STATION_A, null, DATE.atTime(10, 0).toInstant(KST), null, null),
				new JourneyCandidate.Stop(STATION_B, DATE.atTime(10, 10).toInstant(KST), null, null, null)),
			null, null, null);
		assertThat(ride.boardingPlatformGaps()).isEmpty();
		assertThat(ride.alightingPlatformGaps()).isEmpty();

		var projection = new JourneyRideProjection(
			LINE_1, "trip-1", "dir", STATION_A, STATION_B, "LOCAL",
			DATE.atTime(10, 0).toInstant(KST), DATE.atTime(10, 10).toInstant(KST),
			null, null, List.of(), List.of(), null, null);
		assertThat(projection.boardingPlatformGaps()).isEmpty();
		assertThat(projection.alightingPlatformGaps()).isEmpty();

		assertThat(RouteAccessData.empty().platformGaps()).isEmpty();
		assertThat(new RouteAccessData(List.of(), List.of(), List.of(), List.of(), List.of(), null).platformGaps())
			.isEmpty();
	}

	private JourneyRideProjection firstRide(String directionId, Map<PlatformGapKey, List<PlatformGap>> gaps) {
		var query = query(STATION_A, STATION_B, OffsetDateTime.of(2026, 9, 1, 10, 0, 0, 0, KST));
		var itineraries = planner.journeyItineraries(query, createTimetable(directionId, gaps)).itineraries();
		assertThat(itineraries).isNotEmpty();
		return findFirstRide(itineraries.getFirst());
	}

	private static JourneyRideProjection findFirstRide(JourneyItinerary itinerary) {
		return itinerary.legs().stream()
			.filter(JourneyRideProjection.class::isInstance)
			.map(JourneyRideProjection.class::cast)
			.findFirst()
			.orElseThrow();
	}

	private static JourneyRaptorQuery query(String origin, String destination, OffsetDateTime departure) {
		return new JourneyRaptorQuery(
			"01ARZ3NDEKTSV4RRFFQ69G5FAV",
			origin,
			destination,
			new JourneyRaptorQuery.DepartAt(departure.toInstant()),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.SLOW,
			JourneyRequest.MobilityProfile.SLOW,
			JourneyRequest.ConstraintMode.NONE,
			0,
			3,
			() -> false
		);
	}

	private static RouteTimetable createTimetable(String directionId, Map<PlatformGapKey, List<PlatformGap>> platformGaps) {
		return createTimetable(directionId, platformGaps, true);
	}

	private static RouteTimetable createTimetable(
		String directionId, Map<PlatformGapKey, List<PlatformGap>> platformGaps, boolean withFrequency) {
		var calendar = new ServiceCalendar("weekday", true, true, true, true, true, false, false,
			DATE.minusDays(1), DATE.plusDays(30), "Asia/Seoul");
		var route = new TransitRoute("route-1", LINE_1, "1", "Line 1", "station-b", "Asia/Seoul");
		var trip = new TransitTrip("trip-1", "route-1", "weekday", "station-b", directionId, "LOCAL", 0);
		var stopTimes = List.of(
			new TransitStopTime("trip-1", 1, STATION_A, LINE_1, 36000, 36000, 0, 0),
			new TransitStopTime("trip-1", 2, STATION_B, LINE_1, 36600, 36600, 0, 0)
		);
		var frequency = new TransitFrequency("trip-1", 18000, 86400, 300, false);
		var accessData = defaultAccess(stopTimes, platformGaps);

		return new RouteTimetable(
			List.of(calendar),
			List.of(),
			List.of(route),
			List.of(trip),
			stopTimes,
			withFrequency ? List.of(frequency) : List.of(),
			List.of(),
			DATE.plusDays(30),
			accessData
		);
	}

	private static RouteAccessData defaultAccess(
		List<TransitStopTime> stopTimes, Map<PlatformGapKey, List<PlatformGap>> platformGaps) {
		var nodes = new ArrayList<PathwayNode>();
		var edges = new ArrayList<PathwayEdge>();
		var evidence = new ArrayList<RouteEdgeEvidence>();

		Set<String> stations = new LinkedHashSet<>();
		for (var stop : stopTimes) {
			stations.add(stop.stationId());
		}

		for (var stationId : stations) {
			var entranceNodeId = "entrance-" + stationId;
			var exitNodeId = "exit-" + stationId;
			var platformNodeId = "platform-" + stationId + "-" + LINE_1;

			nodes.add(new PathwayNode(entranceNodeId, stationId, null, "ENTRANCE"));
			nodes.add(new PathwayNode(exitNodeId, stationId, null, "EXIT"));
			nodes.add(new PathwayNode(platformNodeId, stationId, LINE_1, "PLATFORM"));

			var entryEdgeId = "entry-" + stationId + "-" + LINE_1;
			edges.add(new PathwayEdge(
				entryEdgeId, entranceNodeId, platformNodeId, 60, 25, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState("STEP_FREE"));
			evidence.add(new RouteEdgeEvidence(
				"ev-" + entryEdgeId, stationId, LINE_1, entryEdgeId, "ENTRY",
				"OFFICIAL_SOURCE", "VERIFIED", true, null
			));

			var exitEdgeId = "exit-" + stationId + "-" + LINE_1;
			edges.add(new PathwayEdge(
				exitEdgeId, platformNodeId, exitNodeId, 60, 25, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState("STEP_FREE"));
			evidence.add(new RouteEdgeEvidence(
				"ev-" + exitEdgeId, stationId, LINE_1, exitEdgeId, "EXIT",
				"OFFICIAL_SOURCE", "VERIFIED", true, null
			));
		}

		return new RouteAccessData(nodes, edges, List.of(), evidence, List.of(), platformGaps);
	}
}
