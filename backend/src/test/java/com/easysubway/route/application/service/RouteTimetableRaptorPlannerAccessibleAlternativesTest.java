package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.easysubway.journey.application.JourneyAlternatives;
import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.application.ServiceDayResolver;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.JourneyAccessProjection;
import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.JourneyItinerary;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #469: 출발 시각 고정 검색의 계단 없는 대안 동선 보존과 결과 구성(빠른 경로·환승 적은 경로·계단 없는 경로).
 *
 * <p>고정 시간표({@link #timetable})의 여정은 다음과 같다(08:00 준비, 표준 걸음).</p>
 * <ul>
 *   <li>C: o→a→b→d 세 번 승차, a·b 계단 환승, 08:26:40 도착</li>
 *   <li>A: o→h→d 두 번 승차, h 계단 동선(30 m), 08:28 도착</li>
 *   <li>B: o→h→d 두 번 승차, h 계단 없는 동선(600 m, 엘리베이터), 다음 열차로 08:47 도착</li>
 *   <li>D: o→d 직행, 09:10 도착</li>
 * </ul>
 * <p>h의 L1→L2 환승에는 짧은 계단 동선과 긴 계단 없는 동선이 함께 있다. 표준 프로필은 짧은 동선을 고르므로 B는
 * 대안 동선을 볼 때만 찾을 수 있다.</p>
 */
@DisplayName("#469 접근성 다기준 경로 대안: 계단 없는 대안 동선과 결과 구성")
class RouteTimetableRaptorPlannerAccessibleAlternativesTest {

	static final LocalDate SERVICE_DATE = LocalDate.of(2026, 7, 6);
	static final Instant READY_AT = SERVICE_DATE.atTime(LocalTime.of(8, 0)).atZone(ServiceDayResolver.ZONE).toInstant();
	static final String ORIGIN = "o";
	static final String DESTINATION = "d";
	private static final String REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";

	@Test
	@DisplayName("R1 표준 프로필도 같은 역의 계단 없는 대안 동선 여정을 결과에 남긴다")
	void standardProfileKeepsStepFreePathwayAlternative() {
		List<JourneyItinerary> results = plan(JourneyRequest.MobilityProfile.STANDARD,
			JourneyRequest.ConstraintMode.NONE, 1, 3, false);

		assertThat(results)
			.extracting(RouteTimetableRaptorPlannerAccessibleAlternativesTest::arrival,
				itinerary -> itinerary.metrics().transfersUsed(),
				RouteTimetableRaptorPlannerAccessibleAlternativesTest::hasStairs)
			.containsExactly(
				tuple("08:28", 1, true),
				tuple("08:47", 1, false));
	}

	@Test
	@DisplayName("R2 무단차 선호·환승 3·대안 3에서 파레토 여정이 4개여도 결과는 3개이고 빠른·계단 없는·환승 적은 여정을 담는다")
	void preferStepFreeComposesWithinAlternativeCount() {
		List<JourneyItinerary> results = plan(JourneyRequest.MobilityProfile.STEP_FREE,
			JourneyRequest.ConstraintMode.NONE, 3, 3, true);

		assertThat(results)
			.extracting(RouteTimetableRaptorPlannerAccessibleAlternativesTest::arrival,
				itinerary -> itinerary.metrics().transfersUsed(),
				RouteTimetableRaptorPlannerAccessibleAlternativesTest::hasStairs)
			.containsExactly(
				tuple("08:26", 2, true),
				tuple("08:47", 1, false),
				tuple("09:10", 0, false));
	}

	@Test
	@DisplayName("R2b 표준 프로필·대안 3은 도착 순 절단 대신 환승 적은 직행과 계단 없는 여정에 자리를 준다")
	void standardProfileReservesFewestTransfersAndStairFreeSlots() {
		List<JourneyItinerary> results = plan(JourneyRequest.MobilityProfile.STANDARD,
			JourneyRequest.ConstraintMode.NONE, 2, 3, true);

		assertThat(results)
			.extracting(RouteTimetableRaptorPlannerAccessibleAlternativesTest::arrival,
				itinerary -> itinerary.metrics().transfersUsed(),
				RouteTimetableRaptorPlannerAccessibleAlternativesTest::hasStairs)
			.containsExactly(
				tuple("08:26", 2, true),
				tuple("08:47", 1, false),
				tuple("09:10", 0, false));
	}

	@Test
	@DisplayName("R3 대안 2개 표준 프로필에서 빠른 여정이 환승도 가장 적으면 둘째 자리는 계단 없는 여정이다")
	void secondSlotIsStairFreeWhenFastestAlsoHasFewestTransfers() {
		List<JourneyItinerary> results = plan(JourneyRequest.MobilityProfile.STANDARD,
			JourneyRequest.ConstraintMode.NONE, 1, 2, false);

		assertThat(results)
			.extracting(RouteTimetableRaptorPlannerAccessibleAlternativesTest::arrival,
				RouteTimetableRaptorPlannerAccessibleAlternativesTest::hasStairs)
			.containsExactly(tuple("08:28", true), tuple("08:47", false));
	}

	@Test
	@DisplayName("대안 1개면 가장 빠른 여정 하나만 낸다")
	void singleAlternativeIsTheFastest() {
		List<JourneyItinerary> results = plan(JourneyRequest.MobilityProfile.STANDARD,
			JourneyRequest.ConstraintMode.NONE, 2, 1, true);

		assertThat(results)
			.extracting(RouteTimetableRaptorPlannerAccessibleAlternativesTest::arrival,
				itinerary -> itinerary.metrics().transfersUsed())
			.containsExactly(tuple("08:26", 2));
	}

	@Test
	@DisplayName("엄격 무단차는 계단 동선을 쓰지 않으므로 계단 여정이 없다")
	void strictStepFreeHasNoStairJourney() {
		List<JourneyItinerary> results = plan(JourneyRequest.MobilityProfile.STEP_FREE,
			JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE, 3, 3, true);

		assertThat(results)
			.extracting(RouteTimetableRaptorPlannerAccessibleAlternativesTest::arrival,
				RouteTimetableRaptorPlannerAccessibleAlternativesTest::hasStairs)
			.containsExactly(tuple("08:47", false), tuple("09:10", false));
	}

	@Test
	@DisplayName("직행 하나가 환승 적은 자리와 계단 없는 자리를 함께 차지하면 남은 자리는 도착 순 파레토 여정으로 채운다")
	void sharedCategoryLeavesRemainingSlotToArrivalOrder() {
		List<JourneyItinerary> results = new RouteTimetableRaptorPlanner()
			.journeyItineraries(query(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 2, 3),
				timetable(true, false))
			.itineraries();

		assertThat(results)
			.extracting(RouteTimetableRaptorPlannerAccessibleAlternativesTest::arrival,
				itinerary -> itinerary.metrics().transfersUsed(),
				RouteTimetableRaptorPlannerAccessibleAlternativesTest::hasStairs)
			.containsExactly(
				tuple("08:26", 2, true),
				tuple("08:28", 1, true),
				tuple("09:10", 0, false));
	}

	@Test
	@DisplayName("계단 없는 여정이 없으면 계단 없는 자리를 만들지 않는다")
	void noStairFreeJourneyLeavesThatCategoryEmpty() {
		List<JourneyItinerary> results = new RouteTimetableRaptorPlanner()
			.journeyItineraries(query(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 1, 3),
				timetable(false, false))
			.itineraries();

		assertThat(results)
			.extracting(RouteTimetableRaptorPlannerAccessibleAlternativesTest::arrival,
				RouteTimetableRaptorPlannerAccessibleAlternativesTest::hasStairs)
			.containsExactly(tuple("08:28", true));
	}

	@Test
	@DisplayName("#469 여정마다 대표 묶음을 붙이고 계단 없는 여정이 있으면 INCLUDED다")
	void tagsCategoriesAndReportsIncluded() {
		var plan = new RouteTimetableRaptorPlanner().journeyItineraries(
			query(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 2, 3), timetable(true, true));

		assertThat(plan.itineraries())
			.extracting(RouteTimetableRaptorPlannerAccessibleAlternativesTest::arrival, JourneyItinerary::alternativeCategories)
			.containsExactly(
				tuple("08:26", java.util.Set.of(JourneyAlternatives.Category.FASTEST)),
				tuple("08:47", java.util.Set.of(JourneyAlternatives.Category.STAIR_FREE)),
				tuple("09:10", java.util.Set.of(JourneyAlternatives.Category.FEWEST_TRANSFERS)));
		assertThat(plan.stairFreeStatus()).isEqualTo(JourneyAlternatives.StairFreeStatus.INCLUDED);
	}

	@Test
	@DisplayName("#469 계단 없는 여정을 찾았지만 대안 1개라 자리가 없으면 OMITTED다")
	void reportsOmittedWhenStairFreeJourneyHasNoSlot() {
		var plan = new RouteTimetableRaptorPlanner().journeyItineraries(
			query(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 2, 1), timetable(true, true));

		assertThat(plan.itineraries()).extracting(JourneyItinerary::alternativeCategories)
			.containsExactly(java.util.Set.of(JourneyAlternatives.Category.FASTEST));
		assertThat(plan.stairFreeStatus()).isEqualTo(JourneyAlternatives.StairFreeStatus.OMITTED);
	}

	@Test
	@DisplayName("#469 계단 동선만 있는 환승이면 NOT_FOUND다")
	void reportsNotFoundWhenOnlyStairPathwayExists() {
		var plan = new RouteTimetableRaptorPlanner().journeyItineraries(
			query(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 1, 3),
			timetable(false, StepFreePathway.NONE));

		assertThat(plan.itineraries()).extracting(RouteTimetableRaptorPlannerAccessibleAlternativesTest::hasStairs)
			.containsExactly(true);
		assertThat(plan.stairFreeStatus()).isEqualTo(JourneyAlternatives.StairFreeStatus.NOT_FOUND);
	}

	@Test
	@DisplayName("#469 계단 없는 동선의 근거가 검증되지 않았으면 쓰지 않고 UNDETERMINED로 드러낸다")
	void reportsUndeterminedWhenStepFreePathwayIsUnverified() {
		var plan = new RouteTimetableRaptorPlanner().journeyItineraries(
			query(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 1, 3),
			timetable(false, StepFreePathway.UNVERIFIED));

		assertThat(plan.itineraries())
			.extracting(RouteTimetableRaptorPlannerAccessibleAlternativesTest::arrival,
				RouteTimetableRaptorPlannerAccessibleAlternativesTest::hasStairs,
				JourneyItinerary::unconfirmedStairFreeTransfer)
			.containsExactly(tuple("08:28", true, true));
		assertThat(plan.stairFreeStatus()).isEqualTo(JourneyAlternatives.StairFreeStatus.UNDETERMINED);
	}

	@Test
	@DisplayName("#469 엄격 무단차는 근거 미검증 계단 없는 동선을 쓰지 않아 경로가 없다")
	void strictStepFreeDoesNotUseUnverifiedPathway() {
		var plan = new RouteTimetableRaptorPlanner().journeyItineraries(
			query(JourneyRequest.MobilityProfile.STEP_FREE, JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE, 1, 3),
			timetable(false, StepFreePathway.UNVERIFIED));

		assertThat(plan.itineraries()).isEmpty();
		assertThat(plan.stairFreeStatus()).isNull();
	}

	@Test
	@DisplayName("#469 F1 계단 상태가 UNKNOWN인 환승 동선을 지나는 여정은 계단 없음·STAIR_FREE·INCLUDED가 아니다")
	void unknownStairStatePathwayIsNeverStairFree() {
		for (String state : java.util.Arrays.asList("UNKNOWN", null)) {
			var plan = new RouteTimetableRaptorPlanner().journeyItineraries(
				query(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 1, 3),
				unconfirmedStairStateTimetable(state));

			assertThat(plan.itineraries()).as("stair state %s", state).isNotEmpty().allSatisfy(itinerary -> {
				assertThat(itinerary.stairFree()).isFalse();
				assertThat(itinerary.alternativeCategories()).doesNotContain(JourneyAlternatives.Category.STAIR_FREE);
				assertThat(itinerary.metrics().accessibilityBurden()).isEqualTo(1);
			});
			assertThat(plan.stairFreeStatus()).as("stair state %s", state)
				.isEqualTo(JourneyAlternatives.StairFreeStatus.UNDETERMINED);
		}
	}

	@Test
	@DisplayName("#469 F1 엄격 무단차는 계단 상태가 확정되지 않은 동선을 쓰지 않는다")
	void strictStepFreeDoesNotUseUnconfirmedStairState() {
		for (String state : java.util.Arrays.asList("UNKNOWN", null)) {
			var plan = new RouteTimetableRaptorPlanner().journeyItineraries(
				query(JourneyRequest.MobilityProfile.STEP_FREE, JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE, 1, 3),
				unconfirmedStairStateTimetable(state));

			assertThat(plan.itineraries()).as("stair state %s", state).isEmpty();
		}
	}

	/**
	 * #469 F1: seq126 번들과 같은 환승 동선. h의 L1→L2는 동선 하나뿐이고 {@code includesStairs=false}, 계단 상태는
	 * {@code stairAccessState}(UNKNOWN 또는 필드 없음)다. 검증 상태는 VERIFIED이고 번들 컴파일러처럼 엄격 무단차 동선으로도
	 * 지정한다. 08:28 도착 여정 하나가 있다.
	 */
	static RouteTimetable unconfirmedStairStateTimetable(String stairAccessState) {
		return unconfirmedStairStateTimetable(stairAccessState, SERVICE_DATE);
	}

	static RouteTimetable unconfirmedStairStateTimetable(String stairAccessState, LocalDate serviceDate) {
		var edge = new LoadRouteTimetablePort.PathwayEdge("e-h-transfer", "p-h-L1", "p-h-L2", 60, 30, false, false, 100,
			"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState(stairAccessState);
		var access = new LoadRouteTimetablePort.RouteAccessData(
			List.of(new LoadRouteTimetablePort.PathwayNode("p-h-L1", "h", "L1", "PLATFORM"),
				new LoadRouteTimetablePort.PathwayNode("p-h-L2", "h", "L2", "PLATFORM")),
			List.of(edge),
			List.of(new LoadRouteTimetablePort.TransferRule("rule-h", "h", "L1", "h", "L2", "IN_STATION", 60,
				edge.id(), edge.id(), "VERIFIED")),
			List.of(evidence("ev-h-transfer", "h", "L2", edge.id())));
		var calendar = new LoadRouteTimetablePort.ServiceCalendar("daily", true, true, true, true, true, true, true,
			serviceDate.minusDays(2), serviceDate.plusDays(2), "Asia/Seoul");
		return new RouteTimetable(List.of(calendar), List.of(), List.of(route("L1"), route("L2")),
			List.of(trip("feeder", "L1"), trip("l2-fast", "L2")),
			List.of(stop("feeder", 1, ORIGIN, "L1", 29_400), stop("feeder", 2, "h", "L1", 29_700),
				stop("l2-fast", 1, "h", "L2", 30_060), stop("l2-fast", 2, DESTINATION, "L2", 30_480)),
			List.of(), List.of(), null, access);
	}

	private static List<JourneyItinerary> plan(
		JourneyRequest.MobilityProfile profile,
		JourneyRequest.ConstraintMode constraint,
		int maxTransfers,
		int alternativeCount,
		boolean includeThreeRideAndDirect
	) {
		return new RouteTimetableRaptorPlanner()
			.journeyItineraries(query(profile, constraint, maxTransfers, alternativeCount),
				timetable(includeThreeRideAndDirect, true))
			.itineraries();
	}

	static JourneyRaptorQuery query(
		JourneyRequest.MobilityProfile profile,
		JourneyRequest.ConstraintMode constraint,
		int maxTransfers,
		int alternativeCount
	) {
		return new JourneyRaptorQuery(REQUEST_ID, ORIGIN, DESTINATION, new JourneyRaptorQuery.DepartAt(READY_AT),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD, profile, constraint,
			maxTransfers, alternativeCount, () -> false);
	}

	private static String arrival(JourneyItinerary itinerary) {
		return itinerary.plannedArrivalTime().atZone(ServiceDayResolver.ZONE).toLocalTime().toString().substring(0, 5);
	}

	private static boolean hasStairs(JourneyItinerary itinerary) {
		return itinerary.legs().stream()
			.filter(JourneyAccessProjection.class::isInstance)
			.map(JourneyAccessProjection.class::cast)
			.anyMatch(JourneyAccessProjection::includesStairs);
	}

	/**
	 * 클래스 설명의 고정 시간표. {@code includeThreeRideAndDirect}가 false면 C와 D 노선을 뺀다.
	 * {@code includeStepFreePathway}가 false면 h에 계단 동선만 둔다.
	 */
	static RouteTimetable timetable(boolean includeThreeRideAndDirect, boolean includeStepFreePathway) {
		return timetable(includeThreeRideAndDirect, includeStepFreePathway ? StepFreePathway.VERIFIED : StepFreePathway.NONE);
	}

	/** h의 계단 없는 동선: 없음, 검증됨, 근거 미검증(동선 검증 상태가 VERIFIED가 아님). */
	enum StepFreePathway { NONE, VERIFIED, UNVERIFIED }

	static RouteTimetable timetable(boolean includeThreeRideAndDirect, StepFreePathway stepFreePathway) {
		List<LoadRouteTimetablePort.PathwayNode> nodes = new ArrayList<>();
		List<LoadRouteTimetablePort.PathwayEdge> edges = new ArrayList<>();
		List<LoadRouteTimetablePort.TransferRule> rules = new ArrayList<>();
		List<LoadRouteTimetablePort.RouteEdgeEvidence> evidence = new ArrayList<>();
		var hubStairs = edge("e-h-stairs", "p-h-L1", "p-h-L2", 30, true);
		edges.add(hubStairs);
		nodes.add(new LoadRouteTimetablePort.PathwayNode("p-h-L1", "h", "L1", "PLATFORM"));
		nodes.add(new LoadRouteTimetablePort.PathwayNode("p-h-L2", "h", "L2", "PLATFORM"));
		evidence.add(evidence("ev-h-stairs", "h", "L2", hubStairs.id()));
		String strictEdge = null;
		if (stepFreePathway != StepFreePathway.NONE) {
			var hubStepFree = new LoadRouteTimetablePort.PathwayEdge("e-h-step-free", "p-h-L1", "p-h-L2", 60, 600, false,
				false, 100, "AVAILABLE", "OFFICIAL_SOURCE",
				stepFreePathway == StepFreePathway.VERIFIED ? "VERIFIED" : "UNVERIFIED").withStairAccessState("STEP_FREE");
			edges.add(hubStepFree);
			evidence.add(evidence("ev-h-step-free", "h", "L2", hubStepFree.id()));
			strictEdge = hubStepFree.id();
		}
		rules.add(new LoadRouteTimetablePort.TransferRule("rule-h", "h", "L1", "h", "L2", "IN_STATION", 60,
			hubStairs.id(), strictEdge, "VERIFIED"));

		List<LoadRouteTimetablePort.TransitRoute> routes = new ArrayList<>(List.of(route("L1"), route("L2")));
		List<LoadRouteTimetablePort.TransitTrip> trips = new ArrayList<>(List.of(
			trip("feeder", "L1"), trip("l2-fast", "L2"), trip("l2-slow", "L2")));
		List<LoadRouteTimetablePort.TransitStopTime> stops = new ArrayList<>(List.of(
			stop("feeder", 1, ORIGIN, "L1", 29_400), stop("feeder", 2, "h", "L1", 29_700),
			stop("l2-fast", 1, "h", "L2", 30_060), stop("l2-fast", 2, DESTINATION, "L2", 30_480),
			stop("l2-slow", 1, "h", "L2", 31_200), stop("l2-slow", 2, DESTINATION, "L2", 31_620)));
		if (includeThreeRideAndDirect) {
			for (String[] transfer : List.of(new String[] {"a", "L4", "L5"}, new String[] {"b", "L5", "L6"})) {
				String station = transfer[0];
				var stairs = edge("e-" + station + "-stairs", "p-" + station + "-" + transfer[1],
					"p-" + station + "-" + transfer[2], 30, true);
				edges.add(stairs);
				nodes.add(new LoadRouteTimetablePort.PathwayNode(stairs.fromNodeId(), station, transfer[1], "PLATFORM"));
				nodes.add(new LoadRouteTimetablePort.PathwayNode(stairs.toNodeId(), station, transfer[2], "PLATFORM"));
				evidence.add(evidence("ev-" + station + "-stairs", station, transfer[2], stairs.id()));
				rules.add(new LoadRouteTimetablePort.TransferRule("rule-" + station, station, transfer[1], station,
					transfer[2], "IN_STATION", 60, stairs.id(), null, "VERIFIED"));
			}
			routes.addAll(List.of(route("L3"), route("L4"), route("L5"), route("L6")));
			trips.addAll(List.of(trip("direct", "L3"), trip("c1", "L4"), trip("c2", "L5"), trip("c3", "L6")));
			stops.addAll(List.of(
				stop("direct", 1, ORIGIN, "L3", 29_400), stop("direct", 2, DESTINATION, "L3", 33_000),
				stop("c1", 1, ORIGIN, "L4", 29_400), stop("c1", 2, "a", "L4", 29_500),
				stop("c2", 1, "a", "L5", 29_800), stop("c2", 2, "b", "L5", 29_900),
				stop("c3", 1, "b", "L6", 30_200), stop("c3", 2, DESTINATION, "L6", 30_400)));
		}
		var calendar = new LoadRouteTimetablePort.ServiceCalendar("daily", true, true, true, true, true, true, true,
			SERVICE_DATE.minusDays(2), SERVICE_DATE.plusDays(2), "Asia/Seoul");
		return new RouteTimetable(List.of(calendar), List.of(), List.copyOf(routes), List.copyOf(trips),
			List.copyOf(stops), List.of(), List.of(), null,
			new LoadRouteTimetablePort.RouteAccessData(nodes, edges, rules, evidence));
	}

	/**
	 * #469 경유 검색 시간표. o→v는 L1 직행(08:10 도착)과 o→x(L3)→v(L4) 두 번 승차(08:08 도착, x 계단 환승) 두 가지이고,
	 * v→d는 L2(08:20 출발, 08:30 도착)다. v의 L1→L2·L4→L2 환승에는 계단 동선(30 m)과 {@code junctionStepFree} 상태의
	 * 계단 없는 동선(600 m)이 있다.
	 */
	static RouteTimetable viaTimetable(StepFreePathway junctionStepFree) {
		List<LoadRouteTimetablePort.PathwayNode> nodes = new ArrayList<>();
		List<LoadRouteTimetablePort.PathwayEdge> edges = new ArrayList<>();
		List<LoadRouteTimetablePort.TransferRule> rules = new ArrayList<>();
		List<LoadRouteTimetablePort.RouteEdgeEvidence> evidence = new ArrayList<>();
		var xStairs = edge("e-x-stairs", "p-x-L3", "p-x-L4", 30, true);
		edges.add(xStairs);
		nodes.add(new LoadRouteTimetablePort.PathwayNode("p-x-L3", "x", "L3", "PLATFORM"));
		nodes.add(new LoadRouteTimetablePort.PathwayNode("p-x-L4", "x", "L4", "PLATFORM"));
		evidence.add(evidence("ev-x-stairs", "x", "L4", xStairs.id()));
		rules.add(new LoadRouteTimetablePort.TransferRule("rule-x", "x", "L3", "x", "L4", "IN_STATION", 60,
			xStairs.id(), null, "VERIFIED"));
		nodes.add(new LoadRouteTimetablePort.PathwayNode("p-v-L2", "v", "L2", "PLATFORM"));
		for (String fromLine : List.of("L1", "L4")) {
			nodes.add(new LoadRouteTimetablePort.PathwayNode("p-v-" + fromLine, "v", fromLine, "PLATFORM"));
			var stairs = edge("e-v-" + fromLine + "-stairs", "p-v-" + fromLine, "p-v-L2", 30, true);
			var stepFree = new LoadRouteTimetablePort.PathwayEdge("e-v-" + fromLine + "-step-free", "p-v-" + fromLine,
				"p-v-L2", 60, 600, false, false, 100, "AVAILABLE", "OFFICIAL_SOURCE",
				junctionStepFree == StepFreePathway.VERIFIED ? "VERIFIED" : "UNVERIFIED").withStairAccessState("STEP_FREE");
			edges.add(stairs);
			edges.add(stepFree);
			evidence.add(evidence("ev-v-" + fromLine + "-stairs", "v", "L2", stairs.id()));
			evidence.add(evidence("ev-v-" + fromLine + "-step-free", "v", "L2", stepFree.id()));
			rules.add(new LoadRouteTimetablePort.TransferRule("rule-v-" + fromLine, "v", fromLine, "v", "L2", "IN_STATION",
				60, stairs.id(), stepFree.id(), "VERIFIED"));
		}
		var calendar = new LoadRouteTimetablePort.ServiceCalendar("daily", true, true, true, true, true, true, true,
			SERVICE_DATE.minusDays(2), SERVICE_DATE.plusDays(2), "Asia/Seoul");
		return new RouteTimetable(List.of(calendar), List.of(),
			List.of(route("L1"), route("L2"), route("L3"), route("L4")),
			List.of(trip("v1", "L1"), trip("x1", "L3"), trip("x2", "L4"), trip("w1", "L2")),
			List.of(
				stop("v1", 1, ORIGIN, "L1", 29_400), stop("v1", 2, "v", "L1", 30_000),
				stop("x1", 1, ORIGIN, "L3", 29_400), stop("x1", 2, "x", "L3", 29_500),
				stop("x2", 1, "x", "L4", 29_800), stop("x2", 2, "v", "L4", 29_880),
				stop("w1", 1, "v", "L2", 30_600), stop("w1", 2, DESTINATION, "L2", 31_200)),
			List.of(), List.of(), null, new LoadRouteTimetablePort.RouteAccessData(nodes, edges, rules, evidence));
	}

	/** #453: 같은 (역, L1, L2) 쌍에 더 짧은 비검증 후보를 앞에, 검증 후보를 뒤에 둔 경유 시간표. */
	static RouteTimetable viaTimetableUnverifiedFirst() {
		List<LoadRouteTimetablePort.PathwayNode> nodes = List.of(
			new LoadRouteTimetablePort.PathwayNode("p-v-L1", "v", "L1", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("p-v-L2", "v", "L2", "PLATFORM"));
		var unverified = new LoadRouteTimetablePort.PathwayEdge("e-v-unverified", "p-v-L1", "p-v-L2", 30, 600,
			false, false, 100, "AVAILABLE", "OFFICIAL_SOURCE", "UNVERIFIED").withStairAccessState("STEP_FREE");
		var verified = new LoadRouteTimetablePort.PathwayEdge("e-v-verified", "p-v-L1", "p-v-L2", 60, 700,
			false, false, 100, "AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState("STEP_FREE");
		var rules = List.of(
			new LoadRouteTimetablePort.TransferRule("rule-v-unverified", "v", "L1", "v", "L2", "IN_STATION",
				30, unverified.id(), null, "UNVERIFIED"),
			new LoadRouteTimetablePort.TransferRule("rule-v-verified", "v", "L1", "v", "L2", "IN_STATION",
				60, verified.id(), null, "VERIFIED"));
		var evidence = List.of(
			evidence("ev-v-unverified", "v", "L2", unverified.id()),
			evidence("ev-v-verified", "v", "L2", verified.id()));
		var calendar = new LoadRouteTimetablePort.ServiceCalendar("daily", true, true, true, true, true, true, true,
			SERVICE_DATE.minusDays(2), SERVICE_DATE.plusDays(2), "Asia/Seoul");
		return new RouteTimetable(List.of(calendar), List.of(), List.of(route("L1"), route("L2")),
			List.of(trip("v1", "L1"), trip("w1", "L2")),
			List.of(
				stop("v1", 1, ORIGIN, "L1", 29_400), stop("v1", 2, "v", "L1", 30_000),
				stop("w1", 1, "v", "L2", 30_600), stop("w1", 2, DESTINATION, "L2", 31_200)),
			List.of(), List.of(), null,
			new LoadRouteTimetablePort.RouteAccessData(nodes, List.of(unverified, verified), rules, evidence));
	}

	private static LoadRouteTimetablePort.PathwayEdge edge(
		String id, String from, String to, int distanceMeters, boolean includesStairs
	) {
		return new LoadRouteTimetablePort.PathwayEdge(id, from, to, 60, distanceMeters, false, includesStairs, 100,
			"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState(includesStairs ? "STAIR_ONLY" : "STEP_FREE");
	}

	private static LoadRouteTimetablePort.RouteEdgeEvidence evidence(
		String id, String station, String line, String edgeId
	) {
		return new LoadRouteTimetablePort.RouteEdgeEvidence(id, station, line, edgeId, "TRANSFER",
			"OFFICIAL_SOURCE", "VERIFIED", true, null);
	}

	private static LoadRouteTimetablePort.TransitRoute route(String line) {
		return new LoadRouteTimetablePort.TransitRoute("r-" + line, line, line, line, "up", "Asia/Seoul");
	}

	private static LoadRouteTimetablePort.TransitTrip trip(String id, String line) {
		return new LoadRouteTimetablePort.TransitTrip(id, "r-" + line, "daily", DESTINATION, "up", "LOCAL", 0);
	}

	private static LoadRouteTimetablePort.TransitStopTime stop(
		String tripId, int sequence, String station, String line, int seconds
	) {
		return new LoadRouteTimetablePort.TransitStopTime(tripId, sequence, station, line, seconds, seconds, 0, 0);
	}
}
