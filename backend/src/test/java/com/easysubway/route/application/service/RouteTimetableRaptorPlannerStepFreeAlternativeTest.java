package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.SearchRouteV2Command;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.domain.ConstraintMode;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteStep;
import com.easysubway.route.domain.RouteWarning;
import com.easysubway.route.domain.RouteWarningCode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("#2534 PREFER_STEP_FREE 목적지 라벨 경고 차원 보존")
class RouteTimetableRaptorPlannerStepFreeAlternativeTest {

	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 7, 6);
	private static final String ORIGIN = "origin";
	private static final String STAIR_HUB = "stair-hub";
	private static final String STEP_FREE_HUB = "step-free-hub";
	private static final String UNVERIFIED_HUB = "unverified-hub";
	private static final String STALE_HUB = "stale-hub";
	private static final String MID_HUB = "mid-hub";
	private static final String DESTINATION = "destination";

	@Test
	@DisplayName("PREFER_STEP_FREE는 더 빠른 계단 경로(37분)와 무단차 대안(40분)을 함께 남긴다")
	void preservesStepFreeAlternativeWhenStairRouteIsFaster() {
		var planner = new RouteTimetableRaptorPlanner();

		List<RouteSearchResult> results = planner.search(
			command(ConstraintMode.PREFER_STEP_FREE, 1, 2), timetable(false));

		assertThat(results)
			.extracting(RouteSearchResult::score, RouteTimetableRaptorPlannerStepFreeAlternativeTest::transferStationId,
				RouteTimetableRaptorPlannerStepFreeAlternativeTest::warningCodes)
			.containsExactly(
				tuple(37, STAIR_HUB, List.of(RouteWarningCode.STAIR_ONLY_ACCESS)),
				tuple(40, STEP_FREE_HUB, List.of()));
		assertThat(transferStep(results.getLast()).includesStairs()).isFalse();
	}

	@Test
	@DisplayName("후보가 상한을 넘으면 가장 느린 경고 후보 대신 무단차 대안을 남긴다")
	void keepsStepFreeAlternativeWhenCandidateLimitTruncates() {
		var planner = new RouteTimetableRaptorPlanner();

		List<RouteSearchResult> results = planner.search(
			command(ConstraintMode.PREFER_STEP_FREE, 1, 2), timetable(true));

		assertThat(results)
			.extracting(RouteSearchResult::score, RouteTimetableRaptorPlannerStepFreeAlternativeTest::transferStationId)
			.containsExactly(tuple(37, STAIR_HUB), tuple(40, STEP_FREE_HUB));
	}

	@Test
	@DisplayName("자리가 하나뿐이면(candidateLimit()==1) 최속 경로를 교체하지 않는다")
	void keepsFastestRouteWhenCandidateLimitIsOne() {
		var planner = new RouteTimetableRaptorPlanner();

		List<RouteSearchResult> results = planner.search(
			command(ConstraintMode.PREFER_STEP_FREE, 0, 1), directRoutesTimetable());

		assertThat(results)
			.extracting(RouteSearchResult::score, RouteTimetableRaptorPlannerStepFreeAlternativeTest::warningCodes)
			.containsExactly(tuple(30, List.of(RouteWarningCode.STAIR_ONLY_ACCESS)));
	}

	@Test
	@DisplayName("상한 교체가 유일한 최소 환승 후보를 축출하지 않는다")
	void keepsUniqueFewestTransferCandidateWhenLimitTruncates() {
		var planner = new RouteTimetableRaptorPlanner();

		List<RouteSearchResult> results = planner.search(
			command(ConstraintMode.PREFER_STEP_FREE, 1, 2), mixedBoardingsTimetable());

		assertThat(results)
			.extracting(RouteSearchResult::score, RouteSearchResult::transferCount)
			.containsExactly(tuple(30, 1), tuple(35, 0));
	}

	@Test
	@DisplayName("무단차 대안이 환승을 한 번 더 해도 보존된다")
	void keepsStepFreeAlternativeThatNeedsMoreTransfers() {
		var planner = new RouteTimetableRaptorPlanner();

		List<RouteSearchResult> results = planner.search(
			command(ConstraintMode.PREFER_STEP_FREE, 2, 2), deeperStepFreeTimetable());

		assertThat(results)
			.extracting(RouteSearchResult::score, RouteSearchResult::transferCount,
				RouteTimetableRaptorPlannerStepFreeAlternativeTest::warningCodes)
			.containsExactly(
				tuple(37, 1, List.of(RouteWarningCode.STAIR_ONLY_ACCESS)),
				tuple(50, 2, List.of()));
	}

	@Test
	@DisplayName("경고 0개 후보가 없어도 유일한 무단차 후보를 남긴다")
	void keepsOnlyStepFreeCandidateWhenNoWarningFreeCandidateExists() {
		var planner = new RouteTimetableRaptorPlanner();

		List<RouteSearchResult> results = planner.search(
			command(ConstraintMode.PREFER_STEP_FREE, 1, 2), noWarningFreeTimetable());

		assertThat(results)
			.extracting(RouteSearchResult::score, RouteTimetableRaptorPlannerStepFreeAlternativeTest::warningCodes)
			.containsExactly(
				tuple(30, List.of(RouteWarningCode.LOW_DATA_CONFIDENCE, RouteWarningCode.STAIR_ONLY_ACCESS,
					RouteWarningCode.STALE_ACCESSIBILITY_DATA)),
				tuple(50, List.of(RouteWarningCode.LOW_DATA_CONFIDENCE)));
	}

	@Test
	@DisplayName("alternativeCount 상한 안에서만 후보가 늘어난다")
	void keepsCandidateCountWithinAlternativeCountBound() {
		var planner = new RouteTimetableRaptorPlanner();

		List<RouteSearchResult> results = planner.search(
			command(ConstraintMode.PREFER_STEP_FREE, 1, 3), timetable(true));

		assertThat(results)
			.extracting(RouteSearchResult::score, RouteTimetableRaptorPlannerStepFreeAlternativeTest::transferStationId)
			.containsExactly(tuple(37, STAIR_HUB), tuple(38, UNVERIFIED_HUB), tuple(40, STEP_FREE_HUB));
	}

	@Test
	@DisplayName("ALLOW_WITH_WARNINGS는 기존대로 환승 수마다 최속 후보 1개만 남긴다")
	void allowWithWarningsKeepsSingleFastestCandidatePerBoardings() {
		var planner = new RouteTimetableRaptorPlanner();

		List<RouteSearchResult> results = planner.search(
			command(ConstraintMode.ALLOW_WITH_WARNINGS, 1, 2), timetable(true));

		assertThat(results)
			.extracting(RouteSearchResult::score, RouteTimetableRaptorPlannerStepFreeAlternativeTest::transferStationId,
				RouteTimetableRaptorPlannerStepFreeAlternativeTest::warningCodes)
			.containsExactly(tuple(37, STAIR_HUB, List.of(RouteWarningCode.STAIR_ONLY_ACCESS)));
	}

	@Test
	@DisplayName("STRICT_STEP_FREE는 계단 전이가 차단되어 무단차 경로만 남는다")
	void strictStepFreeKeepsOnlyStepFreeRoute() {
		var planner = new RouteTimetableRaptorPlanner();

		List<RouteSearchResult> results = planner.search(
			command(ConstraintMode.STRICT_STEP_FREE, 1, 2), timetable(true));

		assertThat(results)
			.extracting(RouteSearchResult::score, RouteTimetableRaptorPlannerStepFreeAlternativeTest::transferStationId,
				RouteTimetableRaptorPlannerStepFreeAlternativeTest::warningCodes)
			.containsExactly(tuple(40, STEP_FREE_HUB, List.of()));
	}

	private static List<RouteWarningCode> warningCodes(RouteSearchResult result) {
		return result.warnings().stream().map(RouteWarning::code).toList();
	}

	private static String transferStationId(RouteSearchResult result) {
		return transferStep(result).fromStationId();
	}

	private static RouteStep transferStep(RouteSearchResult result) {
		return result.steps().stream()
			.filter(step -> "transfer".equals(step.stepType()))
			.findFirst()
			.orElseThrow();
	}

	private static SearchRouteV2Command command(
		ConstraintMode constraintMode, int maxTransfers, int alternativeCount
	) {
		return new SearchRouteV2Command(
			ORIGIN,
			DESTINATION,
			OffsetDateTime.of(2026, 7, 6, 8, 0, 0, 0, ZoneOffset.ofHours(9)),
			MobilityType.SENIOR,
			constraintMode,
			false,
			maxTransfers,
			alternativeCount
		);
	}

	/**
	 * 08:00 출발 기준으로 환승 1회짜리 세 경로를 만든다.
	 *
	 * <ul>
	 *   <li>{@code stair-hub} 환승: 계단 전이(120초) — 37분, STAIR_ONLY_ACCESS 경고</li>
	 *   <li>{@code unverified-hub} 환승: 환승 규칙 없음(기본 360초) — 38분, LOW_DATA_CONFIDENCE 경고</li>
	 *   <li>{@code step-free-hub} 환승: 검증된 무단차 전이(360초) — 40분, 경고 없음</li>
	 * </ul>
	 */
	private static RouteTimetable timetable(boolean includeUnverifiedHub) {
		List<LoadRouteTimetablePort.TransitRoute> routes = new ArrayList<>(List.of(
			route("r1", "l1"), route("r2", "l2"), route("r3", "l3")));
		List<LoadRouteTimetablePort.TransitTrip> trips = new ArrayList<>(List.of(
			trip("t1", "r1"), trip("t2", "r2"), trip("t3", "r3")));
		List<LoadRouteTimetablePort.TransitStopTime> stops = new ArrayList<>(List.of(
			stop("t1", 1, ORIGIN, "l1", 29400),
			stop("t1", 2, STAIR_HUB, "l1", 29700),
			stop("t1", 3, STEP_FREE_HUB, "l1", 30000),
			stop("t2", 1, STAIR_HUB, "l2", 30000),
			stop("t2", 2, DESTINATION, "l2", 30777),
			stop("t3", 1, STEP_FREE_HUB, "l3", 30600),
			stop("t3", 2, DESTINATION, "l3", 30957)));
		if (includeUnverifiedHub) {
			routes.add(route("r4", "l4"));
			trips.add(trip("t4", "r4"));
			stops.add(stop("t1", 4, UNVERIFIED_HUB, "l1", 30060));
			stops.add(stop("t4", 1, UNVERIFIED_HUB, "l4", 30660));
			stops.add(stop("t4", 2, DESTINATION, "l4", 30837));
		}
		return timetable(routes, trips, stops, accessData(includeUnverifiedHub));
	}

	private static RouteTimetable timetable(
		List<LoadRouteTimetablePort.TransitRoute> routes,
		List<LoadRouteTimetablePort.TransitTrip> trips,
		List<LoadRouteTimetablePort.TransitStopTime> stops,
		LoadRouteTimetablePort.RouteAccessData accessData
	) {
		var daily = new LoadRouteTimetablePort.ServiceCalendar(
			"daily", true, true, true, true, true, true, true,
			SERVICE_DATE, SERVICE_DATE.plusDays(7), "Asia/Seoul");
		return new RouteTimetable(
			List.of(daily), List.of(), List.copyOf(routes), List.copyOf(trips), List.copyOf(stops),
			List.of(), List.of(), null, accessData);
	}

	/**
	 * 직통 두 개만 있는 시각표 — 30분(계단 진입, `STAIR_ONLY_ACCESS`)과 35분(무단차, 경고 없음).
	 * `candidateLimit()`이 1이 되는 경계를 만들기 위해 환승 경로를 두지 않는다.
	 */
	private static RouteTimetable directRoutesTimetable() {
		List<LoadRouteTimetablePort.PathwayNode> nodes = new ArrayList<>();
		List<LoadRouteTimetablePort.PathwayEdge> edges = new ArrayList<>();
		List<LoadRouteTimetablePort.RouteEdgeEvidence> evidence = new ArrayList<>();
		addAccess(nodes, edges, evidence, ORIGIN, "lf", true);
		addAccess(nodes, edges, evidence, DESTINATION, "lf", false);
		addAccess(nodes, edges, evidence, ORIGIN, "ls", false);
		addAccess(nodes, edges, evidence, DESTINATION, "ls", false);
		return timetable(
			List.of(route("rf", "lf"), route("rs", "ls")),
			List.of(trip("tf", "rf"), trip("ts", "rs")),
			List.of(
				stop("tf", 1, ORIGIN, "lf", 29400), stop("tf", 2, DESTINATION, "lf", 30357),
				stop("ts", 1, ORIGIN, "ls", 29400), stop("ts", 2, DESTINATION, "ls", 30657)),
			new LoadRouteTimetablePort.RouteAccessData(nodes, edges, List.of(), evidence));
	}

	/**
	 * 환승 수가 서로 다른 세 경로 — 30분(환승 1회, 계단), 35분(직통, 계단 진입), 40분(환승 1회, 무단차).
	 * 상한 교체가 유일한 최소 환승 후보(직통 35분)를 축출하는지 보기 위한 시각표다.
	 */
	private static RouteTimetable mixedBoardingsTimetable() {
		List<LoadRouteTimetablePort.PathwayNode> nodes = new ArrayList<>();
		List<LoadRouteTimetablePort.PathwayEdge> edges = new ArrayList<>();
		List<LoadRouteTimetablePort.RouteEdgeEvidence> evidence = new ArrayList<>();
		for (String stationLine : List.of(
			ORIGIN + ":l1", STAIR_HUB + ":l1", STAIR_HUB + ":l2", STEP_FREE_HUB + ":l1",
			STEP_FREE_HUB + ":l3", DESTINATION + ":l2", DESTINATION + ":l3", DESTINATION + ":ld")) {
			String[] parts = stationLine.split(":");
			addAccess(nodes, edges, evidence, parts[0], parts[1], false);
		}
		addAccess(nodes, edges, evidence, ORIGIN, "ld", true);
		List<LoadRouteTimetablePort.TransferRule> transfers = new ArrayList<>();
		addTransfer(nodes, edges, evidence, transfers, STAIR_HUB, "l1", "l2", 120, true, "VERIFIED");
		addTransfer(nodes, edges, evidence, transfers, STEP_FREE_HUB, "l1", "l3", 360, false, "VERIFIED");
		return timetable(
			List.of(route("r1", "l1"), route("r2", "l2"), route("r3", "l3"), route("rd", "ld")),
			List.of(trip("t1", "r1"), trip("t2", "r2"), trip("t3", "r3"), trip("td", "rd")),
			List.of(
				stop("t1", 1, ORIGIN, "l1", 29400),
				stop("t1", 2, STAIR_HUB, "l1", 29700),
				stop("t1", 3, STEP_FREE_HUB, "l1", 30000),
				stop("t2", 1, STAIR_HUB, "l2", 30000),
				stop("t2", 2, DESTINATION, "l2", 30357),
				stop("t3", 1, STEP_FREE_HUB, "l3", 30600),
				stop("t3", 2, DESTINATION, "l3", 30957),
				stop("td", 1, ORIGIN, "ld", 29400),
				stop("td", 2, DESTINATION, "ld", 30657)),
			new LoadRouteTimetablePort.RouteAccessData(nodes, edges, transfers, evidence));
	}

	/**
	 * 무단차 대안이 환승을 한 번 더 하는 시각표 — 37분(환승 1회, 계단)과 50분(환승 2회, 무단차).
	 * 경고 부분집합 관계가 성립하지 않아 환승 수·시간만으로는 무단차 쪽이 지배당한다.
	 */
	private static RouteTimetable deeperStepFreeTimetable() {
		List<LoadRouteTimetablePort.PathwayNode> nodes = new ArrayList<>();
		List<LoadRouteTimetablePort.PathwayEdge> edges = new ArrayList<>();
		List<LoadRouteTimetablePort.RouteEdgeEvidence> evidence = new ArrayList<>();
		for (String stationLine : List.of(
			ORIGIN + ":l1", STAIR_HUB + ":l1", STAIR_HUB + ":l2", STEP_FREE_HUB + ":l1",
			STEP_FREE_HUB + ":l3", MID_HUB + ":l3", MID_HUB + ":l4",
			DESTINATION + ":l2", DESTINATION + ":l4")) {
			String[] parts = stationLine.split(":");
			addAccess(nodes, edges, evidence, parts[0], parts[1], false);
		}
		List<LoadRouteTimetablePort.TransferRule> transfers = new ArrayList<>();
		addTransfer(nodes, edges, evidence, transfers, STAIR_HUB, "l1", "l2", 120, true, "VERIFIED");
		addTransfer(nodes, edges, evidence, transfers, STEP_FREE_HUB, "l1", "l3", 360, false, "VERIFIED");
		addTransfer(nodes, edges, evidence, transfers, MID_HUB, "l3", "l4", 360, false, "VERIFIED");
		return timetable(
			List.of(route("r1", "l1"), route("r2", "l2"), route("r3", "l3"), route("r4", "l4")),
			List.of(trip("t1", "r1"), trip("t2", "r2"), trip("t3", "r3"), trip("t4", "r4")),
			List.of(
				stop("t1", 1, ORIGIN, "l1", 29400),
				stop("t1", 2, STAIR_HUB, "l1", 29700),
				stop("t1", 3, STEP_FREE_HUB, "l1", 30000),
				stop("t2", 1, STAIR_HUB, "l2", 30000),
				stop("t2", 2, DESTINATION, "l2", 30777),
				stop("t3", 1, STEP_FREE_HUB, "l3", 30600),
				stop("t3", 2, MID_HUB, "l3", 30900),
				stop("t4", 1, MID_HUB, "l4", 31500),
				stop("t4", 2, DESTINATION, "l4", 31557)),
			new LoadRouteTimetablePort.RouteAccessData(nodes, edges, transfers, evidence));
	}

	/**
	 * 경고 0개 후보가 없는 시각표 — 30분(계단+만료), 45분(계단), 50분(미검증 환승, 계단 없음).
	 * 경고 개수만 보면 45분이 뽑히지만 무단차인 것은 50분뿐이다.
	 */
	private static RouteTimetable noWarningFreeTimetable() {
		List<LoadRouteTimetablePort.PathwayNode> nodes = new ArrayList<>();
		List<LoadRouteTimetablePort.PathwayEdge> edges = new ArrayList<>();
		List<LoadRouteTimetablePort.RouteEdgeEvidence> evidence = new ArrayList<>();
		for (String stationLine : List.of(
			ORIGIN + ":l1", STALE_HUB + ":l1", STALE_HUB + ":l2", STAIR_HUB + ":l1", STAIR_HUB + ":l3",
			UNVERIFIED_HUB + ":l1", UNVERIFIED_HUB + ":l4",
			DESTINATION + ":l2", DESTINATION + ":l3", DESTINATION + ":l4")) {
			String[] parts = stationLine.split(":");
			addAccess(nodes, edges, evidence, parts[0], parts[1], false);
		}
		List<LoadRouteTimetablePort.TransferRule> transfers = new ArrayList<>();
		addTransfer(nodes, edges, evidence, transfers, STALE_HUB, "l1", "l2", 120, true, "STALE");
		addTransfer(nodes, edges, evidence, transfers, STAIR_HUB, "l1", "l3", 120, true, "VERIFIED");
		return timetable(
			List.of(route("r1", "l1"), route("r2", "l2"), route("r3", "l3"), route("r4", "l4")),
			List.of(trip("t1", "r1"), trip("t2", "r2"), trip("t3", "r3"), trip("t4", "r4")),
			List.of(
				stop("t1", 1, ORIGIN, "l1", 29400),
				stop("t1", 2, STALE_HUB, "l1", 29700),
				stop("t1", 3, STAIR_HUB, "l1", 29760),
				stop("t1", 4, UNVERIFIED_HUB, "l1", 29820),
				stop("t2", 1, STALE_HUB, "l2", 30000),
				stop("t2", 2, DESTINATION, "l2", 30357),
				stop("t3", 1, STAIR_HUB, "l3", 30060),
				stop("t3", 2, DESTINATION, "l3", 31257),
				stop("t4", 1, UNVERIFIED_HUB, "l4", 30420),
				stop("t4", 2, DESTINATION, "l4", 31557)),
			new LoadRouteTimetablePort.RouteAccessData(nodes, edges, transfers, evidence));
	}

	private static LoadRouteTimetablePort.RouteAccessData accessData(boolean includeUnverifiedHub) {
		List<LoadRouteTimetablePort.PathwayNode> nodes = new ArrayList<>();
		List<LoadRouteTimetablePort.PathwayEdge> edges = new ArrayList<>();
		List<LoadRouteTimetablePort.RouteEdgeEvidence> evidence = new ArrayList<>();
		List<String> stationLines = new ArrayList<>(List.of(
			ORIGIN + ":l1", STAIR_HUB + ":l1", STAIR_HUB + ":l2", STEP_FREE_HUB + ":l1",
			STEP_FREE_HUB + ":l3", DESTINATION + ":l2", DESTINATION + ":l3"));
		if (includeUnverifiedHub) {
			stationLines.add(UNVERIFIED_HUB + ":l1");
			stationLines.add(UNVERIFIED_HUB + ":l4");
			stationLines.add(DESTINATION + ":l4");
		}
		for (String stationLine : stationLines) {
			String[] parts = stationLine.split(":");
			addAccess(nodes, edges, evidence, parts[0], parts[1], false);
		}
		List<LoadRouteTimetablePort.TransferRule> transfers = new ArrayList<>();
		addTransfer(nodes, edges, evidence, transfers, STAIR_HUB, "l1", "l2", 120, true, "VERIFIED");
		addTransfer(nodes, edges, evidence, transfers, STEP_FREE_HUB, "l1", "l3", 360, false, "VERIFIED");
		return new LoadRouteTimetablePort.RouteAccessData(nodes, edges, transfers, evidence);
	}

	private static void addAccess(
		List<LoadRouteTimetablePort.PathwayNode> nodes,
		List<LoadRouteTimetablePort.PathwayEdge> edges,
		List<LoadRouteTimetablePort.RouteEdgeEvidence> evidence,
		String station,
		String line,
		boolean entryIncludesStairs
	) {
		String key = station + "-" + line;
		var entry = edge(key + "-entry", 240, 180, entryIncludesStairs, "VERIFIED");
		var exit = edge(key + "-exit", 180, 120, false, "VERIFIED");
		edges.add(entry);
		edges.add(exit);
		nodes.add(new LoadRouteTimetablePort.PathwayNode(entry.fromNodeId(), station, null, "ENTRANCE"));
		nodes.add(new LoadRouteTimetablePort.PathwayNode(entry.toNodeId(), station, line, "PLATFORM"));
		nodes.add(new LoadRouteTimetablePort.PathwayNode(exit.fromNodeId(), station, line, "PLATFORM"));
		nodes.add(new LoadRouteTimetablePort.PathwayNode(exit.toNodeId(), station, null, "EXIT"));
		evidence.add(verifiedEvidence(key + "-entry-evidence", station, line, entry.id(), "ENTRY"));
		evidence.add(verifiedEvidence(key + "-exit-evidence", station, line, exit.id(), "EXIT"));
	}

	private static void addTransfer(
		List<LoadRouteTimetablePort.PathwayNode> nodes,
		List<LoadRouteTimetablePort.PathwayEdge> edges,
		List<LoadRouteTimetablePort.RouteEdgeEvidence> evidence,
		List<LoadRouteTimetablePort.TransferRule> transfers,
		String station,
		String fromLine,
		String toLine,
		int durationSeconds,
		boolean includesStairs,
		String verificationStatus
	) {
		String key = station + "-" + fromLine + "-" + toLine;
		var edge = edge(key + "-transfer", durationSeconds, durationSeconds, includesStairs, verificationStatus);
		edges.add(edge);
		nodes.add(new LoadRouteTimetablePort.PathwayNode(edge.fromNodeId(), station, fromLine, "PLATFORM"));
		nodes.add(new LoadRouteTimetablePort.PathwayNode(edge.toNodeId(), station, toLine, "PLATFORM"));
		evidence.add(verifiedEvidence(key + "-transfer-evidence", station, toLine, edge.id(), "TRANSFER"));
		transfers.add(new LoadRouteTimetablePort.TransferRule(
			key + "-rule", station, fromLine, station, toLine, "IN_STATION", durationSeconds,
			edge.id(), includesStairs ? null : edge.id(), "VERIFIED"));
	}

	private static LoadRouteTimetablePort.PathwayEdge edge(
		String id, int duration, int distance, boolean includesStairs, String verificationStatus
	) {
		return new LoadRouteTimetablePort.PathwayEdge(
			id, id + "-from", id + "-to", duration, distance, false, includesStairs, 100,
			"AVAILABLE", "OFFICIAL_SOURCE", verificationStatus);
	}

	private static LoadRouteTimetablePort.RouteEdgeEvidence verifiedEvidence(
		String id, String station, String line, String edgeId, String edgeType
	) {
		return new LoadRouteTimetablePort.RouteEdgeEvidence(
			id, station, line, edgeId, edgeType, "OFFICIAL_SOURCE", "VERIFIED", true, null);
	}

	private static LoadRouteTimetablePort.TransitRoute route(String id, String lineId) {
		return new LoadRouteTimetablePort.TransitRoute(id, lineId, id, id, id, "Asia/Seoul");
	}

	private static LoadRouteTimetablePort.TransitTrip trip(String id, String routeId) {
		return new LoadRouteTimetablePort.TransitTrip(id, routeId, "daily", id, "0", "LOCAL", 0);
	}

	private static LoadRouteTimetablePort.TransitStopTime stop(
		String tripId, int sequence, String stationId, String lineId, int seconds
	) {
		return new LoadRouteTimetablePort.TransitStopTime(
			tripId, sequence, stationId, lineId, seconds, seconds, 0, 0);
	}
}
