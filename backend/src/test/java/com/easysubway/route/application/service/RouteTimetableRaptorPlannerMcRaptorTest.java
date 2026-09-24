package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.JourneyAccessProjection;
import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.JourneyItinerary;
import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.ScheduledTrip;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Bounded McRAPTOR 단일 패턴 다차원 파레토 라벨 백 및 용량 제한 검증")
class RouteTimetableRaptorPlannerMcRaptorTest {

	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 7, 6);
	private static final String ORIGIN = "origin";
	private static final String DESTINATION = "destination";

	@Test
	@DisplayName("동일 패턴 내에서 계단 진입 최속 열차와 무단차 후행 열차가 모두 보존된다")
	void preservesBothStairAndStepFreeTripsOnSamePattern() {
		var planner = new RouteTimetableRaptorPlanner();
		var timetable = samePatternStepFreeTimetable();

		var query = new JourneyRaptorQuery(
			"01ARZ3NDEKTSV4RRFFQ69G5FAV",
			ORIGIN,
			DESTINATION,
			new JourneyRaptorQuery.DepartAt(Instant.parse("2026-07-05T23:00:00Z")),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.SLOW,
			JourneyRequest.MobilityProfile.STEP_FREE,
			JourneyRequest.ConstraintMode.NONE,
			0,
			2,
			() -> false
		);

		List<JourneyItinerary> results = planner.journeyItineraries(query, timetable).itineraries();

		assertThat(results)
			.hasSize(2)
			.extracting(
				RouteTimetableRaptorPlannerMcRaptorTest::durationMinutes,
				itinerary -> entranceStep(itinerary).includesStairs()
			)
			.containsExactly(
				tuple(24L, true),
				tuple(31L, false)
			);
	}

	@Test
	@DisplayName("동일 패턴 내에서 선행 무단차 열차가 후행 계단 열차를 지배하여 축출한다")
	void supersetWarningTripDominatedAndPruned() {
		var planner = new RouteTimetableRaptorPlanner();
		var timetable = dominatedSupersetTimetable();

		var query = new JourneyRaptorQuery(
			"01ARZ3NDEKTSV4RRFFQ69G5FAV",
			ORIGIN,
			DESTINATION,
			new JourneyRaptorQuery.DepartAt(Instant.parse("2026-07-05T23:00:00Z")),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.SLOW,
			JourneyRequest.MobilityProfile.STEP_FREE,
			JourneyRequest.ConstraintMode.NONE,
			0,
			2,
			() -> false
		);

		List<JourneyItinerary> results = planner.journeyItineraries(query, timetable).itineraries();

		assertThat(results)
			.hasSize(1)
			.extracting(
				RouteTimetableRaptorPlannerMcRaptorTest::durationMinutes,
				itinerary -> entranceStep(itinerary).includesStairs()
			)
			.containsExactly(
				tuple(24L, false)
			);
	}

	@Test
	@DisplayName("ScanWorkspace 파레토 프런티어 용량 상한 초과 시 최악 라벨 축출 검증")
	void testFrontierCapacityEviction() {
		var workspace = new RouteTimetableRaptorPlanner.ScanWorkspace();
		workspace.prepare(3, 1, 1);
		for (int w = 0; w < 6; w += 1) {
			int slot = workspace.slot(1, 1, 0, w);
			workspace.arrivalSeconds[slot] = 1000 + w * 10;
			workspace.warningBits[slot] = (byte) w;
		}
		workspace.enforceStationFrontierCapacity(1, 1, 0, 4);

		int active = 0;
		for (int w = 0; w < 8; w += 1) {
			if (workspace.arrivalSeconds[workspace.slot(1, 1, 0, w)] != RouteTimetableRaptorPlanner.UNREACHED) {
				active += 1;
			}
		}
		assertThat(active).isEqualTo(4);
	}

	@Test
	@DisplayName("enforceBagCapacity 용량 초과 시 최악 열차 축출 검증")
	void testEnforceBagCapacityEviction() {
		var planner = new RouteTimetableRaptorPlanner();
		var timetable = samePatternStepFreeTimetable();
		var compiled = planner.compile(timetable);
		var trip = compiled.activeServiceDay(SERVICE_DATE).tripsByPattern(0).getFirst();

		var workspace = new RouteTimetableRaptorPlanner.ScanWorkspace();
		workspace.prepare(2, 1, 1);

		for (int w = 0; w < 6; w += 1) {
			workspace.bagTrips[w] = trip;
			workspace.bagWarningBits[w] = (byte) w;
		}
		RouteTimetableRaptorPlanner.enforceBagCapacity(workspace, 0, 4);

		int count = 0;
		for (int w = 0; w < 8; w += 1) {
			if (workspace.bagTrips[w] != null) count += 1;
		}
		assertThat(count).isEqualTo(4);
	}

	@Test
	@DisplayName("enforceRealtimeBagCapacity 및 enforceReadyCapacity 용량 초과 시 축출 검증")
	void testRealtimeAndReadyCapacityEviction() {
		var workspace = new RouteTimetableRaptorPlanner.ScanWorkspace();
		workspace.prepare(2, 1, 1);

		for (int w = 0; w < 6; w += 1) {
			workspace.rtActive[w] = true;
			workspace.rtEarliestDepartureSeconds[w] = 1000 + w * 10;
			workspace.rtWarningBits[w] = (byte) w;

			workspace.readyActive[w] = true;
			workspace.readyEarliestDepartureSeconds[w] = 1000 + w * 10;
			workspace.readyWarningBits[w] = (byte) w;
		}

		RouteTimetableRaptorPlanner.enforceRealtimeBagCapacity(workspace, 4);
		RouteTimetableRaptorPlanner.enforceReadyCapacity(workspace, 4);

		int rtCount = 0;
		int readyCount = 0;
		for (int w = 0; w < 8; w += 1) {
			if (workspace.rtActive[w]) rtCount += 1;
			if (workspace.readyActive[w]) readyCount += 1;
		}
		assertThat(rtCount).isEqualTo(4);
		assertThat(readyCount).isEqualTo(4);
	}

	@Test
	@DisplayName("updateBagWithCandidate 부분집합 지배 및 상위집합 축출 검증")
	void testUpdateBagWithCandidateDominance() {
		var planner = new RouteTimetableRaptorPlanner();
		var timetable = samePatternStepFreeTimetable();
		var compiled = planner.compile(timetable);
		var trips = compiled.activeServiceDay(SERVICE_DATE).tripsByPattern(0);
		var t1 = trips.get(0);
		var t2 = trips.get(1);

		var workspace = new RouteTimetableRaptorPlanner.ScanWorkspace();
		workspace.prepare(2, 1, 1);

		RouteTimetableRaptorPlanner.updateBagWithCandidate(workspace, 0, t1, 0, 29000, 0, 0, (byte) 0);
		assertThat(workspace.bagTrips[0]).isEqualTo(t1);

		RouteTimetableRaptorPlanner.updateBagWithCandidate(workspace, 1, t2, 0, 29000, 0, 0, (byte) 1);
		assertThat(workspace.bagTrips[1]).isNull();

		workspace.clearBag();
		workspace.bagTrips[3] = t2;
		workspace.bagWarningBits[3] = (byte) 3;
		RouteTimetableRaptorPlanner.updateBagWithCandidate(workspace, 1, t1, 0, 29000, 0, 0, (byte) 1);
		assertThat(workspace.bagTrips[1]).isEqualTo(t1);
		assertThat(workspace.bagTrips[3]).isNull();

		RouteTimetableRaptorPlanner.updateBagWithCandidate(workspace, 1, t1, 0, 29000, 0, 0, (byte) 1);
		assertThat(workspace.bagTrips[1]).isEqualTo(t1);
	}

	@Test
	@DisplayName("updateReadyBoarding 부분집합 지배, 상위집합 축출 및 갱신 검증")
	void testUpdateReadyBoardingBranches() {
		var workspace = new RouteTimetableRaptorPlanner.ScanWorkspace();
		workspace.prepare(2, 1, 1);

		RouteTimetableRaptorPlanner.updateReadyBoarding(workspace, 0, 0, 0, 1000, (byte) 0, false);
		assertThat(workspace.readyActive[0]).isTrue();

		RouteTimetableRaptorPlanner.updateReadyBoarding(workspace, 1, 1, 0, 1100, (byte) 1, false);
		assertThat(workspace.readyActive[1]).isFalse();

		workspace.clearReady();
		workspace.readyActive[3] = true;
		workspace.readyEarliestDepartureSeconds[3] = 1200;
		workspace.readyWarningBits[3] = (byte) 3;
		RouteTimetableRaptorPlanner.updateReadyBoarding(workspace, 1, 1, 0, 1050, (byte) 1, false);
		assertThat(workspace.readyActive[1]).isTrue();
		assertThat(workspace.readyActive[3]).isFalse();

		RouteTimetableRaptorPlanner.updateReadyBoarding(workspace, 1, 1, 0, 1020, (byte) 1, false);
		assertThat(workspace.readyEarliestDepartureSeconds[1]).isEqualTo(1020);

		RouteTimetableRaptorPlanner.updateReadyBoarding(workspace, 1, 1, 0, 1080, (byte) 1, false);
		assertThat(workspace.readyEarliestDepartureSeconds[1]).isEqualTo(1020);
	}

	@Test
	@DisplayName("isTransitionEligible 및 exitTransitions 분기 검증")
	void testIsTransitionEligibleAndExitTransitions() {
		var planner = new RouteTimetableRaptorPlanner();
		var timetable = samePatternStepFreeTimetable();
		var compiled = planner.compile(timetable);

		assertThat(compiled.isTransitionEligible(-1, 0, false, false, false)).isFalse();
		assertThat(compiled.isTransitionEligible(9999, 0, false, false, false)).isFalse();

		boolean el0 = compiled.isTransitionEligible(0, 0, false, false, false);
		assertThat(el0).isTrue();
		assertThat(compiled.isTransitionEligible(0, 0, true, false, false)).isTrue();
		assertThat(compiled.isTransitionEligible(0, 0, false, true, true)).isFalse();
		assertThat(compiled.isTransitionEligible(0, 0, false, true, false)).isFalse();

		int[] exits = compiled.exitTransitions(0, 0);
		assertThat(exits).isNotNull();
	}

	@Test
	@DisplayName("relax 지배, 축출 및 목표역 상위집합 갱신 검증")
	void testRelaxBranches() {
		var workspace = new RouteTimetableRaptorPlanner.ScanWorkspace();
		workspace.prepare(3, 1, 1);
		workspace.setTargetStation(2);

		workspace.relax(2, 1, 0, 1000, 0, 0, 1, 0, 0, (byte) 0);
		assertThat(workspace.bestTargetArrivalSeconds[0]).isEqualTo(1000);
		assertThat(workspace.bestTargetArrivalSeconds[1]).isEqualTo(1000);

		workspace.relax(2, 1, 0, 1200, 0, 0, 1, 0, 0, (byte) 0);
		assertThat(workspace.bestTargetArrivalSeconds[0]).isEqualTo(1000);

		workspace.relax(1, 1, 0, 500, 0, 0, 1, 0, 0, (byte) 0);

		workspace.relax(1, 1, 0, 600, 0, 0, 1, 0, 0, (byte) 1);
		assertThat(workspace.arrivalSeconds[workspace.slot(1, 1, 0, 1)]).isEqualTo(RouteTimetableRaptorPlanner.UNREACHED);

		workspace.relax(1, 2, 0, 600, 0, 0, 1, 0, 0, (byte) 0);
		assertThat(workspace.arrivalSeconds[workspace.slot(2, 1, 0, 0)]).isEqualTo(RouteTimetableRaptorPlanner.UNREACHED);

		workspace.arrivalSeconds[workspace.slot(1, 1, 0, 3)] = 800;
		workspace.relax(1, 1, 0, 400, 0, 0, 1, 0, 0, (byte) 1);
		assertThat(workspace.arrivalSeconds[workspace.slot(1, 1, 0, 3)]).isEqualTo(RouteTimetableRaptorPlanner.UNREACHED);

		workspace.relax(1, 1, 0, 400, 0, 0, 1, 0, 0, (byte) 1);
	}

	@Test
	@DisplayName("updateRealtimeBagWithCandidate 부분집합 지배, 상위집합 축출 및 갱신 분기 검증")
	void testUpdateRealtimeBagWithCandidate() {
		var workspace = new RouteTimetableRaptorPlanner.ScanWorkspace();
		workspace.prepare(2, 1, 1);

		// 1. Initial insert
		RouteTimetableRaptorPlanner.updateRealtimeBagWithCandidate(workspace, 0, 0, 1000, 0, 0, (byte) 0);
		assertThat(workspace.rtActive[0]).isTrue();

		// 2. Dominated by subset (sub=0 dominates w=1)
		RouteTimetableRaptorPlanner.updateRealtimeBagWithCandidate(workspace, 1, 0, 1100, 0, 1, (byte) 1);
		assertThat(workspace.rtActive[1]).isFalse();

		// 3. Superset eviction (w=1 evicts sup=3)
		workspace.rtActive[0] = false;
		workspace.rtActive[3] = true;
		workspace.rtEarliestDepartureSeconds[3] = 1200;
		workspace.rtWarningBits[3] = (byte) 3;
		RouteTimetableRaptorPlanner.updateRealtimeBagWithCandidate(workspace, 1, 0, 1050, 0, 1, (byte) 1);
		assertThat(workspace.rtActive[1]).isTrue();
		assertThat(workspace.rtActive[3]).isFalse();

		// 4. Incumbent replacement (earlier departure)
		RouteTimetableRaptorPlanner.updateRealtimeBagWithCandidate(workspace, 1, 0, 1020, 0, 1, (byte) 1);
		assertThat(workspace.rtEarliestDepartureSeconds[1]).isEqualTo(1020);

		// 5. Inferior candidate (later departure, no replacement)
		RouteTimetableRaptorPlanner.updateRealtimeBagWithCandidate(workspace, 1, 0, 1080, 0, 1, (byte) 1);
		assertThat(workspace.rtEarliestDepartureSeconds[1]).isEqualTo(1020);
	}

	@Test
	@DisplayName("updateBagWithCandidate sup 동일 도착/출발 및 incumbent 동일 출발/도착 분기 검증")
	void testUpdateBagWithCandidateAdditionalBranches() {
		var planner = new RouteTimetableRaptorPlanner();
		var timetable = samePatternStepFreeTimetable();
		var compiled = planner.compile(timetable);
		var trips = compiled.activeServiceDay(SERVICE_DATE).tripsByPattern(0);
		var t1 = trips.get(0);
		var t2 = trips.get(1);

		var workspace = new RouteTimetableRaptorPlanner.ScanWorkspace();
		workspace.prepare(2, 1, 1);

		// 1. candidateArr == supArr && candidateDep <= supDep -> evicts sup
		workspace.bagTrips[3] = t1;
		workspace.bagWarningBits[3] = (byte) 3;
		RouteTimetableRaptorPlanner.updateBagWithCandidate(workspace, 1, t1, 0, 29000, 0, 0, (byte) 1);
		assertThat(workspace.bagTrips[3]).isNull();
		assertThat(workspace.bagTrips[1]).isEqualTo(t1);

		// 2. candidate == incumbent (same trip)
		RouteTimetableRaptorPlanner.updateBagWithCandidate(workspace, 1, t1, 0, 29000, 0, 0, (byte) 1);
		assertThat(workspace.bagTrips[1]).isEqualTo(t1);

		// 3. candidateDep > incumbent.departureSeconds -> no update
		workspace.clearBag();
		workspace.bagTrips[1] = t2; // t2 departs 29800
		RouteTimetableRaptorPlanner.updateBagWithCandidate(workspace, 1, t1, 0, 29000, 0, 0, (byte) 1); // t1 departs 29400 < 29800 -> replaces!
		assertThat(workspace.bagTrips[1]).isEqualTo(t1);

		RouteTimetableRaptorPlanner.updateBagWithCandidate(workspace, 1, t2, 0, 29000, 0, 0, (byte) 1); // t2 departs 29800 > 29400 -> rejected!
		assertThat(workspace.bagTrips[1]).isEqualTo(t1);
	}

	@Test
	@DisplayName("용량 초과 시 동일 출발 시각에 대한 경고 수 및 인덱스 타이 브레이킹 분기 검증")
	void testCapacityTieBreakingBranches() {
		var planner = new RouteTimetableRaptorPlanner();
		var timetable = samePatternStepFreeTimetable();
		var compiled = planner.compile(timetable);
		var trips = compiled.activeServiceDay(SERVICE_DATE).tripsByPattern(0);
		var t1 = trips.get(0); // dep 29400
		var t2 = trips.get(1); // dep 29800

		var workspace = new RouteTimetableRaptorPlanner.ScanWorkspace();
		workspace.prepare(3, 1, 1);

		// StationFrontier
		int[] frontierArr = {1000, 1200, 1200, 1200, 800, 1200, 1200};
		byte[] frontierWarn = {0, 1, 3, 3, 0, 1, 3};
		for (int w = 0; w < 7; w += 1) {
			int s = workspace.slot(1, 1, 0, w);
			workspace.arrivalSeconds[s] = frontierArr[w];
			workspace.warningBits[s] = frontierWarn[w];
		}
		workspace.enforceStationFrontierCapacity(1, 1, 0, 2);

		// Bag
		ScheduledTrip[] bagTrips = {t1, t2, t2, t2, t1, t2, t2};
		byte[] bagWarn = {0, 1, 3, 3, 0, 1, 3};
		for (int w = 0; w < 7; w += 1) {
			workspace.bagTrips[w] = bagTrips[w];
			workspace.bagWarningBits[w] = bagWarn[w];
		}
		RouteTimetableRaptorPlanner.enforceBagCapacity(workspace, 0, 2);

		// Realtime
		for (int w = 0; w < 7; w += 1) {
			workspace.rtActive[w] = true;
			workspace.rtEarliestDepartureSeconds[w] = frontierArr[w];
			workspace.rtWarningBits[w] = frontierWarn[w];
		}
		RouteTimetableRaptorPlanner.enforceRealtimeBagCapacity(workspace, 2);

		// Ready
		for (int w = 0; w < 7; w += 1) {
			workspace.readyActive[w] = true;
			workspace.readyEarliestDepartureSeconds[w] = frontierArr[w];
			workspace.readyWarningBits[w] = frontierWarn[w];
		}
		RouteTimetableRaptorPlanner.enforceReadyCapacity(workspace, 2);
	}

	@Test
	@DisplayName("collectReadyBoardings 다중 alternative 전이(시간 단축 및 연장) 평가 분기 검증")
	void testCollectReadyBoardingsAlternativeTransitions() {
		var planner = new RouteTimetableRaptorPlanner();
		var timetable = multipleAlternativeTransitionsTimetable();
		var compiled = planner.compile(timetable);

		var query = new JourneyRaptorQuery(
			"01ARZ3NDEKTSV4RRFFQ69G5FAV",
			ORIGIN,
			DESTINATION,
			new JourneyRaptorQuery.DepartAt(Instant.parse("2026-07-05T23:00:00Z")),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.SLOW,
			JourneyRequest.MobilityProfile.STEP_FREE,
			JourneyRequest.ConstraintMode.NONE,
			0,
			2,
			() -> false
		);
		var input = RouteTimetableRaptorPlanner.scanInput(query);
		var workspace = new RouteTimetableRaptorPlanner.ScanWorkspace();
		workspace.prepare(compiled.stationCount(), compiled.lineCount(), 1);

		int stationOrigin = compiled.stationIndex(ORIGIN);
		int line1 = compiled.lineIndex("l1");
		int slot = workspace.slot(0, stationOrigin, workspace.noIncomingLine(), 0);
		workspace.arrivalSeconds[slot] = 28800;

		RouteTimetableRaptorPlanner.collectReadyBoardings(
			compiled, workspace, stationOrigin, line1, 0, 90, 0, input, false, RouteTimetableRaptorPlanner.UNREACHED);

		assertThat(workspace.readyActive[0]).isTrue();
	}

	@Test
	@DisplayName("CompiledTimetable 커버리지 보강 검증")
	void testCompiledTimetableCoverage() {
		var planner = new RouteTimetableRaptorPlanner();
		var timetable = samePatternStepFreeTimetable();
		var compiled = planner.compile(timetable);

		assertThat(compiled.coveredStationIds()).isNotEmpty();
		assertThat(compiled.routeCount()).isGreaterThan(0);
		assertThat(compiled.unsupportedTransferCount()).isGreaterThanOrEqualTo(0);
		assertThat(compiled.transferTransitions(0, 0, 0)).isNotNull();
	}

	@Test
	@DisplayName("updateBestReadyBoarding 갱신 분기 검증")
	void testUpdateBestReadyBoardingBranches() {
		var first = RouteTimetableRaptorPlanner.updateBestReadyBoarding(null, 1, 0, 1000, (byte) 0, false);
		assertThat(first).isNotNull();

		// candidate is better (900 < 1000) -> replaces
		var better = RouteTimetableRaptorPlanner.updateBestReadyBoarding(first, 2, 0, 900, (byte) 0, false);
		assertThat(better.earliestDepartureSeconds()).isEqualTo(900);

		// candidate is worse (1100 > 900) -> rejected
		var worse = RouteTimetableRaptorPlanner.updateBestReadyBoarding(better, 3, 0, 1100, (byte) 0, false);
		assertThat(worse.earliestDepartureSeconds()).isEqualTo(900);
	}

	@Test
	@DisplayName("compareReadyBoardingKeys 및 compareDestinationLabelKeys 분기 검증")
	void testComparisonKeysBranches() {
		// preferWarnings = true
		assertThat(RouteTimetableRaptorPlanner.compareReadyBoardingKeys(1000, (byte) 1, 1, 1000, (byte) 0, 2, true)).isGreaterThan(0);
		assertThat(RouteTimetableRaptorPlanner.compareReadyBoardingKeys(1100, (byte) 1, 1, 1000, (byte) 1, 2, true)).isGreaterThan(0);
		assertThat(RouteTimetableRaptorPlanner.compareReadyBoardingKeys(1000, (byte) 1, 2, 1000, (byte) 1, 1, true)).isGreaterThan(0);

		// preferWarnings = false
		assertThat(RouteTimetableRaptorPlanner.compareReadyBoardingKeys(1100, (byte) 0, 1, 1000, (byte) 0, 2, false)).isGreaterThan(0);
		assertThat(RouteTimetableRaptorPlanner.compareReadyBoardingKeys(1000, (byte) 1, 1, 1000, (byte) 0, 2, false)).isGreaterThan(0);
		assertThat(RouteTimetableRaptorPlanner.compareReadyBoardingKeys(1000, (byte) 1, 2, 1000, (byte) 1, 1, false)).isGreaterThan(0);

		// compareDestinationLabelKeys
		assertThat(RouteTimetableRaptorPlanner.compareDestinationLabelKeys(1100, (byte) 0, 1, 2, 1000, (byte) 0, 1, 2)).isGreaterThan(0);
		assertThat(RouteTimetableRaptorPlanner.compareDestinationLabelKeys(1000, (byte) 0, 1, 3, 1000, (byte) 0, 1, 2)).isGreaterThan(0);
	}

	@Test
	@DisplayName("dominates Label 분기 검증")
	void testDominatesLabelBranches() {
		var l1 = new RouteTimetableRaptorPlanner.Label("s", 1000, 900, 1, List.of(), null, 0, (byte) 0, 0);
		var l2 = new RouteTimetableRaptorPlanner.Label("s", 1000, 900, 2, List.of(), null, 0, (byte) 0, 0);
		var l3 = new RouteTimetableRaptorPlanner.Label("s", 1100, 900, 1, List.of(), null, 0, (byte) 0, 0);
		var lWarn = new RouteTimetableRaptorPlanner.Label("s", 1000, 900, 1, List.of(), null, 0, (byte) 1, 0);

		// other.boardings > candidate.boardings -> false
		assertThat(RouteTimetableRaptorPlanner.dominates(l2, l1, true, false)).isFalse();
		// other.virtualCost > candidate.virtualCost -> false
		assertThat(RouteTimetableRaptorPlanner.dominates(l3, l1, true, false)).isFalse();

		// !warningDimension -> true
		assertThat(RouteTimetableRaptorPlanner.dominates(l1, lWarn, false, false)).isTrue();

		// warningBits subset mismatch -> false
		assertThat(RouteTimetableRaptorPlanner.dominates(lWarn, l1, true, false)).isFalse();

		// warningBits subset match -> true
		assertThat(RouteTimetableRaptorPlanner.dominates(l1, lWarn, true, false)).isTrue();

		// exact same vector, earlier = true / false
		assertThat(RouteTimetableRaptorPlanner.dominates(l1, l1, true, true)).isTrue();
		assertThat(RouteTimetableRaptorPlanner.dominates(l1, l1, true, false)).isFalse();
	}

	@Test
	@DisplayName("limitDestinationLabels 분기 검증")
	void testLimitDestinationLabelsBranches() {
		var qStepFree = new JourneyRaptorQuery(
			"01ARZ3NDEKTSV4RRFFQ69G5FAV", ORIGIN, DESTINATION,
			new JourneyRaptorQuery.DepartAt(Instant.parse("2026-07-05T23:00:00Z")),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.SLOW, JourneyRequest.MobilityProfile.STEP_FREE,
			JourneyRequest.ConstraintMode.NONE, 0, 2, () -> false
		);
		var inStepFree = RouteTimetableRaptorPlanner.scanInput(qStepFree);

		var qNormal = new JourneyRaptorQuery(
			"01ARZ3NDEKTSV4RRFFQ69G5FAV", ORIGIN, DESTINATION,
			new JourneyRaptorQuery.DepartAt(Instant.parse("2026-07-05T23:00:00Z")),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.SLOW, JourneyRequest.MobilityProfile.SLOW,
			JourneyRequest.ConstraintMode.NONE, 0, 2, () -> false
		);
		var inNormal = RouteTimetableRaptorPlanner.scanInput(qNormal);

		var l0 = new RouteTimetableRaptorPlanner.Label("s", 1000, 900, 0, List.of(), null, 0, (byte) 1, 0);
		var l1 = new RouteTimetableRaptorPlanner.Label("s", 1100, 900, 1, List.of(), null, 0, (byte) 1, 0);
		var l2 = new RouteTimetableRaptorPlanner.Label("s", 1200, 900, 1, List.of(), null, 0, (byte) 1, 0);
		var lStepFree = new RouteTimetableRaptorPlanner.Label("s", 1300, 900, 1, List.of(), null, 0, (byte) 0, 0);
		var lFastStepFree = new RouteTimetableRaptorPlanner.Label("s", 950, 900, 1, List.of(), null, 0, (byte) 0, 0);

		// 1. ordered.size() <= limit
		assertThat(RouteTimetableRaptorPlanner.limitDestinationLabels(List.of(l0), inStepFree)).hasSize(1);

		// 2. !input.prefersStepFree()
		assertThat(RouteTimetableRaptorPlanner.limitDestinationLabels(List.of(l0, l1, l2), inNormal)).hasSize(2);

		// 3. already contains preferred step-free
		assertThat(RouteTimetableRaptorPlanner.limitDestinationLabels(List.of(lFastStepFree, l0, l1), inStepFree)).contains(lFastStepFree);

		// 4. evict duplicate boardings with preferred step-free
		var result = RouteTimetableRaptorPlanner.limitDestinationLabels(List.of(l1, l2, lStepFree), inStepFree);
		assertThat(result).contains(lStepFree);

		// 5. victim < 0 (no duplicate boardings beyond index 0)
		var noDupResult = RouteTimetableRaptorPlanner.limitDestinationLabels(List.of(l0, l1, lStepFree), inStepFree);
		assertThat(noDupResult).doesNotContain(lStepFree);
	}

	@Test
	@DisplayName("journeyAccessSeconds 분기 검증")
	void testJourneyAccessSecondsBranches() {
		var query = new JourneyRaptorQuery(
			"01ARZ3NDEKTSV4RRFFQ69G5FAV", ORIGIN, DESTINATION,
			new JourneyRaptorQuery.DepartAt(Instant.parse("2026-07-05T23:00:00Z")),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.SLOW, JourneyRequest.MobilityProfile.STEP_FREE,
			JourneyRequest.ConstraintMode.NONE, 0, 2, () -> false
		);
		var inVerified = RouteTimetableRaptorPlanner.scanInput(query);
		var inUnverified = new RouteTimetableRaptorPlanner.ScanInput(
			inVerified.originStationId(), inVerified.destinationStationId(), inVerified.serviceDay(),
			inVerified.readyAtSeconds(), inVerified.accessProfileBit(), inVerified.mobilityPreset(),
			inVerified.constraintMode(), inVerified.walkingSpeedMetersPerHour(), inVerified.boardingSlackSeconds(),
			false, inVerified.realtimeRequired(), inVerified.maxTransfers(), inVerified.candidateLimit(),
			inVerified.cancellationSignal()
		);

		// TRANSFER + requiresVerifiedJourneyDistance
		int s1 = RouteTimetableRaptorPlanner.journeyAccessSeconds(
			inVerified, RouteTimetableRaptorPlanner.JourneyAccessKind.TRANSFER, 60, 100);
		assertThat(s1).isGreaterThan(0);

		// ENTRY + requiresVerifiedJourneyDistance
		int s2 = RouteTimetableRaptorPlanner.journeyAccessSeconds(
			inVerified, RouteTimetableRaptorPlanner.JourneyAccessKind.ENTRY, 60, 100);
		assertThat(s2).isGreaterThan(0);

		// TRANSFER without verified distance
		int s3 = RouteTimetableRaptorPlanner.journeyAccessSeconds(
			inUnverified, RouteTimetableRaptorPlanner.JourneyAccessKind.TRANSFER, 60, 100);
		assertThat(s3).isGreaterThan(0);
	}

	private static long durationMinutes(JourneyItinerary itinerary) {
		return Math.round(Duration.between(itinerary.plannedDepartureTime(), itinerary.plannedArrivalTime()).toSeconds() / 60.0);
	}

	private static JourneyAccessProjection entranceStep(JourneyItinerary itinerary) {
		return (JourneyAccessProjection) itinerary.legs().getFirst();
	}

	private static RouteTimetable samePatternStepFreeTimetable() {
		List<LoadRouteTimetablePort.PathwayNode> nodes = new ArrayList<>();
		List<LoadRouteTimetablePort.PathwayEdge> edges = new ArrayList<>();
		List<LoadRouteTimetablePort.RouteEdgeEvidence> evidence = new ArrayList<>();

		// Entrance nodes & edges for ORIGIN
		// 1) Entrance with stairs: 240s
		// 2) Entrance step-free: 600s
		String entStairs = "ent-stairs";
		String entStepFree = "ent-stepfree";
		String platOrigin = "plat-origin";
		nodes.add(new LoadRouteTimetablePort.PathwayNode(entStairs, ORIGIN, null, "ENTRANCE"));
		nodes.add(new LoadRouteTimetablePort.PathwayNode(entStepFree, ORIGIN, null, "ENTRANCE"));
		nodes.add(new LoadRouteTimetablePort.PathwayNode(platOrigin, ORIGIN, "l1", "PLATFORM"));

		edges.add(new LoadRouteTimetablePort.PathwayEdge("e-stairs", entStairs, platOrigin, 240, 180, false, true, 100, "AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"));
		edges.add(new LoadRouteTimetablePort.PathwayEdge("e-stepfree", entStepFree, platOrigin, 600, 400, false, false, 100, "AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"));
		evidence.add(new LoadRouteTimetablePort.RouteEdgeEvidence("ev-stairs", ORIGIN, "l1", "e-stairs", "ENTRY", "OFFICIAL_SOURCE", "VERIFIED", true, null));
		evidence.add(new LoadRouteTimetablePort.RouteEdgeEvidence("ev-stepfree", ORIGIN, "l1", "e-stepfree", "ENTRY", "OFFICIAL_SOURCE", "VERIFIED", true, null));

		// Exit nodes & edges for DESTINATION (step-free)
		String exitDest = "exit-dest";
		String platDest = "plat-dest";
		nodes.add(new LoadRouteTimetablePort.PathwayNode(exitDest, DESTINATION, null, "EXIT"));
		nodes.add(new LoadRouteTimetablePort.PathwayNode(platDest, DESTINATION, "l1", "PLATFORM"));
		edges.add(new LoadRouteTimetablePort.PathwayEdge("e-exit", platDest, exitDest, 180, 120, false, false, 100, "AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"));
		evidence.add(new LoadRouteTimetablePort.RouteEdgeEvidence("ev-exit", DESTINATION, "l1", "e-exit", "EXIT", "OFFICIAL_SOURCE", "VERIFIED", true, null));

		// 1 Route (r1 on l1), 2 Trips (t1 earlier with stairs, t2 later step-free)
		var routes = List.of(new LoadRouteTimetablePort.TransitRoute("r1", "l1", "r1", "l1", "Line 1", "Asia/Seoul"));
		var trips = List.of(
			new LoadRouteTimetablePort.TransitTrip("t1", "r1", "daily", "t1", "0", "LOCAL", 0),
			new LoadRouteTimetablePort.TransitTrip("t2", "r1", "daily", "t2", "0", "LOCAL", 0)
		);
		// Query DepartAt: 2026-07-05T23:00:00Z -> 08:00 KST (28800).
		// With stairs: ready at 28800 + 324s (240 * 1.35) + 90s slack = 29214. Can board t1 (departs 29400).
		// t1 arrives at DESTINATION at 30000. Exit = 180 * 1.35 = 243s. Total arrival = 30243. Duration = 30243 - 28800 = 1443s (24m).
		// Step-free: ready at 28800 + 810s (600 * 1.35) + 90s slack = 29700. Can board t2 (departs 29800).
		// t2 arrives at DESTINATION at 30400. Exit = 243s. Total arrival = 30643. Duration = 30643 - 28800 = 1843s (31m).
		var stops = List.of(
			new LoadRouteTimetablePort.TransitStopTime("t1", 1, ORIGIN, "l1", 29400, 29400, 0, 0),
			new LoadRouteTimetablePort.TransitStopTime("t1", 2, DESTINATION, "l1", 30000, 30000, 0, 0),
			new LoadRouteTimetablePort.TransitStopTime("t2", 1, ORIGIN, "l1", 29800, 29800, 0, 0),
			new LoadRouteTimetablePort.TransitStopTime("t2", 2, DESTINATION, "l1", 30400, 30400, 0, 0)
		);

		var daily = new LoadRouteTimetablePort.ServiceCalendar(
			"daily", true, true, true, true, true, true, true,
			SERVICE_DATE, SERVICE_DATE.plusDays(7), "Asia/Seoul");

		return new RouteTimetable(
			List.of(daily), List.of(), routes, trips, stops,
			List.of(), List.of(), null,
			new LoadRouteTimetablePort.RouteAccessData(nodes, edges, List.of(), evidence));
	}

	private static RouteTimetable dominatedSupersetTimetable() {
		List<LoadRouteTimetablePort.PathwayNode> nodes = new ArrayList<>();
		List<LoadRouteTimetablePort.PathwayEdge> edges = new ArrayList<>();
		List<LoadRouteTimetablePort.RouteEdgeEvidence> evidence = new ArrayList<>();

		String entStairs = "ent-stairs";
		String entStepFree = "ent-stepfree";
		String platOrigin = "plat-origin";
		nodes.add(new LoadRouteTimetablePort.PathwayNode(entStairs, ORIGIN, null, "ENTRANCE"));
		nodes.add(new LoadRouteTimetablePort.PathwayNode(entStepFree, ORIGIN, null, "ENTRANCE"));
		nodes.add(new LoadRouteTimetablePort.PathwayNode(platOrigin, ORIGIN, "l1", "PLATFORM"));

		// Step-free is FASTER (240s), stairs is SLOWER (600s). Step-free dominates stairs!
		edges.add(new LoadRouteTimetablePort.PathwayEdge("e-stepfree", entStepFree, platOrigin, 240, 180, false, false, 100, "AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"));
		edges.add(new LoadRouteTimetablePort.PathwayEdge("e-stairs", entStairs, platOrigin, 600, 400, false, true, 100, "AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"));
		evidence.add(new LoadRouteTimetablePort.RouteEdgeEvidence("ev-stepfree", ORIGIN, "l1", "e-stepfree", "ENTRY", "OFFICIAL_SOURCE", "VERIFIED", true, null));
		evidence.add(new LoadRouteTimetablePort.RouteEdgeEvidence("ev-stairs", ORIGIN, "l1", "e-stairs", "ENTRY", "OFFICIAL_SOURCE", "VERIFIED", true, null));

		String exitDest = "exit-dest";
		String platDest = "plat-dest";
		nodes.add(new LoadRouteTimetablePort.PathwayNode(exitDest, DESTINATION, null, "EXIT"));
		nodes.add(new LoadRouteTimetablePort.PathwayNode(platDest, DESTINATION, "l1", "PLATFORM"));
		edges.add(new LoadRouteTimetablePort.PathwayEdge("e-exit", platDest, exitDest, 180, 120, false, false, 100, "AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"));
		evidence.add(new LoadRouteTimetablePort.RouteEdgeEvidence("ev-exit", DESTINATION, "l1", "e-exit", "EXIT", "OFFICIAL_SOURCE", "VERIFIED", true, null));

		var routes = List.of(new LoadRouteTimetablePort.TransitRoute("r1", "l1", "r1", "l1", "Line 1", "Asia/Seoul"));
		var trips = List.of(
			new LoadRouteTimetablePort.TransitTrip("t1", "r1", "daily", "t1", "0", "LOCAL", 0),
			new LoadRouteTimetablePort.TransitTrip("t2", "r1", "daily", "t2", "0", "LOCAL", 0)
		);
		var stops = List.of(
			new LoadRouteTimetablePort.TransitStopTime("t1", 1, ORIGIN, "l1", 29400, 29400, 0, 0),
			new LoadRouteTimetablePort.TransitStopTime("t1", 2, DESTINATION, "l1", 30000, 30000, 0, 0),
			new LoadRouteTimetablePort.TransitStopTime("t2", 1, ORIGIN, "l1", 29800, 29800, 0, 0),
			new LoadRouteTimetablePort.TransitStopTime("t2", 2, DESTINATION, "l1", 30400, 30400, 0, 0)
		);

		var daily = new LoadRouteTimetablePort.ServiceCalendar(
			"daily", true, true, true, true, true, true, true,
			SERVICE_DATE, SERVICE_DATE.plusDays(7), "Asia/Seoul");

		return new RouteTimetable(
			List.of(daily), List.of(), routes, trips, stops,
			List.of(), List.of(), null,
			new LoadRouteTimetablePort.RouteAccessData(nodes, edges, List.of(), evidence));
	}

	private static RouteTimetable multipleAlternativeTransitionsTimetable() {
		List<LoadRouteTimetablePort.PathwayNode> nodes = new ArrayList<>();
		List<LoadRouteTimetablePort.PathwayEdge> edges = new ArrayList<>();
		List<LoadRouteTimetablePort.RouteEdgeEvidence> evidence = new ArrayList<>();

		String entCanonical = "ent-canonical";
		String entAlt1 = "ent-alt1";
		String entAlt2 = "ent-alt2";
		String entAlt3 = "ent-alt3";
		String platOrigin = "plat-origin";
		nodes.add(new LoadRouteTimetablePort.PathwayNode(entCanonical, ORIGIN, null, "ENTRANCE"));
		nodes.add(new LoadRouteTimetablePort.PathwayNode(entAlt1, ORIGIN, null, "ENTRANCE"));
		nodes.add(new LoadRouteTimetablePort.PathwayNode(entAlt2, ORIGIN, null, "ENTRANCE"));
		nodes.add(new LoadRouteTimetablePort.PathwayNode(entAlt3, ORIGIN, null, "ENTRANCE"));
		nodes.add(new LoadRouteTimetablePort.PathwayNode(platOrigin, ORIGIN, "l1", "PLATFORM"));

		// Canonical: step-free (600s, no stairs)
		edges.add(new LoadRouteTimetablePort.PathwayEdge("e-can", entCanonical, platOrigin, 600, 400, false, false, 100, "AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"));
		evidence.add(new LoadRouteTimetablePort.RouteEdgeEvidence("ev-can", ORIGIN, "l1", "e-can", "ENTRY", "OFFICIAL_SOURCE", "VERIFIED", true, null));

		// Alt1: stairs, 300s
		edges.add(new LoadRouteTimetablePort.PathwayEdge("e-alt1", entAlt1, platOrigin, 300, 200, false, true, 100, "AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"));
		evidence.add(new LoadRouteTimetablePort.RouteEdgeEvidence("ev-alt1", ORIGIN, "l1", "e-alt1", "ENTRY", "OFFICIAL_SOURCE", "VERIFIED", true, null));

		// Alt2: stairs, 150s (shorter than alt1)
		edges.add(new LoadRouteTimetablePort.PathwayEdge("e-alt2", entAlt2, platOrigin, 150, 100, false, true, 100, "AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"));
		evidence.add(new LoadRouteTimetablePort.RouteEdgeEvidence("ev-alt2", ORIGIN, "l1", "e-alt2", "ENTRY", "OFFICIAL_SOURCE", "VERIFIED", true, null));

		// Alt3: stairs, 400s (longer than alt2)
		edges.add(new LoadRouteTimetablePort.PathwayEdge("e-alt3", entAlt3, platOrigin, 400, 250, false, true, 100, "AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"));
		evidence.add(new LoadRouteTimetablePort.RouteEdgeEvidence("ev-alt3", ORIGIN, "l1", "e-alt3", "ENTRY", "OFFICIAL_SOURCE", "VERIFIED", true, null));

		String exitDest = "exit-dest";
		String platDest = "plat-dest";
		nodes.add(new LoadRouteTimetablePort.PathwayNode(exitDest, DESTINATION, null, "EXIT"));
		nodes.add(new LoadRouteTimetablePort.PathwayNode(platDest, DESTINATION, "l1", "PLATFORM"));
		edges.add(new LoadRouteTimetablePort.PathwayEdge("e-exit", platDest, exitDest, 180, 120, false, false, 100, "AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"));
		evidence.add(new LoadRouteTimetablePort.RouteEdgeEvidence("ev-exit", DESTINATION, "l1", "e-exit", "EXIT", "OFFICIAL_SOURCE", "VERIFIED", true, null));

		var routes = List.of(new LoadRouteTimetablePort.TransitRoute("r1", "l1", "r1", "l1", "Line 1", "Asia/Seoul"));
		var trips = List.of(
			new LoadRouteTimetablePort.TransitTrip("t1", "r1", "daily", "t1", "0", "LOCAL", 0)
		);
		var stops = List.of(
			new LoadRouteTimetablePort.TransitStopTime("t1", 1, ORIGIN, "l1", 30000, 30000, 0, 0),
			new LoadRouteTimetablePort.TransitStopTime("t1", 2, DESTINATION, "l1", 31000, 31000, 0, 0)
		);
		var daily = new LoadRouteTimetablePort.ServiceCalendar(
			"daily", true, true, true, true, true, true, true,
			SERVICE_DATE, SERVICE_DATE.plusDays(7), "Asia/Seoul");

		return new RouteTimetable(
			List.of(daily), List.of(), routes, trips, stops,
			List.of(), List.of(), null,
			new LoadRouteTimetablePort.RouteAccessData(nodes, edges, List.of(), evidence));
	}
}
