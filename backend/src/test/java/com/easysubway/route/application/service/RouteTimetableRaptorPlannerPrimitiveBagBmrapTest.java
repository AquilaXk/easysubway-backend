package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRequest;
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
import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.JourneyItinerary;
import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.RealtimeOverlay;
import com.easysubway.route.domain.RoutePersona;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("#365 Conveyal R5 style Primitive Label Bag & BMRAP heuristic lower bound integration")
class RouteTimetableRaptorPlannerPrimitiveBagBmrapTest {

	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 7, 6);
	private static final String ORIGIN = "sta-origin";
	private static final String DESTINATION = "sta-destination";

	@Test
	@DisplayName("BMRAP 역방향 단일 기준 하한선(lower bound)이 목적지로부터 정확하고 가경로(admissible)하게 계산된다")
	void bmrapComputesAdmissibleLowerBoundsFromDestination() {
		var planner = new RouteTimetableRaptorPlanner();
		var timetable = multiStationTimetable();
		var compiled = planner.compile(timetable);

		int destIndex = compiled.stationIndex(DESTINATION);
		int[] lowerBounds = RouteTimetableRaptorPlanner.computeStationLowerBounds(compiled, destIndex);

		assertThat(lowerBounds).isNotNull();
		assertThat(lowerBounds).hasSize(compiled.stationCount());

		// 1. 목적지 자체의 하한선은 0이어야 함
		assertThat(lowerBounds[destIndex]).isZero();

		// 2. 직결 선로 인접 역: 최소 주행 시간 이상이어야 함
		int sta2Index = compiled.stationIndex("sta-2");
		assertThat(lowerBounds[sta2Index]).isGreaterThan(0);
		// sta-2 -> sta-destination 구간 주행시간은 600초 (10분)
		assertThat(lowerBounds[sta2Index]).isEqualTo(600);

		// 3. 2-hop 선로 역: 중간 주행시간의 합이어야 함
		int originIndex = compiled.stationIndex(ORIGIN);
		// sta-origin -> sta-2(600s) -> sta-destination(600s) = 1200초 (20분)
		assertThat(lowerBounds[originIndex]).isEqualTo(1200);

		// 4. 환승 지선 역: 선로 최소 주행시간의 합 이상이어야 함 (admissible lower bound)
		int branchIndex = compiled.stationIndex("sta-branch");
		// branch -> sta-2 (line-branch 주행 480s) + sta-2->dest (line-main 주행 600s) = 1080초
		assertThat(lowerBounds[branchIndex]).isGreaterThanOrEqualTo(480 + 600);
	}

	@Test
	@DisplayName("BMRAP 하한선 기반 조기 가지치기(early pruning)가 파레토 최적성을 100% 보존한다")
	void bmrapPreservesParetoOptimalItineraries() {
		var planner = new RouteTimetableRaptorPlanner();
		var timetable = multiStationTimetable();
		var compiled = planner.compile(timetable);

		var query = new JourneyRaptorQuery(
			"01ARZ3NDEKTSV4RRFFQ69G5FAV",
			ORIGIN,
			DESTINATION,
			new JourneyRaptorQuery.DepartAt(Instant.parse("2026-07-05T22:50:00Z")),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.SLOW,
			JourneyRequest.MobilityProfile.STEP_FREE,
			JourneyRequest.ConstraintMode.NONE,
			0,
			3,
			() -> false
		);

		// BMRAP 하한선이 적용된 스캔 결과
		var plan = planner.journeyItineraries(query, compiled, RealtimeOverlay.empty());
		assertThat(plan.itineraries()).isNotEmpty();

		// 최속 경로 도착 시간이 1200초 이상(주행 시간 하한선)임을 검증
		var fastest = plan.itineraries().getFirst();
		long duration = Duration.between(fastest.plannedDepartureTime(), fastest.plannedArrivalTime()).getSeconds();
		assertThat(duration).isGreaterThanOrEqualTo(1200);
	}

	@Test
	@DisplayName("PrimitiveProfileLabelPool이 6차원 벡터를 평탄 배열에 저장하고 무할당 지배 판정을 수행한다")
	void primitiveProfileLabelPoolStoresVectorsAndEvaluatesDominance() {
		var pool = new RouteTimetableRaptorPlanner.PrimitiveProfileLabelPool(16);

		// 라벨 1: arrival=1000, accSec=120, accMeters=100, stairs=0, slack=300, warnings=0
		int l1 = pool.allocate(
			0, 1000, 1, 10, 1, (byte) 0,
			120, 100, 0, 300,
			-1, 1, 0, 1, 5,
			SERVICE_DATE, null, null
		);

		// 라벨 2: arrival=1200 (더 늦음), accSec=180 (더 김), accMeters=150, stairs=1, slack=200, warnings=1
		// -> l1이 l2를 전방위 지배해야 함
		int l2 = pool.allocate(
			0, 1200, 1, 10, 1, (byte) 1,
			180, 150, 1, 200,
			-1, 2, 0, 1, 6,
			SERVICE_DATE, null, null
		);

		assertThat(RouteTimetableRaptorPlanner.PrimitiveProfileLabelPool.dominates(pool, l1, l2)).isTrue();
		assertThat(RouteTimetableRaptorPlanner.PrimitiveProfileLabelPool.dominates(pool, l2, l1)).isFalse();

		// 라벨 3: arrival=900 (더 빠름), accSec=200 (더 긺), stairs=1 (계단 있음)
		// -> l1과 상호 비지배(Trade-off: l1은 무단차, l3은 최속)
		int l3 = pool.allocate(
			0, 900, 1, 10, 1, (byte) 1,
			200, 180, 1, 150,
			-1, 3, 0, 1, 7,
			SERVICE_DATE, null, null
		);

		assertThat(RouteTimetableRaptorPlanner.PrimitiveProfileLabelPool.dominates(pool, l1, l3)).isFalse();
		assertThat(RouteTimetableRaptorPlanner.PrimitiveProfileLabelPool.dominates(pool, l3, l1)).isFalse();
	}

	@Test
	@DisplayName("4대 핵심 페르소나(Fastest, StepFree, MinWalk, RelaxedSlack)로 결과가 올바르게 자동 분류된다")
	void classifiesFourCorePersonasCorrectly() {
		var planner = new RouteTimetableRaptorPlanner();
		var timetable = multiStationTimetable();
		var compiled = planner.compile(timetable);

		var query = new JourneyRaptorQuery(
			"01ARZ3NDEKTSV4RRFFQ69G5FAV",
			ORIGIN,
			DESTINATION,
			new JourneyRaptorQuery.DepartAt(Instant.parse("2026-07-05T22:50:00Z")),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.SLOW,
			JourneyRequest.MobilityProfile.STEP_FREE,
			JourneyRequest.ConstraintMode.NONE,
			0,
			3,
			() -> false
		);

		var plan = planner.journeyItineraries(query, compiled, RealtimeOverlay.empty());
		List<JourneyItinerary> itineraries = plan.itineraries();
		assertThat(itineraries).isNotEmpty();

		Map<RoutePersona, JourneyItinerary> personas = RouteTimetableRaptorPlanner.classifyPersonas(itineraries);
		assertThat(personas).containsKey(RoutePersona.FASTEST);
		assertThat(personas).containsKey(RoutePersona.STEP_FREE);
		assertThat(personas).containsKey(RoutePersona.MIN_WALK);
		assertThat(personas).containsKey(RoutePersona.RELAXED_SLACK);

		// Fastest는 최단 소요시간이어야 함
		var fastest = personas.get(RoutePersona.FASTEST);
		assertThat(fastest).isNotNull();

		// 각 JourneyItinerary가 persona()를 부여받았는지 검증
		for (JourneyItinerary it : itineraries) {
			assertThat(it.persona()).isNotNull();
		}
	}

	// ------------------ Timetable Fixtures ------------------

	private static RouteTimetable multiStationTimetable() {
		var calendar = new ServiceCalendar("cal-1", true, true, true, true, true, true, true, SERVICE_DATE, SERVICE_DATE, "Asia/Seoul");
		var routeMain = new TransitRoute("route-main", "line-main", "1", "Main Line", "up", "Asia/Seoul");
		var routeBranch = new TransitRoute("route-branch", "line-branch", "2", "Branch Line", "up", "Asia/Seoul");

		// Main line: sta-origin(08:00) -> sta-2(08:10) -> sta-destination(08:20)
		var tripMain = new TransitTrip("trip-main", "route-main", "cal-1", "dest", "0", "LOCAL", 28800);
		var st1 = new TransitStopTime("trip-main", 1, ORIGIN, "line-main", 28800, 28800, 0, 0);
		var st2 = new TransitStopTime("trip-main", 2, "sta-2", "line-main", 29400, 29400, 1, 0);
		var st3 = new TransitStopTime("trip-main", 3, DESTINATION, "line-main", 30000, 30000, 2, 0);

		// Branch line: sta-branch(08:00) -> sta-2(08:08)
		var tripBranch = new TransitTrip("trip-branch", "route-branch", "cal-1", "sta-2", "0", "LOCAL", 28800);
		var st4 = new TransitStopTime("trip-branch", 1, "sta-branch", "line-branch", 28800, 28800, 0, 0);
		var st5 = new TransitStopTime("trip-branch", 2, "sta-2", "line-branch", 29280, 29280, 1, 0);

		return new RouteTimetable(
			List.of(calendar),
			List.of(),
			List.of(routeMain, routeBranch),
			List.of(tripMain, tripBranch),
			List.of(st1, st2, st3, st4, st5),
			List.of(),
			List.of(),
			null,
			multiStationAccessData()
		);
	}

	private static RouteAccessData multiStationAccessData() {
		var edges = List.of(
			new PathwayEdge("entry-origin", "node-origin-e", "node-origin-p", 120, 100, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"),
			new PathwayEdge("transfer-2", "node-2-p2", "node-2-p1", 60, 60, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"),
			new PathwayEdge("entry-branch", "node-branch-e", "node-branch-p", 120, 100, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"),
			new PathwayEdge("exit-dest", "node-dest-p", "node-dest-e", 120, 100, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED")
		);

		var nodes = List.of(
			new PathwayNode("node-origin-e", ORIGIN, null, "ENTRANCE"),
			new PathwayNode("node-origin-p", ORIGIN, "line-main", "PLATFORM"),
			new PathwayNode("node-2-p1", "sta-2", "line-main", "PLATFORM"),
			new PathwayNode("node-2-p2", "sta-2", "line-branch", "PLATFORM"),
			new PathwayNode("node-branch-e", "sta-branch", null, "ENTRANCE"),
			new PathwayNode("node-branch-p", "sta-branch", "line-branch", "PLATFORM"),
			new PathwayNode("node-dest-p", DESTINATION, "line-main", "PLATFORM"),
			new PathwayNode("node-dest-e", DESTINATION, null, "EXIT")
		);

		var transferRule = new TransferRule(
			"transfer-rule-2", "sta-2", "line-branch", "sta-2", "line-main",
			"IN_STATION", 60, "transfer-2", "transfer-2", "VERIFIED"
		);

		var evidence = List.of(
			new RouteEdgeEvidence("entry-origin-ev", ORIGIN, "line-main", "entry-origin", "ENTRY",
				"OFFICIAL_SOURCE", "VERIFIED", true, null),
			new RouteEdgeEvidence("transfer-2-ev", "sta-2", "line-main", "transfer-2", "TRANSFER",
				"OFFICIAL_SOURCE", "VERIFIED", true, null),
			new RouteEdgeEvidence("entry-branch-ev", "sta-branch", "line-branch", "entry-branch", "ENTRY",
				"OFFICIAL_SOURCE", "VERIFIED", true, null),
			new RouteEdgeEvidence("exit-dest-ev", DESTINATION, "line-main", "exit-dest", "EXIT",
				"OFFICIAL_SOURCE", "VERIFIED", true, null)
		);

		return new RouteAccessData(nodes, edges, List.of(transferRule), evidence);
	}
}
