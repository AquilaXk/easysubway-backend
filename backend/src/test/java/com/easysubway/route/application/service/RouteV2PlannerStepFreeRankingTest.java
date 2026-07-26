package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.application.port.in.RouteSearchUseCase;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.RouteObjective;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.RouteTransportScope;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.RouteV2Plan;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.RouteV2Status;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.SearchRouteV2Command;
import com.easysubway.route.application.port.in.SearchInternalRouteCommand;
import com.easysubway.route.application.port.in.SearchRouteCommand;
import com.easysubway.route.application.port.in.SubmitRouteFeedbackCommand;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.domain.ConstraintMode;
import com.easysubway.route.domain.EtaSource;
import com.easysubway.route.domain.InternalRouteResult;
import com.easysubway.route.domain.RouteFeedback;
import com.easysubway.route.domain.RouteRefreshResult;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteStep;
import com.easysubway.route.domain.RouteWarning;
import com.easysubway.route.domain.RouteWarningCode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #2560 — 플래너가 보존한 무단차 대안(#2534)이 응답 랭킹 축약에서 소실되지 않는지 고정한다.
 *
 * <p>플래너 계층 보존은 {@link RouteTimetableRaptorPlannerStepFreeAlternativeTest}가 다루고, 여기서는
 * {@link RouteV2Planner#search}가 내보내는 응답 후보 집합·태그·plan status만 검증한다.
 */
@DisplayName("#2560 PREFER_STEP_FREE 응답 랭킹의 무단차 대안 보존")
class RouteV2PlannerStepFreeRankingTest {

	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 7, 6);
	private static final OffsetDateTime DEPARTURE =
		OffsetDateTime.of(2026, 7, 6, 8, 0, 0, 0, ZoneOffset.ofHours(9));
	private static final String ORIGIN = "origin";
	private static final String STAIR_HUB = "stair-hub";
	private static final String STEP_FREE_HUB = "step-free-hub";
	private static final String UNVERIFIED_HUB = "unverified-hub";
	private static final String DESTINATION = "destination";

	@Test
	@DisplayName("환승 수가 같고 계단 경로가 더 빠르면 무단차 대안을 추가 후보로 남긴다")
	void preservesStepFreeAlternativeWhenObjectiveRepresentativesShareStairRoute() {
		var planner = planner(sameTransferCountTimetable(true, true));

		RouteV2Plan plan = planner.search(command(ConstraintMode.PREFER_STEP_FREE, RouteObjective.FASTEST, 1, 3));

		assertThat(plan.itineraries())
			.extracting(RouteV2PlannerStepFreeRankingTest::transferStationId, RouteSearchResult::objectiveTags)
			.containsExactly(
				tuple(STAIR_HUB, List.of("FASTEST", "FEWEST_TRANSFERS")),
				tuple(STEP_FREE_HUB, List.of("STEP_FREE_PREFERRED")));
		assertThat(warningCodes(plan.itineraries().getLast())).isEmpty();
		assertThat(transferStep(plan.itineraries().getLast()).includesStairs()).isFalse();
		assertThat(transferStep(plan.itineraries().getLast()).stairAccessState()).isEqualTo("STEP_FREE");
	}

	@Test
	@DisplayName("대표가 하나로 합쳐지면 alternativeCount 2에서도 무단차 대안이 남는다")
	void preservesStepFreeAlternativeWhenAlternativeCountIsTwo() {
		var planner = planner(sameTransferCountTimetable(true, true));

		RouteV2Plan plan = planner.search(command(ConstraintMode.PREFER_STEP_FREE, RouteObjective.FASTEST, 1, 2));

		assertThat(plan.itineraries())
			.extracting(RouteV2PlannerStepFreeRankingTest::transferStationId, RouteSearchResult::objectiveTags)
			.containsExactly(
				tuple(STAIR_HUB, List.of("FASTEST", "FEWEST_TRANSFERS")),
				tuple(STEP_FREE_HUB, List.of("STEP_FREE_PREFERRED")));
	}

	@Test
	@DisplayName("alternativeCount 1이면 objective 대표만 남기고 무단차 대안을 덧붙이지 않는다")
	void doesNotExceedAlternativeCountOfOne() {
		var planner = planner(sameTransferCountTimetable(true, true));

		RouteV2Plan plan = planner.search(command(ConstraintMode.PREFER_STEP_FREE, RouteObjective.FASTEST, 1, 1));

		assertThat(plan.itineraries()).singleElement().satisfies(itinerary -> {
			assertThat(transferStationId(itinerary)).isEqualTo(STAIR_HUB);
			assertThat(itinerary.objectiveTags()).containsExactly("FASTEST", "FEWEST_TRANSFERS");
		});
	}

	@Test
	@DisplayName("두 objective 대표가 모두 계단이면 무단차 대안이 세 번째 후보로 붙는다")
	void addsStepFreeAlternativeBesideBothObjectiveRepresentatives() {
		var planner = planner(mixedObjectiveTimetable());

		RouteV2Plan plan = planner.search(command(ConstraintMode.PREFER_STEP_FREE, RouteObjective.FASTEST, 1, 3));

		assertThat(plan.itineraries())
			.hasSize(3)
			.extracting(RouteSearchResult::transferCount, RouteSearchResult::objectiveTags)
			.containsExactly(
				tuple(1, List.of("FASTEST")),
				tuple(0, List.of("FEWEST_TRANSFERS")),
				tuple(1, List.of("STEP_FREE_PREFERRED")));
	}

	@Test
	@DisplayName("objective 대표 두 건이 자리를 다 쓰면 무단차 대안을 덧붙이지 않는다")
	void keepsBothObjectiveRepresentativesWhenAlternativeCountIsTwo() {
		var planner = planner(mixedObjectiveTimetable());

		RouteV2Plan plan = planner.search(command(ConstraintMode.PREFER_STEP_FREE, RouteObjective.FASTEST, 1, 2));

		assertThat(plan.itineraries())
			.extracting(RouteSearchResult::transferCount, RouteSearchResult::objectiveTags)
			.containsExactly(tuple(1, List.of("FASTEST")), tuple(0, List.of("FEWEST_TRANSFERS")));
	}

	@Test
	@DisplayName("FEWEST_TRANSFERS 요청에서도 표시 선두는 요청 objective 대표이고 무단차 대안은 마지막이다")
	void keepsRequestedObjectiveFirstAndStepFreeAlternativeLast() {
		var planner = planner(mixedObjectiveTimetable());

		RouteV2Plan plan = planner.search(
			command(ConstraintMode.PREFER_STEP_FREE, RouteObjective.FEWEST_TRANSFERS, 1, 3));

		assertThat(plan.itineraries())
			.extracting(RouteSearchResult::transferCount, RouteSearchResult::objectiveTags)
			.containsExactly(
				tuple(0, List.of("FEWEST_TRANSFERS")),
				tuple(1, List.of("FASTEST")),
				tuple(1, List.of("STEP_FREE_PREFERRED")));
	}

	@Test
	@DisplayName("대표가 이미 검증된 무단차면 후보를 늘리지 않는다")
	void doesNotAddAlternativeWhenRepresentativeIsAlreadyStepFree() {
		var planner = planner(sameTransferCountTimetable(false, true));

		RouteV2Plan plan = planner.search(command(ConstraintMode.PREFER_STEP_FREE, RouteObjective.FASTEST, 1, 3));

		assertThat(plan.itineraries()).singleElement().satisfies(itinerary -> {
			assertThat(itinerary.objectiveTags()).containsExactly("FASTEST", "FEWEST_TRANSFERS");
			assertThat(warningCodes(itinerary)).isEmpty();
		});
	}

	@Test
	@DisplayName("ALLOW_WITH_WARNINGS는 기존 objective 대표 계약을 그대로 둔다")
	void allowWithWarningsKeepsObjectiveRepresentativesOnly() {
		var planner = planner(sameTransferCountTimetable(true, true));

		RouteV2Plan plan = planner.search(command(ConstraintMode.ALLOW_WITH_WARNINGS, RouteObjective.FASTEST, 1, 3));

		assertThat(plan.itineraries()).singleElement().satisfies(itinerary -> {
			assertThat(transferStationId(itinerary)).isEqualTo(STAIR_HUB);
			assertThat(itinerary.objectiveTags()).containsExactly("FASTEST", "FEWEST_TRANSFERS");
		});
	}

	@Test
	@DisplayName("STRICT_STEP_FREE는 계단 경로가 차단되므로 추가 태그 없이 무단차 대표만 남는다")
	void strictStepFreeKeepsObjectiveRepresentativesOnly() {
		var planner = planner(sameTransferCountTimetable(true, true));

		RouteV2Plan plan = planner.search(command(ConstraintMode.STRICT_STEP_FREE, RouteObjective.FASTEST, 1, 3));

		assertThat(plan.itineraries()).singleElement().satisfies(itinerary -> {
			assertThat(transferStationId(itinerary)).isEqualTo(STEP_FREE_HUB);
			assertThat(itinerary.objectiveTags()).containsExactly("FASTEST", "FEWEST_TRANSFERS");
		});
	}

	@Test
	@DisplayName("prod 완결성 계약을 못 채우는 무단차 후보는 편입하지 않아 plan 전체가 거부되지 않는다")
	void doesNotAppendStepFreeCandidateThatBreaksProductionCompletenessContract() {
		var planner = planner(sameTransferCountTimetable(true, false));

		RouteV2Plan plan = planner.search(command(ConstraintMode.PREFER_STEP_FREE, RouteObjective.FASTEST, 1, 3));

		// 무단차 후보는 두 번째 ride OD의 공식 요금 행이 없어 officialFare가 null이다. 편입하면
		// requireUsablePlan()이 plan 전체를 503으로 거부하므로 대표만 남고 응답은 그대로 성립해야 한다.
		assertThat(plan.itineraries())
			.extracting(RouteV2PlannerStepFreeRankingTest::transferStationId, RouteSearchResult::objectiveTags)
			.containsExactly(tuple(STAIR_HUB, List.of("FASTEST", "FEWEST_TRANSFERS")));
		assertThat(plan.statuses()).contains(RouteV2Status.FOUND);
		assertThat(plan.itineraries()).allSatisfy(itinerary ->
			assertThat(ProductionRouteV2Support.incompleteFoundItinerary(itinerary)).isFalse());
	}

	@Test
	@DisplayName("편입된 후보를 포함해 모든 FOUND itinerary가 prod 완결성 계약을 만족한다")
	void appendedItinerariesSatisfyProductionCompletenessContract() {
		var planner = planner(mixedObjectiveTimetable());

		RouteV2Plan plan = planner.search(command(ConstraintMode.PREFER_STEP_FREE, RouteObjective.FASTEST, 1, 3));

		assertThat(plan.itineraries()).hasSize(3).allSatisfy(itinerary -> {
			assertThat(itinerary.objectiveTags()).isNotEmpty();
			assertThat(itinerary.officialFare()).isNotNull();
			assertThat(ProductionRouteV2Support.incompleteFoundItinerary(itinerary)).isFalse();
		});
	}

	@Test
	@DisplayName("대표의 접근 동선이 미검증(UNKNOWN)이면 검증된 무단차 대안을 남긴다")
	void preservesStepFreeAlternativeWhenRepresentativeAccessIsUnverified() {
		var planner = planner(unverifiedRepresentativeTimetable());

		RouteV2Plan plan = planner.search(command(ConstraintMode.PREFER_STEP_FREE, RouteObjective.FASTEST, 1, 3));

		// 대표는 계단이 없는 것이 아니라 "확인되지 않은" 경로다. 무단차가 확인된 경로가 따로 있으면 남긴다.
		assertThat(plan.itineraries())
			.extracting(RouteV2PlannerStepFreeRankingTest::transferStationId, RouteSearchResult::objectiveTags)
			.containsExactly(
				tuple(UNVERIFIED_HUB, List.of("FASTEST", "FEWEST_TRANSFERS")),
				tuple(STEP_FREE_HUB, List.of("STEP_FREE_PREFERRED")));
		assertThat(transferStep(plan.itineraries().getFirst()).stairAccessState()).isEqualTo("UNKNOWN");
		assertThat(transferStep(plan.itineraries().getFirst()).requiresAccessibilityCheck()).isTrue();
	}

	@Test
	@DisplayName("미검증(UNKNOWN) 후보에는 STEP_FREE_PREFERRED를 붙이지 않는다")
	void doesNotTagUnverifiedCandidateAsStepFreePreferred() {
		var planner = planner(unknownOnlyAlternativeTimetable());

		RouteV2Plan plan = planner.search(command(ConstraintMode.PREFER_STEP_FREE, RouteObjective.FASTEST, 1, 3));

		assertThat(plan.itineraries())
			.extracting(RouteSearchResult::objectiveTags)
			.allSatisfy(tags -> assertThat(tags).doesNotContain("STEP_FREE_PREFERRED"));
		assertThat(plan.itineraries().getFirst().objectiveTags())
			.containsExactly("FASTEST", "FEWEST_TRANSFERS");
	}

	@Test
	@DisplayName("추가 후보만 PLANNED ETA면 plan status에 REALTIME_UNAVAILABLE_PLANNED_USED가 더해진다")
	void appendedStepFreeAlternativeContributesToPlanStatuses() {
		var timetable = sameTransferCountTimetable(true, true);
		// 대표(계단 경로)가 타는 t1·t2에만 실시간 갱신을 준다. 무단차 대안이 타는 t3은 PLANNED로 남는다.
		var planner = new RouteV2Planner(realtimeFor(Set.of("t1", "t2")), () -> timetable);
		var representativeOnlyPlanner = new RouteV2Planner(realtimeFor(Set.of("t1", "t2")), () -> timetable);

		RouteV2Plan plan = planner.search(
			realtimeCommand(ConstraintMode.PREFER_STEP_FREE, RouteObjective.FASTEST, 1, 3));
		RouteV2Plan representativeOnly = representativeOnlyPlanner.search(
			realtimeCommand(ConstraintMode.PREFER_STEP_FREE, RouteObjective.FASTEST, 1, 1));

		// 대표만 담긴 응답은 ride가 전부 실시간이라 planned-used status가 없다.
		assertThat(representativeOnly.itineraries()).hasSize(1);
		assertThat(representativeOnly.itineraries().getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.allSatisfy(step -> assertThat(step.timeSource()).isEqualTo(EtaSource.REALTIME.name()));
		assertThat(representativeOnly.statuses()).containsExactly(RouteV2Status.FOUND);

		// 무단차 대안이 편입되면 그 후보의 PLANNED ride가 plan 수준 status를 하나 더 만든다.
		assertThat(plan.itineraries()).hasSize(2);
		assertThat(plan.itineraries().getLast().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.anySatisfy(step -> assertThat(step.timeSource()).isEqualTo(EtaSource.PLANNED.name()));
		assertThat(plan.statuses())
			.containsExactly(RouteV2Status.FOUND, RouteV2Status.REALTIME_UNAVAILABLE_PLANNED_USED);
	}

	private static RouteV2Planner planner(RouteTimetable timetable) {
		return new RouteV2Planner(new LegacySearchMustNotBeCalled(), () -> timetable);
	}

	private static SearchRouteV2Command command(
		ConstraintMode constraintMode, RouteObjective objective, int maxTransfers, int alternativeCount
	) {
		return command(constraintMode, objective, maxTransfers, alternativeCount, false);
	}

	private static SearchRouteV2Command realtimeCommand(
		ConstraintMode constraintMode, RouteObjective objective, int maxTransfers, int alternativeCount
	) {
		return command(constraintMode, objective, maxTransfers, alternativeCount, true);
	}

	private static SearchRouteV2Command command(
		ConstraintMode constraintMode,
		RouteObjective objective,
		int maxTransfers,
		int alternativeCount,
		boolean useRealtime
	) {
		return new SearchRouteV2Command(
			ORIGIN,
			DESTINATION,
			DEPARTURE,
			MobilityType.SENIOR,
			null,
			constraintMode,
			useRealtime,
			maxTransfers,
			alternativeCount,
			RouteTransportScope.SUBWAY_AND_ITX_CHEONGCHUN,
			objective
		);
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

	/**
	 * 환승 수가 1로 같은 두 경로를 만든다. {@code fastRouteIncludesStairs}가 true면 이슈 시나리오
	 * ({@code stair-hub} 경유가 더 빠른 계단 경로, {@code step-free-hub} 경유가 더 느린 무단차 경로)이고,
	 * false면 두 경로 모두 검증된 무단차라 추가 후보가 필요 없는 대조군이다.
	 * {@code stepFreeRouteHasFare}가 false면 무단차 경로 두 번째 ride OD의 공식 요금 행을 빼서
	 * {@code officialFare}가 null인(=prod 완결성 계약 미충족) 후보를 만든다.
	 */
	private static RouteTimetable sameTransferCountTimetable(
		boolean fastRouteIncludesStairs, boolean stepFreeRouteHasFare
	) {
		List<LoadRouteTimetablePort.PathwayNode> nodes = new ArrayList<>();
		List<LoadRouteTimetablePort.PathwayEdge> edges = new ArrayList<>();
		List<LoadRouteTimetablePort.RouteEdgeEvidence> evidence = new ArrayList<>();
		addStationLineAccess(nodes, edges, evidence, List.of(
			ORIGIN + ":l1", STAIR_HUB + ":l1", STAIR_HUB + ":l2", STEP_FREE_HUB + ":l1",
			STEP_FREE_HUB + ":l3", DESTINATION + ":l2", DESTINATION + ":l3"));
		List<LoadRouteTimetablePort.TransferRule> transfers = new ArrayList<>();
		addTransfer(nodes, edges, evidence, transfers, STAIR_HUB, "l1", "l2", 120, fastRouteIncludesStairs);
		addTransfer(nodes, edges, evidence, transfers, STEP_FREE_HUB, "l1", "l3", 360, false);
		List<LoadRouteTimetablePort.OfficialFare> fares = new ArrayList<>(List.of(
			fare("t1", ORIGIN, STAIR_HUB),
			fare("t1", ORIGIN, STEP_FREE_HUB),
			fare("t2", STAIR_HUB, DESTINATION)));
		if (stepFreeRouteHasFare) {
			fares.add(fare("t3", STEP_FREE_HUB, DESTINATION));
		}
		return timetable(
			List.of(route("r1", "l1"), route("r2", "l2"), route("r3", "l3")),
			List.of(trip("t1", "r1"), trip("t2", "r2"), trip("t3", "r3")),
			List.of(
				stop("t1", 1, ORIGIN, "l1", 29400),
				stop("t1", 2, STAIR_HUB, "l1", 29700),
				stop("t1", 3, STEP_FREE_HUB, "l1", 30000),
				stop("t2", 1, STAIR_HUB, "l2", 30000),
				stop("t2", 2, DESTINATION, "l2", 30777),
				stop("t3", 1, STEP_FREE_HUB, "l3", 30600),
				stop("t3", 2, DESTINATION, "l3", 30957)),
			fares,
			new LoadRouteTimetablePort.RouteAccessData(nodes, edges, transfers, evidence));
	}

	/**
	 * 두 objective 대표가 서로 다른 경로가 되도록 계단 진입 직통({@code ld}, 환승 0회)을 더한 시각표다.
	 * 최속(계단 환승)·최소 환승(계단 진입 직통) 대표가 모두 계단을 포함하고 무단차 대안은 셋째 후보로 남는다.
	 */
	private static RouteTimetable mixedObjectiveTimetable() {
		List<LoadRouteTimetablePort.PathwayNode> nodes = new ArrayList<>();
		List<LoadRouteTimetablePort.PathwayEdge> edges = new ArrayList<>();
		List<LoadRouteTimetablePort.RouteEdgeEvidence> evidence = new ArrayList<>();
		addStationLineAccess(nodes, edges, evidence, List.of(
			ORIGIN + ":l1", STAIR_HUB + ":l1", STAIR_HUB + ":l2", STEP_FREE_HUB + ":l1",
			STEP_FREE_HUB + ":l3", DESTINATION + ":l2", DESTINATION + ":l3", DESTINATION + ":ld"));
		addAccess(nodes, edges, evidence, ORIGIN, "ld", true);
		List<LoadRouteTimetablePort.TransferRule> transfers = new ArrayList<>();
		addTransfer(nodes, edges, evidence, transfers, STAIR_HUB, "l1", "l2", 120, true);
		addTransfer(nodes, edges, evidence, transfers, STEP_FREE_HUB, "l1", "l3", 360, false);
		return timetable(
			List.of(route("r1", "l1"), route("r2", "l2"), route("r3", "l3"), route("rd", "ld")),
			List.of(trip("t1", "r1"), trip("t2", "r2"), trip("t3", "r3"), trip("td", "rd")),
			List.of(
				stop("t1", 1, ORIGIN, "l1", 29400),
				stop("t1", 2, STAIR_HUB, "l1", 29700),
				stop("t1", 3, STEP_FREE_HUB, "l1", 30000),
				stop("t2", 1, STAIR_HUB, "l2", 30000),
				stop("t2", 2, DESTINATION, "l2", 30777),
				stop("t3", 1, STEP_FREE_HUB, "l3", 30600),
				stop("t3", 2, DESTINATION, "l3", 30957),
				stop("td", 1, ORIGIN, "ld", 29400),
				stop("td", 2, DESTINATION, "ld", 31500)),
			List.of(
				fare("t1", ORIGIN, STAIR_HUB),
				fare("t1", ORIGIN, STEP_FREE_HUB),
				fare("t2", STAIR_HUB, DESTINATION),
				fare("t3", STEP_FREE_HUB, DESTINATION),
				fare("td", ORIGIN, DESTINATION)),
			new LoadRouteTimetablePort.RouteAccessData(nodes, edges, transfers, evidence));
	}

	/**
	 * 최속 경로가 환승 규칙 없는 허브를 지나 접근 동선이 미검증(UNKNOWN)인 시각표다. 계단은 없지만
	 * 무단차로 확인된 것도 아니므로, 검증된 무단차 대안({@code step-free-hub})을 남겨야 한다.
	 */
	private static RouteTimetable unverifiedRepresentativeTimetable() {
		List<LoadRouteTimetablePort.PathwayNode> nodes = new ArrayList<>();
		List<LoadRouteTimetablePort.PathwayEdge> edges = new ArrayList<>();
		List<LoadRouteTimetablePort.RouteEdgeEvidence> evidence = new ArrayList<>();
		addStationLineAccess(nodes, edges, evidence, List.of(
			ORIGIN + ":l1", UNVERIFIED_HUB + ":l1", UNVERIFIED_HUB + ":l4", STEP_FREE_HUB + ":l1",
			STEP_FREE_HUB + ":l3", DESTINATION + ":l3", DESTINATION + ":l4"));
		List<LoadRouteTimetablePort.TransferRule> transfers = new ArrayList<>();
		addTransfer(nodes, edges, evidence, transfers, STEP_FREE_HUB, "l1", "l3", 360, false);
		return timetable(
			List.of(route("r1", "l1"), route("r3", "l3"), route("r4", "l4")),
			List.of(trip("t1", "r1"), trip("t3", "r3"), trip("t4", "r4")),
			List.of(
				stop("t1", 1, ORIGIN, "l1", 29400),
				stop("t1", 2, UNVERIFIED_HUB, "l1", 29700),
				stop("t1", 3, STEP_FREE_HUB, "l1", 30000),
				stop("t4", 1, UNVERIFIED_HUB, "l4", 30480),
				stop("t4", 2, DESTINATION, "l4", 30777),
				stop("t3", 1, STEP_FREE_HUB, "l3", 30600),
				stop("t3", 2, DESTINATION, "l3", 30957)),
			List.of(
				fare("t1", ORIGIN, UNVERIFIED_HUB),
				fare("t1", ORIGIN, STEP_FREE_HUB),
				fare("t4", UNVERIFIED_HUB, DESTINATION),
				fare("t3", STEP_FREE_HUB, DESTINATION)),
			new LoadRouteTimetablePort.RouteAccessData(nodes, edges, transfers, evidence));
	}

	/**
	 * 계단 경로(최속)와 미검증 경로만 있는 시각표다. 검증된 무단차 후보가 없으므로 추가 후보를 만들지 않는다.
	 */
	private static RouteTimetable unknownOnlyAlternativeTimetable() {
		List<LoadRouteTimetablePort.PathwayNode> nodes = new ArrayList<>();
		List<LoadRouteTimetablePort.PathwayEdge> edges = new ArrayList<>();
		List<LoadRouteTimetablePort.RouteEdgeEvidence> evidence = new ArrayList<>();
		addStationLineAccess(nodes, edges, evidence, List.of(
			ORIGIN + ":l1", STAIR_HUB + ":l1", STAIR_HUB + ":l2", UNVERIFIED_HUB + ":l1",
			UNVERIFIED_HUB + ":l4", DESTINATION + ":l2", DESTINATION + ":l4"));
		List<LoadRouteTimetablePort.TransferRule> transfers = new ArrayList<>();
		addTransfer(nodes, edges, evidence, transfers, STAIR_HUB, "l1", "l2", 120, true);
		return timetable(
			List.of(route("r1", "l1"), route("r2", "l2"), route("r4", "l4")),
			List.of(trip("t1", "r1"), trip("t2", "r2"), trip("t4", "r4")),
			List.of(
				stop("t1", 1, ORIGIN, "l1", 29400),
				stop("t1", 2, STAIR_HUB, "l1", 29700),
				stop("t1", 3, UNVERIFIED_HUB, "l1", 30000),
				stop("t2", 1, STAIR_HUB, "l2", 30000),
				stop("t2", 2, DESTINATION, "l2", 30777),
				stop("t4", 1, UNVERIFIED_HUB, "l4", 30600),
				stop("t4", 2, DESTINATION, "l4", 30957)),
			List.of(
				fare("t1", ORIGIN, STAIR_HUB),
				fare("t1", ORIGIN, UNVERIFIED_HUB),
				fare("t2", STAIR_HUB, DESTINATION),
				fare("t4", UNVERIFIED_HUB, DESTINATION)),
			new LoadRouteTimetablePort.RouteAccessData(nodes, edges, transfers, evidence));
	}

	private static RouteTimetable timetable(
		List<LoadRouteTimetablePort.TransitRoute> routes,
		List<LoadRouteTimetablePort.TransitTrip> trips,
		List<LoadRouteTimetablePort.TransitStopTime> stops,
		List<LoadRouteTimetablePort.OfficialFare> fares,
		LoadRouteTimetablePort.RouteAccessData accessData
	) {
		var daily = new LoadRouteTimetablePort.ServiceCalendar(
			"daily", true, true, true, true, true, true, true,
			SERVICE_DATE, SERVICE_DATE.plusDays(7), "Asia/Seoul");
		return new RouteTimetable(
			List.of(daily), List.of(), routes, trips, stops, List.of(), List.copyOf(fares), null, accessData);
	}

	private static LoadRouteTimetablePort.OfficialFare fare(String tripId, String from, String to) {
		return new LoadRouteTimetablePort.OfficialFare(
			tripId, from, to, 1_250, "KRW", "official", "snapshot");
	}

	private static void addStationLineAccess(
		List<LoadRouteTimetablePort.PathwayNode> nodes,
		List<LoadRouteTimetablePort.PathwayEdge> edges,
		List<LoadRouteTimetablePort.RouteEdgeEvidence> evidence,
		List<String> stationLines
	) {
		for (String stationLine : stationLines) {
			String[] parts = stationLine.split(":");
			addAccess(nodes, edges, evidence, parts[0], parts[1], false);
		}
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
		var entry = edge(key + "-entry", 240, 180, entryIncludesStairs);
		var exit = edge(key + "-exit", 180, 120, false);
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
		boolean includesStairs
	) {
		String key = station + "-" + fromLine + "-" + toLine;
		var edge = edge(key + "-transfer", durationSeconds, durationSeconds, includesStairs);
		edges.add(edge);
		nodes.add(new LoadRouteTimetablePort.PathwayNode(edge.fromNodeId(), station, fromLine, "PLATFORM"));
		nodes.add(new LoadRouteTimetablePort.PathwayNode(edge.toNodeId(), station, toLine, "PLATFORM"));
		evidence.add(verifiedEvidence(key + "-transfer-evidence", station, toLine, edge.id(), "TRANSFER"));
		transfers.add(new LoadRouteTimetablePort.TransferRule(
			key + "-rule", station, fromLine, station, toLine, "IN_STATION", durationSeconds,
			edge.id(), includesStairs ? null : edge.id(), "VERIFIED"));
	}

	private static LoadRouteTimetablePort.PathwayEdge edge(
		String id, int duration, int distance, boolean includesStairs
	) {
		return new LoadRouteTimetablePort.PathwayEdge(
			id, id + "-from", id + "-to", duration, distance, false, includesStairs, 100,
			"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED");
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

	private static RouteSearchUseCase realtimeFor(Set<String> realtimeTripIds) {
		return new LegacySearchMustNotBeCalled() {
			@Override
			public TimetableRealtimeUpdates resolveTimetableRealtime(List<TimetableRealtimeQuery> queries) {
				return new TimetableRealtimeUpdates(
					"overlay-1",
					true,
					realtimeTripIds.stream()
						.sorted()
						.map(tripId -> new TimetableRealtimeUpdate(
							tripId, 0, 0, false, "provider-snapshot", Instant.parse("2026-07-06T08:00:00Z")))
						.toList(),
					null
				);
			}
		};
	}

	private static class LegacySearchMustNotBeCalled implements RouteSearchUseCase {

		@Override
		public RouteSearchResult searchRoute(SearchRouteCommand command) {
			throw new AssertionError("RouteV2Planner must not delegate timetable-backed search to legacy graph search");
		}

		@Override
		public List<RouteSearchResult> searchRouteAlternatives(SearchRouteCommand command, int alternativeCount) {
			throw new AssertionError("RouteV2Planner must not delegate timetable-backed search to legacy graph search");
		}

		@Override
		public InternalRouteResult searchInternalRoute(SearchInternalRouteCommand command) {
			throw new AssertionError("RouteV2Planner must not call internal route search");
		}

		@Override
		public RouteSearchResult getRouteSearch(String routeSearchId) {
			throw new AssertionError("RouteV2Planner must not load legacy route search results");
		}

		@Override
		public RouteRefreshResult refreshRoute(String routeSearchId) {
			throw new AssertionError("RouteV2Planner must not refresh legacy route search results");
		}

		@Override
		public RouteFeedback submitRouteFeedback(SubmitRouteFeedbackCommand command) {
			throw new AssertionError("RouteV2Planner must not submit feedback during search");
		}
	}
}
