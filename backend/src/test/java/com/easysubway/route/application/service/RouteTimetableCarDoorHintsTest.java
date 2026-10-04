package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.application.JourneyRequestMeasurement;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.CarDoorHint;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayEdge;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteAccessData;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteEdgeEvidence;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendar;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransferRule;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitRoute;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitStopTime;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitTrip;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Journey V3 빠른 하차 칸-문 힌트 제공 검증 (#432)")
class RouteTimetableCarDoorHintsTest {

	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 7, 6);
	private static final Instant DEPARTURE_INSTANT = Instant.parse("2026-07-06T00:15:00Z");

	@Test
	@DisplayName("(1) 환승 하차 구간에는 다음 이동인 TRANSFER 칸-문만 오름차순으로 제공한다")
	void providesOnlyTransferCarDoorsForTransferAlighting() {
		var planner = new RouteTimetableRaptorPlanner();
		var timetable = createTimetableWithCarDoorHints();
		var compiled = planner.compile(timetable);

		var query = JourneyRaptorQuery.from(createRequest("station-a", "station-c", JourneyRequest.ConstraintMode.NONE), DEPARTURE_INSTANT);
		var result = planner.journeyItineraries(
			query, compiled, RouteTimetableRaptorPlanner.RealtimeOverlay.empty(),
			new JourneyRequestMeasurement("req-1"), "req-1", "bundle-sha", 1L);

		assertThat(result.itineraries()).isNotEmpty();
		var itinerary = result.itineraries().getFirst();
		var rides = itinerary.legs().stream()
			.filter(RouteTimetableRaptorPlanner.JourneyRideProjection.class::isInstance)
			.map(RouteTimetableRaptorPlanner.JourneyRideProjection.class::cast)
			.toList();

		assertThat(rides).hasSize(2);
		var transferRide = rides.getFirst(); // line-1 ride from station-a to station-b
		assertThat(transferRide.toStationId()).isEqualTo("station-b");
		assertThat(transferRide.alightingCarDoors()).hasSize(2);
		assertThat(transferRide.alightingCarDoors()).extracting(RouteTimetableRaptorPlanner.AlightingCarDoor::targetFacilityType)
			.containsOnly("TRANSFER");
		assertThat(transferRide.alightingCarDoors().get(0).carNumber()).isEqualTo(3);
		assertThat(transferRide.alightingCarDoors().get(0).doorNumber()).isEqualTo(2);
		assertThat(transferRide.alightingCarDoors().get(1).carNumber()).isEqualTo(5);
		assertThat(transferRide.alightingCarDoors().get(1).doorNumber()).isEqualTo(1);
	}

	@Test
	@DisplayName("(2) 무단차 최종 하차에는 ELEVATOR만 제공하고 일반 요청에는 ELEVATOR·ESCALATOR·STAIR 전체를 제공한다")
	void providesOnlyElevatorForStepFreeFinalAlightingAndAllForGeneral() {
		var planner = new RouteTimetableRaptorPlanner();
		var timetable = createTimetableWithCarDoorHints();
		var compiled = planner.compile(timetable);

		// 2-1. 무단차 요청 (REQUIRE_STEP_FREE)
		var stepFreeQuery = JourneyRaptorQuery.from(
			createRequest("station-a", "station-c", JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE), DEPARTURE_INSTANT);
		var stepFreeResult = planner.journeyItineraries(
			stepFreeQuery, compiled, RouteTimetableRaptorPlanner.RealtimeOverlay.empty(),
			new JourneyRequestMeasurement("req-2-1"), "req-2-1", "bundle-sha", 1L);

		assertThat(stepFreeResult.itineraries()).isNotEmpty();
		var stepFreeRides = stepFreeResult.itineraries().getFirst().legs().stream()
			.filter(RouteTimetableRaptorPlanner.JourneyRideProjection.class::isInstance)
			.map(RouteTimetableRaptorPlanner.JourneyRideProjection.class::cast)
			.toList();

		var finalStepFreeRide = stepFreeRides.getLast(); // line-2 ride from station-b to station-c (exit)
		assertThat(finalStepFreeRide.toStationId()).isEqualTo("station-c");
		assertThat(finalStepFreeRide.alightingCarDoors()).hasSize(1);
		assertThat(finalStepFreeRide.alightingCarDoors().getFirst().targetFacilityType()).isEqualTo("ELEVATOR");
		assertThat(finalStepFreeRide.alightingCarDoors().getFirst().carNumber()).isEqualTo(2);
		assertThat(finalStepFreeRide.alightingCarDoors().getFirst().doorNumber()).isEqualTo(3);

		// 2-2. 일반 요청 (NONE) -> ELEVATOR, ESCALATOR, STAIR 모두 오름차순 포함
		var generalQuery = JourneyRaptorQuery.from(
			createRequest("station-a", "station-c", JourneyRequest.ConstraintMode.NONE), DEPARTURE_INSTANT);
		var generalResult = planner.journeyItineraries(
			generalQuery, compiled, RouteTimetableRaptorPlanner.RealtimeOverlay.empty(),
			new JourneyRequestMeasurement("req-2-2"), "req-2-2", "bundle-sha", 1L);

		assertThat(generalResult.itineraries()).isNotEmpty();
		var generalRides = generalResult.itineraries().getFirst().legs().stream()
			.filter(RouteTimetableRaptorPlanner.JourneyRideProjection.class::isInstance)
			.map(RouteTimetableRaptorPlanner.JourneyRideProjection.class::cast)
			.toList();

		var finalGeneralRide = generalRides.getLast();
		assertThat(finalGeneralRide.alightingCarDoors()).hasSize(3);
		// targetFacilityType 오름차순: ELEVATOR -> ESCALATOR -> STAIR
		assertThat(finalGeneralRide.alightingCarDoors().get(0).targetFacilityType()).isEqualTo("ELEVATOR");
		assertThat(finalGeneralRide.alightingCarDoors().get(0).carNumber()).isEqualTo(2);
		assertThat(finalGeneralRide.alightingCarDoors().get(0).doorNumber()).isEqualTo(3);
		assertThat(finalGeneralRide.alightingCarDoors().get(1).targetFacilityType()).isEqualTo("ESCALATOR");
		assertThat(finalGeneralRide.alightingCarDoors().get(1).carNumber()).isEqualTo(6);
		assertThat(finalGeneralRide.alightingCarDoors().get(1).doorNumber()).isEqualTo(2);
		assertThat(finalGeneralRide.alightingCarDoors().get(2).targetFacilityType()).isEqualTo("STAIR");
		assertThat(finalGeneralRide.alightingCarDoors().get(2).carNumber()).isEqualTo(4);
		assertThat(finalGeneralRide.alightingCarDoors().get(2).doorNumber()).isEqualTo(1);
	}

	@Test
	@DisplayName("(3) 방향이 다른 hint(예: DOWN)는 상행(up) trip에서 제외된다")
	void excludesHintsWithDifferentDirection() {
		var planner = new RouteTimetableRaptorPlanner();
		var timetable = createTimetableWithCarDoorHints();
		var compiled = planner.compile(timetable);

		var query = JourneyRaptorQuery.from(createRequest("station-a", "station-c", JourneyRequest.ConstraintMode.NONE), DEPARTURE_INSTANT);
		var result = planner.journeyItineraries(
			query, compiled, RouteTimetableRaptorPlanner.RealtimeOverlay.empty(),
			new JourneyRequestMeasurement("req-3"), "req-3", "bundle-sha", 1L);

		var firstRide = (RouteTimetableRaptorPlanner.JourneyRideProjection) result.itineraries().getFirst().legs().get(0);
		assertThat(firstRide.alightingCarDoors()).isNotEmpty();
		assertThat(firstRide.alightingCarDoors()).extracting(RouteTimetableRaptorPlanner.AlightingCarDoor::carNumber).contains(3, 5);
		// hint 4 (station-b, line-1, DOWN, car 8, door 4) must be excluded because trip-1 has directionId "up"
		assertThat(firstRide.alightingCarDoors()).noneMatch(d -> d.carNumber() == 8 && d.doorNumber() == 4);
	}

	@Test
	@DisplayName("(4) 방향 근거가 없는 노선(increasing·decreasing)은 빈 배열을 반환한다")
	void returnsEmptyCarDoorsWhenTripDirectionHasNoOfficialBasis() {
		var planner = new RouteTimetableRaptorPlanner();
		var timetable = createTimetableWithCarDoorHints();
		var compiled = planner.compile(timetable);

		var query = JourneyRaptorQuery.from(createRequest("station-a", "station-b", JourneyRequest.ConstraintMode.NONE), Instant.parse("2026-07-06T00:35:00Z"));
		var result = planner.journeyItineraries(
			query, compiled, RouteTimetableRaptorPlanner.RealtimeOverlay.empty(),
			new JourneyRequestMeasurement("req-4"), "req-4", "bundle-sha", 1L);

		var rides = result.itineraries().getFirst().legs().stream()
			.filter(RouteTimetableRaptorPlanner.JourneyRideProjection.class::isInstance)
			.map(RouteTimetableRaptorPlanner.JourneyRideProjection.class::cast)
			.toList();

		assertThat(rides).isNotEmpty();
		var ride = rides.getFirst();
		assertThat(ride.tripId()).isEqualTo("trip-3-nodir");
		assertThat(ride.alightingCarDoors()).isEmpty();
	}

	@Test
	@DisplayName("(5) hint 테이블이 없는 번들은 빈 배열과 로그를 남긴다")
	void returnsEmptyCarDoorsWhenHintTableIsMissing() {
		var planner = new RouteTimetableRaptorPlanner();
		var timetable = createTimetableWithoutCarDoorHints();
		var compiled = planner.compile(timetable);

		var query = JourneyRaptorQuery.from(createRequest("station-a", "station-c", JourneyRequest.ConstraintMode.NONE), DEPARTURE_INSTANT);
		var result = planner.journeyItineraries(
			query, compiled, RouteTimetableRaptorPlanner.RealtimeOverlay.empty(),
			new JourneyRequestMeasurement("req-5"), "req-5", "bundle-sha", 1L);

		assertThat(result.itineraries()).isNotEmpty();
		for (var leg : result.itineraries().getFirst().legs()) {
			if (leg instanceof RouteTimetableRaptorPlanner.JourneyRideProjection ride) {
				assertThat(ride.alightingCarDoors()).isEmpty();
			}
		}
	}

	@Test
	@DisplayName("(6) 동일한 (carNumber, doorNumber, targetFacilityType) hint 중복 행은 distinct하게 하나만 제공된다")
	void deduplicatesIdenticalCarDoorHints() {
		var planner = new RouteTimetableRaptorPlanner();
		var duplicatedHints = List.of(
			new CarDoorHint("station-b", "line-1", "UP", "TRANSFER", 3, 2),
			new CarDoorHint("station-b", "line-1", "UP", "TRANSFER", 3, 2),
			new CarDoorHint("station-b", "line-1", "UP", "TRANSFER", 5, 1)
		);
		var timetable = createBaseTimetable(duplicatedHints);
		var compiled = planner.compile(timetable);

		var query = JourneyRaptorQuery.from(createRequest("station-a", "station-c", JourneyRequest.ConstraintMode.NONE), DEPARTURE_INSTANT);
		var result = planner.journeyItineraries(
			query, compiled, RouteTimetableRaptorPlanner.RealtimeOverlay.empty(),
			new JourneyRequestMeasurement("req-6"), "req-6", "bundle-sha", 1L);

		var firstRide = (RouteTimetableRaptorPlanner.JourneyRideProjection) result.itineraries().getFirst().legs().get(0);
		assertThat(firstRide.alightingCarDoors()).hasSize(2);
		assertThat(firstRide.alightingCarDoors()).containsExactly(
			new RouteTimetableRaptorPlanner.AlightingCarDoor(3, 2, "TRANSFER"),
			new RouteTimetableRaptorPlanner.AlightingCarDoor(5, 1, "TRANSFER")
		);
	}

	@Test
	@DisplayName("(8) 방향 BOTH 힌트는 up/down/increasing/null 어느 트립에도 제공되고 INNER/OUTER는 제공되지 않는다")
	void providesBothDirectionHintsForAnyTripDirection() {
		var compiled = new RouteTimetableRaptorPlanner().compile(createBaseTimetable(List.of(
			new CarDoorHint("station-b", "line-1", "BOTH", "ELEVATOR", 3, 1),
			new CarDoorHint("station-b", "line-1", "UP", "ELEVATOR", 9, 2),
			new CarDoorHint("station-b", "line-1", "INNER", "ELEVATOR", 1, 1),
			new CarDoorHint("station-b", "line-1", "OUTER", "ELEVATOR", 2, 2),
			new CarDoorHint("station-b", "line-2", "BOTH", "ELEVATOR", 7, 3)
		)));
		var both = new RouteTimetableRaptorPlanner.AlightingCarDoor(3, 1, "ELEVATOR");
		var up = new RouteTimetableRaptorPlanner.AlightingCarDoor(9, 2, "ELEVATOR");

		assertThat(compiled.selectAlightingCarDoors("station-b", "line-1", "increasing", false, true)).containsExactly(both);
		assertThat(compiled.selectAlightingCarDoors("station-b", "line-1", "decreasing", false, true)).containsExactly(both);
		assertThat(compiled.selectAlightingCarDoors("station-b", "line-1", null, false, true)).containsExactly(both);
		assertThat(compiled.selectAlightingCarDoors("station-b", "line-1", "up", false, true)).containsExactly(both, up);
		assertThat(compiled.selectAlightingCarDoors("station-b", "line-1", "down", false, true)).containsExactly(both);
		assertThat(compiled.selectAlightingCarDoors("station-b", "line-3", "increasing", false, true)).isEmpty();
	}

	@Test
	@DisplayName("(9) BOTH와 UP에 같은 칸-문-종류가 겹치면 한 건만 제공된다")
	void deduplicatesBothAndDirectionalOverlap() {
		var compiled = new RouteTimetableRaptorPlanner().compile(createBaseTimetable(List.of(
			new CarDoorHint("station-b", "line-1", "BOTH", "ELEVATOR", 4, 4),
			new CarDoorHint("station-b", "line-1", "UP", "ELEVATOR", 4, 4)
		)));

		assertThat(compiled.selectAlightingCarDoors("station-b", "line-1", "up", false, false))
			.containsExactly(new RouteTimetableRaptorPlanner.AlightingCarDoor(4, 4, "ELEVATOR"));
	}

	@Test
	@DisplayName("(7) AlightingCarDoor 및 JourneyRideProjection 생성자 경계 검증")
	void validatesAlightingCarDoorAndProjectionBoundary() {
		assertThatThrownBy(() -> new RouteTimetableRaptorPlanner.AlightingCarDoor(0, 1, "TRANSFER"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RouteTimetableRaptorPlanner.AlightingCarDoor(11, 1, "TRANSFER"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RouteTimetableRaptorPlanner.AlightingCarDoor(1, 0, "TRANSFER"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RouteTimetableRaptorPlanner.AlightingCarDoor(1, 5, "TRANSFER"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RouteTimetableRaptorPlanner.AlightingCarDoor(1, 1, null))
			.isInstanceOf(NullPointerException.class);

		var door = new RouteTimetableRaptorPlanner.AlightingCarDoor(1, 2, "TRANSFER");
		assertThat(door.carNumber()).isEqualTo(1);
		assertThat(door.doorNumber()).isEqualTo(2);
		assertThat(door.targetFacilityType()).isEqualTo("TRANSFER");
		assertThat(door).isEqualTo(new RouteTimetableRaptorPlanner.AlightingCarDoor(1, 2, "TRANSFER"));
		assertThat(door.hashCode()).isNotZero();
		assertThat(door.toString()).contains("1");

		var projection10Arg = TestProjectionRides.projectionRide(
			"l", "t", "d", "f", "to", DEPARTURE_INSTANT, DEPARTURE_INSTANT, null, null
		);
		assertThat(projection10Arg.alightingCarDoors()).isEmpty();

		var projectionNullDoors = new RouteTimetableRaptorPlanner.JourneyRideProjection(
			"l", "t", "d", "f", "to", "LOCAL", DEPARTURE_INSTANT, DEPARTURE_INSTANT, null, null, List.of(), null, null, null
		);
		assertThat(projectionNullDoors.alightingCarDoors()).isEmpty();

		var planner = new RouteTimetableRaptorPlanner();
		var compiled = planner.compile(createTimetableWithCarDoorHints());
		assertThat(compiled.selectAlightingCarDoors(null, "l", "up", false, false)).isEmpty();
		assertThat(compiled.selectAlightingCarDoors("s", null, "up", false, false)).isEmpty();
		assertThat(compiled.selectAlightingCarDoors("s", "l", null, false, false)).isEmpty();
		assertThat(compiled.selectAlightingCarDoors("s", "l", "unknown", false, false)).isEmpty();
		assertThat(compiled.tripDirection("non-existent-trip-id")).isNull();
	}

	private static JourneyRequest createRequest(String origin, String destination, JourneyRequest.ConstraintMode constraintMode) {
		return new JourneyRequest(
			"01HZY3Q4J5K6M7N8P9Q0R1S2T3",
			origin,
			destination,
			new JourneyRequest.Departure.Scheduled(DEPARTURE_INSTANT),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD,
			constraintMode,
			1,
			1,
			() -> false
		);
	}

	private static RouteTimetable createTimetableWithCarDoorHints() {
		var hints = List.of(
			new CarDoorHint("station-b", "line-1", "UP", "TRANSFER", 3, 2),
			new CarDoorHint("station-b", "line-1", "UP", "TRANSFER", 5, 1),
			new CarDoorHint("station-b", "line-1", "UP", "ELEVATOR", 1, 1),
			new CarDoorHint("station-b", "line-1", "DOWN", "TRANSFER", 8, 4),
			new CarDoorHint("station-c", "line-2", "DOWN", "ELEVATOR", 2, 3),
			new CarDoorHint("station-c", "line-2", "DOWN", "STAIR", 4, 1),
			new CarDoorHint("station-c", "line-2", "DOWN", "ESCALATOR", 6, 2),
			new CarDoorHint("station-b", "line-3", "UP", "TRANSFER", 3, 3)
		);
		return createBaseTimetable(hints);
	}

	private static RouteTimetable createTimetableWithoutCarDoorHints() {
		return createBaseTimetable(List.of());
	}

	private static RouteTimetable createBaseTimetable(List<CarDoorHint> hints) {
		var routes = List.of(
			new TransitRoute("r1", "line-1", "r1", "line-1", "Line 1", "Asia/Seoul"),
			new TransitRoute("r2", "line-2", "r2", "line-2", "Line 2", "Asia/Seoul"),
			new TransitRoute("r3", "line-3", "r3", "line-3", "Line 3", "Asia/Seoul")
		);

		var trips = List.of(
			new TransitTrip("trip-1", "r1", "daily", "trip-1", "up", "LOCAL", 0),
			new TransitTrip("trip-2", "r2", "daily", "trip-2", "down", "LOCAL", 0),
			new TransitTrip("trip-3-nodir", "r3", "daily", "trip-3-nodir", "increasing", "LOCAL", 0)
		);

		var stopTimes = List.of(
			new TransitStopTime("trip-1", 1, "station-a", "line-1", 33600, 33600, 0, 0),
			new TransitStopTime("trip-1", 2, "station-b", "line-1", 34200, 34200, 0, 0),
			new TransitStopTime("trip-2", 1, "station-b", "line-2", 34800, 34800, 0, 0),
			new TransitStopTime("trip-2", 2, "station-c", "line-2", 35400, 35400, 0, 0),
			new TransitStopTime("trip-3-nodir", 1, "station-a", "line-3", 35000, 35000, 0, 0),
			new TransitStopTime("trip-3-nodir", 2, "station-b", "line-3", 35600, 35600, 0, 0)
		);

		var daily = new ServiceCalendar(
			"daily", true, true, true, true, true, true, true,
			SERVICE_DATE, SERVICE_DATE.plusDays(7), "Asia/Seoul");

		var nodes = List.of(
			new PathwayNode("entrance", "station-a", null, "ENTRANCE"),
			new PathwayNode("platform-a", "station-a", "line-1", "PLATFORM"),
			new PathwayNode("platform-transfer-a", "station-b", "line-1", "PLATFORM"),
			new PathwayNode("platform-transfer-b", "station-b", "line-2", "PLATFORM"),
			new PathwayNode("platform-c", "station-c", "line-2", "PLATFORM"),
			new PathwayNode("outside", "station-c", null, "EXIT"),
			new PathwayNode("entrance-3", "station-a", null, "ENTRANCE"),
			new PathwayNode("platform-a-l3", "station-a", "line-3", "PLATFORM"),
			new PathwayNode("platform-b-l3", "station-b", "line-3", "PLATFORM"),
			new PathwayNode("outside-3", "station-b", null, "EXIT")
		);

		var edges = List.of(
			new PathwayEdge("entry", "entrance", "platform-a", 120, 60, false, false, 100, "AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState("STEP_FREE"),
			new PathwayEdge("transfer", "platform-transfer-a", "platform-transfer-b", 120, 60, false, false, 100, "AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState("STEP_FREE"),
			new PathwayEdge("exit", "platform-c", "outside", 60, 40, false, false, 100, "AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState("STEP_FREE"),
			new PathwayEdge("entry-3", "entrance-3", "platform-a-l3", 120, 60, false, false, 100, "AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState("STEP_FREE"),
			new PathwayEdge("exit-3", "platform-b-l3", "outside-3", 60, 40, false, false, 100, "AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState("STEP_FREE")
		);

		var transferRules = List.of(
			new TransferRule("transfer-rule", "station-b", "line-1", "station-b", "line-2", "IN_STATION", 120, "transfer", "transfer", "VERIFIED")
		);

		var evidence = List.of(
			new RouteEdgeEvidence("entry-evidence", "station-a", "line-1", "entry", "ENTRY", "OFFICIAL_SOURCE", "VERIFIED", true, null),
			new RouteEdgeEvidence("transfer-evidence", "station-b", "line-2", "transfer", "TRANSFER", "OFFICIAL_SOURCE", "VERIFIED", true, null),
			new RouteEdgeEvidence("exit-evidence", "station-c", "line-2", "exit", "EXIT", "OFFICIAL_SOURCE", "VERIFIED", true, null),
			new RouteEdgeEvidence("entry-3-evidence", "station-a", "line-3", "entry-3", "ENTRY", "OFFICIAL_SOURCE", "VERIFIED", true, null),
			new RouteEdgeEvidence("exit-3-evidence", "station-b", "line-3", "exit-3", "EXIT", "OFFICIAL_SOURCE", "VERIFIED", true, null)
		);

		return new RouteTimetable(
			List.of(daily),
			List.of(),
			routes,
			trips,
			stopTimes,
			List.of(),
			List.of(),
			null,
			new RouteAccessData(nodes, edges, transferRules, evidence, hints)
		);
	}
}
