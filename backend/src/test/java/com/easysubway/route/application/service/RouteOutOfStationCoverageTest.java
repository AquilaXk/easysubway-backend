package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.JourneyAccessKind;
import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.JourneyAccessProjection;
import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.Label;
import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.OutOfStationFootpath;
import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.ScanWorkspace;
import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RouteTimetableRaptorPlanner Out-of-station 및 McRAPTOR 가지치기 단위 커버리지 테스트")
class RouteOutOfStationCoverageTest {

	@Test
	@DisplayName("getTransferLimitSeconds 시간대별 분기 완전 커버리지 검증")
	void getTransferLimitSecondsBranches() {
		// 1. 하차 시각이 이른 아침 (< 7:00)
		assertThat(RouteTimetableRaptorPlanner.getTransferLimitSeconds(5 * 3600, 10 * 3600)).isEqualTo(3600);
		// 2. 하차 시각이 심야 (>= 21:00)
		assertThat(RouteTimetableRaptorPlanner.getTransferLimitSeconds(21 * 3600 + 1, 10 * 3600)).isEqualTo(3600);
		// 3. 승차 시각이 이른 아침 (< 7:00)
		assertThat(RouteTimetableRaptorPlanner.getTransferLimitSeconds(10 * 3600, 6 * 3600)).isEqualTo(3600);
		// 4. 승차 시각이 심야 (>= 21:00)
		assertThat(RouteTimetableRaptorPlanner.getTransferLimitSeconds(12 * 3600, 22 * 3600)).isEqualTo(3600);
		// 5. 주간 (7:00 ~ 20:59)
		assertThat(RouteTimetableRaptorPlanner.getTransferLimitSeconds(12 * 3600, 12 * 3600 + 600)).isEqualTo(1800);
		// 6. 다음 날 24시간 초과 주간 (86400 modulo 동작)
		assertThat(RouteTimetableRaptorPlanner.getTransferLimitSeconds(86400 + 14 * 3600, 86400 + 14 * 3600 + 300)).isEqualTo(1800);
	}

	@Test
	@DisplayName("calculateJourneyPenalty 경과 시간 및 한도별 분기 완전 커버리지 검증")
	void calculateJourneyPenaltyBranches() {
		// 주간 한도 1800초 (30분)
		// <= 18분 (1080초): 0
		assertThat(RouteTimetableRaptorPlanner.calculateJourneyPenalty(600, 1800)).isZero();
		assertThat(RouteTimetableRaptorPlanner.calculateJourneyPenalty(1080, 1800)).isZero();
		// 18분 초과 ~ 30분 이하: 300초 페널티
		assertThat(RouteTimetableRaptorPlanner.calculateJourneyPenalty(1081, 1800)).isEqualTo(300);
		assertThat(RouteTimetableRaptorPlanner.calculateJourneyPenalty(1800, 1800)).isEqualTo(300);
		// 30분 초과: 600초 페널티
		assertThat(RouteTimetableRaptorPlanner.calculateJourneyPenalty(1801, 1800)).isEqualTo(600);

		// 야간 한도 3600초 (60분)
		// <= 60분: 0
		assertThat(RouteTimetableRaptorPlanner.calculateJourneyPenalty(1200, 3600)).isZero();
		assertThat(RouteTimetableRaptorPlanner.calculateJourneyPenalty(2400, 3600)).isZero();
		assertThat(RouteTimetableRaptorPlanner.calculateJourneyPenalty(3600, 3600)).isZero();
		// 60분 초과: 600초 페널티
		assertThat(RouteTimetableRaptorPlanner.calculateJourneyPenalty(3601, 3600)).isEqualTo(600);
	}

	@Test
	@DisplayName("OutOfStationFootpath 레코드 방어적 복사 및 equals/hashCode/toString 검증")
	void outOfStationFootpathDefensiveCopyAndRecordMethods() {
		// null candidates -> 빈 배열 초기화
		var emptyFootpath = new OutOfStationFootpath(1, 2, 3, 4, null);
		assertThat(emptyFootpath.candidateTransitions()).isEmpty();
		assertThat(emptyFootpath.fromStation()).isEqualTo(1);
		assertThat(emptyFootpath.fromLine()).isEqualTo(2);
		assertThat(emptyFootpath.toStation()).isEqualTo(3);
		assertThat(emptyFootpath.toLine()).isEqualTo(4);

		// non-null candidates -> 방어적 복사
		int[] original = new int[] {10, 20};
		var footpath1 = new OutOfStationFootpath(1, 2, 3, 4, original);
		original[0] = 999;
		assertThat(footpath1.candidateTransitions()).containsExactly(10, 20);

		int[] returned = footpath1.candidateTransitions();
		returned[0] = 888;
		assertThat(footpath1.candidateTransitions()).containsExactly(10, 20);

		var footpath2 = new OutOfStationFootpath(1, 2, 3, 4, new int[] {10, 20});
		assertThat(footpath1).isEqualTo(footpath2);
		assertThat(footpath1.equals(footpath1)).isTrue();
		assertThat(footpath1.equals(null)).isFalse();
		assertThat(footpath1.equals("not-a-footpath")).isFalse();
		assertThat(footpath1.equals(new OutOfStationFootpath(99, 2, 3, 4, new int[] {10, 20}))).isFalse();
		assertThat(footpath1.equals(new OutOfStationFootpath(1, 99, 3, 4, new int[] {10, 20}))).isFalse();
		assertThat(footpath1.equals(new OutOfStationFootpath(1, 2, 99, 4, new int[] {10, 20}))).isFalse();
		assertThat(footpath1.equals(new OutOfStationFootpath(1, 2, 3, 99, new int[] {10, 20}))).isFalse();
		assertThat(footpath1.equals(new OutOfStationFootpath(1, 2, 3, 4, new int[] {99}))).isFalse();
		assertThat(footpath1.hashCode()).isEqualTo(footpath2.hashCode());
		assertThat(footpath1.toString()).contains("OutOfStationFootpath");
	}

	@Test
	@DisplayName("JourneyAccessProjection 오버로드 생성자 기본값 검증")
	void journeyAccessProjectionConstructors() {
		var defaultAccess = new JourneyAccessProjection(
			JourneyAccessKind.TRANSFER, "st-a", "st-b", 120, 100, false, true, "VERIFIED"
		);
		assertThat(defaultAccess.transferType()).isNull();
		assertThat(defaultAccess.farePenaltyApplies()).isNull();
		assertThat(defaultAccess.additionalFareWon()).isNull();
		assertThat(defaultAccess.transferLimitMinutes()).isNull();

		var explicitAccess = new JourneyAccessProjection(
			JourneyAccessKind.TRANSFER, "st-a", "st-b", 120, 100, false, true, "VERIFIED",
			"OUT_OF_STATION", true, 1400, 30
		);
		assertThat(explicitAccess.transferType()).isEqualTo("OUT_OF_STATION");
		assertThat(explicitAccess.farePenaltyApplies()).isTrue();
		assertThat(explicitAccess.additionalFareWon()).isEqualTo(1400);
		assertThat(explicitAccess.transferLimitMinutes()).isEqualTo(30);
	}

	@Test
	@DisplayName("Label 생성자 오버로드 및 virtualCostSeconds 페널티 가산 검증")
	void labelVirtualCostSeconds() {
		var defaultLabel = new Label(
			"st-a", 1000, 900, 1, List.of(), new int[] {0}, -1, (byte) 0
		);
		assertThat(defaultLabel.penaltySeconds()).isZero();
		assertThat(defaultLabel.virtualCostSeconds()).isEqualTo(1000);

		var penalizedLabel = new Label(
			"st-a", 1000, 900, 1, List.of(), new int[] {0}, -1, (byte) 0, 300
		);
		assertThat(penalizedLabel.penaltySeconds()).isEqualTo(300);
		assertThat(penalizedLabel.virtualCostSeconds()).isEqualTo(1300);
	}

	@Test
	@DisplayName("ScanWorkspace isDominatedByTarget 및 markNext 분기 검증")
	void scanWorkspaceDominatedByTargetAndMarkNext() {
		var workspace = new ScanWorkspace();
		workspace.prepare(5, 5, 5);
		int target = 3;
		workspace.setTargetStation(target);

		// 초기에는 UNREACHED
		assertThat(workspace.isDominatedByTarget(target, 5000, 0)).isFalse();

		// target best arrival을 5000초로 설정
		workspace.bestTargetArrivalSeconds[0] = 5000;

		// 1. station == targetStation: best < candidateArrivalSeconds 일 때만 dominated
		assertThat(workspace.isDominatedByTarget(target, 5001, 0)).isTrue();
		assertThat(workspace.isDominatedByTarget(target, 5000, 0)).isFalse(); // 동률은 타겟 도달로서 유지
		assertThat(workspace.isDominatedByTarget(target, 4999, 0)).isFalse();

		// 2. station != targetStation: best <= candidateArrivalSeconds 이면 dominated
		int intermediate = 2;
		assertThat(workspace.isDominatedByTarget(intermediate, 5001, 0)).isTrue();
		assertThat(workspace.isDominatedByTarget(intermediate, 5000, 0)).isTrue(); // 타겟 도달 시각 이상이면 조기 가지치기
		assertThat(workspace.isDominatedByTarget(intermediate, 4999, 0)).isFalse();

		// 3. warningState 비트 마스크 일치 여부
		workspace.bestTargetArrivalSeconds[1] = 4000; // warningState 1에 대해 4000초
		// candidateWarningState 0: 1비트가 켜져있지 않으므로 warningState 1은 검사 안 됨
		assertThat(workspace.isDominatedByTarget(intermediate, 4500, 0)).isFalse();
		// candidateWarningState 1 또는 3: 1비트가 켜져있으므로 best=4000 <= 4500 -> dominated!
		assertThat(workspace.isDominatedByTarget(intermediate, 4500, 1)).isTrue();
		assertThat(workspace.isDominatedByTarget(intermediate, 4500, 3)).isTrue();

		// 4. markNext 중복 호출 방어
		assertThat(workspace.nextMarked[1]).isFalse();
		workspace.markNext(1);
		assertThat(workspace.nextMarked[1]).isTrue();
		assertThat(workspace.nextMarkedStopCount).isEqualTo(1);
		// 중복 호출 시 카운트 증가하지 않음
		workspace.markNext(1);
		assertThat(workspace.nextMarkedStopCount).isEqualTo(1);
	}

	@Test
	@DisplayName("dominates 정적 메서드의 다차원 파레토 지배 분기 완전 검증")
	void dominatesMethodBranches() {
		var candidate = new Label("st", 1000, 900, 2, List.of(), new int[] {0}, -1, (byte) 1, 0);

		// 1. other.boardings > candidate.boardings -> false
		var worseBoardings = new Label("st", 900, 900, 3, List.of(), new int[] {0}, -1, (byte) 1, 0);
		assertThat(RouteTimetableRaptorPlanner.dominates(worseBoardings, candidate, true, true)).isFalse();

		// 2. other.virtualCostSeconds > candidate.virtualCostSeconds -> false
		var worseCost = new Label("st", 1100, 900, 2, List.of(), new int[] {0}, -1, (byte) 1, 0);
		assertThat(RouteTimetableRaptorPlanner.dominates(worseCost, candidate, true, true)).isFalse();

		// 3. warningDimension == false -> true (동일하거나 우세하면 즉시 지배)
		var equalCost = new Label("st", 1000, 900, 2, List.of(), new int[] {0}, -1, (byte) 3, 0);
		assertThat(RouteTimetableRaptorPlanner.dominates(equalCost, candidate, false, true)).isTrue();

		// 4. warning bits mismatch -> false
		// candidate warningBits=1, other warningBits=2 -> (2 & 1) != 2 -> false
		var diffWarning = new Label("st", 900, 900, 1, List.of(), new int[] {0}, -1, (byte) 2, 0);
		assertThat(RouteTimetableRaptorPlanner.dominates(diffWarning, candidate, true, true)).isFalse();

		// 5. other.boardings < candidate.boardings -> true
		var betterBoardings = new Label("st", 1000, 900, 1, List.of(), new int[] {0}, -1, (byte) 1, 0);
		assertThat(RouteTimetableRaptorPlanner.dominates(betterBoardings, candidate, true, true)).isTrue();

		// 6. other.virtualCostSeconds < candidate.virtualCostSeconds -> true
		var betterCost = new Label("st", 950, 900, 2, List.of(), new int[] {0}, -1, (byte) 1, 0);
		assertThat(RouteTimetableRaptorPlanner.dominates(betterCost, candidate, true, true)).isTrue();

		// 7. other.warningBits != candidate.warningBits (sub-warning) -> true
		// other warningBits=0 (경고 없음), candidate warningBits=1 -> other is better
		var betterWarning = new Label("st", 1000, 900, 2, List.of(), new int[] {0}, -1, (byte) 0, 0);
		assertThat(RouteTimetableRaptorPlanner.dominates(betterWarning, candidate, true, true)).isTrue();

		// 8. 모든 차원 동일: earlier == true 면 true, earlier == false 면 false
		var identical = new Label("st", 1000, 900, 2, List.of(), new int[] {0}, -1, (byte) 1, 0);
		assertThat(RouteTimetableRaptorPlanner.dominates(identical, candidate, true, true)).isTrue();
		assertThat(RouteTimetableRaptorPlanner.dominates(identical, candidate, true, false)).isFalse();
	}

	@Test
	@DisplayName("journeyItineraries RouteTimetable 오버로드 검증")
	void journeyItinerariesRouteTimetableOverload() {
		var planner = new RouteTimetableRaptorPlanner();
		var timetable = RouteTimetableRaptorPlannerOutOfStationTransferGoldenTest.timetable();
		var query = new JourneyRaptorQuery(
			"01ARZ3NDEKTSV4RRFFQ69G5FAV", "station-a", "station-d",
			new JourneyRaptorQuery.DepartAt(Instant.parse("2026-07-06T03:00:00Z")),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.SLOW,
			JourneyRequest.MobilityProfile.SLOW,
			JourneyRequest.ConstraintMode.NONE,
			1, 2, () -> false
		);
		var plan = planner.journeyItineraries(query, timetable);
		assertThat(plan).isNotNull();
		assertThat(plan.itineraries()).isNotEmpty();
	}

	@Test
	@DisplayName("DepartureEvent stopIndex 경계값 예외 검증")
	void departureEventOutOfBounds() {
		var planner = new RouteTimetableRaptorPlanner();
		var timetable = RouteTimetableRaptorPlannerOutOfStationTransferGoldenTest.timetable();
		var compiled = planner.compile(timetable);
		var scheduledTrip = compiled.scheduledTrip(0);
		org.assertj.core.api.Assertions.assertThatThrownBy(() ->
			new RouteTimetableRaptorPlanner.DepartureEvent(scheduledTrip, -1, 100)
		).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("stopIndex must address scheduledTrip");
	}

	@Test
	@DisplayName("Label 레코드 메서드, 방어적 복사 및 equals/hashCode/toString 검증")
	void labelDefensiveCopyAndRecordMethods() {
		int[] transitions = new int[] {10, 20};
		var label1 = new Label("st-a", 1000, 900, 1, List.of(), transitions, 5, (byte) 2, 300);
		transitions[0] = 999;
		assertThat(label1.accessTransitions()).containsExactly(10, 20);
		assertThat(label1.virtualCostSeconds()).isEqualTo(1300);

		int[] returned = label1.accessTransitions();
		returned[0] = 888;
		assertThat(label1.accessTransitions()).containsExactly(10, 20);

		var label2 = new Label("st-a", 1000, 900, 1, List.of(), new int[] {10, 20}, 5, (byte) 2, 300);
		assertThat(label1).isEqualTo(label2);
		assertThat(label1.equals(label1)).isTrue();
		assertThat(label1.equals(null)).isFalse();
		assertThat(label1.equals("not-a-label")).isFalse();
		assertThat(label1.hashCode()).isEqualTo(label2.hashCode());
		assertThat(label1.toString()).contains("st-a").contains("1000");

		assertThat(label1.equals(new Label("st-b", 1000, 900, 1, List.of(), new int[] {10, 20}, 5, (byte) 2, 300))).isFalse();
		assertThat(label1.equals(new Label("st-a", 9999, 900, 1, List.of(), new int[] {10, 20}, 5, (byte) 2, 300))).isFalse();
		assertThat(label1.equals(new Label("st-a", 1000, 9999, 1, List.of(), new int[] {10, 20}, 5, (byte) 2, 300))).isFalse();
		assertThat(label1.equals(new Label("st-a", 1000, 900, 9, List.of(), new int[] {10, 20}, 5, (byte) 2, 300))).isFalse();
		assertThat(label1.equals(new Label("st-a", 1000, 900, 1, List.of(), new int[] {10, 20}, 99, (byte) 2, 300))).isFalse();
		assertThat(label1.equals(new Label("st-a", 1000, 900, 1, List.of(), new int[] {10, 20}, 5, (byte) 9, 300))).isFalse();
		assertThat(label1.equals(new Label("st-a", 1000, 900, 1, List.of(), new int[] {10, 20}, 5, (byte) 2, 999))).isFalse();
		assertThat(label1.equals(new Label("st-a", 1000, 900, 1, List.of(), new int[] {99}, 5, (byte) 2, 300))).isFalse();

		var nullTransLabel = new Label("st-c", 500, 400, 0, List.of(), null, -1, (byte) 0, 0);
		assertThat(nullTransLabel.accessTransitions()).isEmpty();

		var eightArgLabel = new Label("st-d", 600, 500, 1, List.of(), new int[] {1}, 0, (byte) 0);
		assertThat(eightArgLabel.penaltySeconds()).isZero();
		assertThat(eightArgLabel.virtualCostSeconds()).isEqualTo(600);
	}

	@Test
	@DisplayName("CompiledRouteTimetable outOfStation 메서드 및 transition 선택 검증")
	void compiledRouteTimetableOutOfStationMethods() {
		var planner = new RouteTimetableRaptorPlanner();
		var timetable = RouteTimetableRaptorPlannerOutOfStationTransferGoldenTest.timetable();
		var compiled = planner.compile(timetable);
		assertThat(compiled.footpathsToStationLine(0, 0)).isNull();
		assertThat(compiled.isOutOfStationTransition(0)).isFalse();
		int[] cands = new int[] {0};
		int sel = compiled.selectTransition(cands, 0, false, false);
		assertThat(sel).isGreaterThanOrEqualTo(0);
	}

	@Test
	@DisplayName("ScanWorkspace isTargetDominatingDeparture 조기 가지치기 분기 검증")
	void scanWorkspaceTargetDominatingDeparture() {
		var workspace = new ScanWorkspace();
		workspace.prepare(5, 5, 5);
		int target = 3;
		workspace.setTargetStation(target);

		// 1. 타겟 도달 전 (UNREACHED): 어떤 departure도 지배하지 못함
		assertThat(workspace.isTargetDominatingDeparture(2, 5000)).isFalse();

		// 2. 타겟 도달 시각 5000초 설정 (warningState 0)
		workspace.bestTargetArrivalSeconds[0] = 5000;

		// station != targetStation: earliestDepartureSeconds >= best 이면 지배 (조기 종료 가능)
		assertThat(workspace.isTargetDominatingDeparture(2, 5000)).isTrue();
		assertThat(workspace.isTargetDominatingDeparture(2, 5001)).isTrue();
		assertThat(workspace.isTargetDominatingDeparture(2, 4999)).isFalse();

		// station == targetStation: earliestDepartureSeconds > best 일 때만 지배
		assertThat(workspace.isTargetDominatingDeparture(target, 5001)).isTrue();
		assertThat(workspace.isTargetDominatingDeparture(target, 5000)).isFalse();
		assertThat(workspace.isTargetDominatingDeparture(target, 4999)).isFalse();

		// 다중 warningState 활성화: 모든 reached target에 대해 earliestDepartureSeconds >= best 만족해야 함
		workspace.bestTargetArrivalSeconds[1] = 4000;
		// 4500초: warningState 1 (4000초)에는 >= 이지만, 4500 < 5000 (warningState 0)이므로 false
		assertThat(workspace.isTargetDominatingDeparture(2, 4500)).isFalse();
		// 5000초: warningState 0 (5000초) >= 5000 && warningState 1 (4000초) >= 5000 -> true!
		assertThat(workspace.isTargetDominatingDeparture(2, 5000)).isTrue();
	}

	@Test
	@DisplayName("bestReadyBoarding 노외 환승 조기 가지치기(Early Pruning) 분기 완전 커버리지 검증")
	void bestReadyBoardingEarlyPruningBranches() {
		var planner = new RouteTimetableRaptorPlanner();
		var timetable = RouteTimetableRaptorPlannerOutOfStationTransferGoldenTest.timetable();
		var compiled = planner.compile(timetable);

		var query = new JourneyRaptorQuery(
			"01ARZ3NDEKTSV4RRFFQ69G5FAV",
			"station-a",
			"station-d",
			new JourneyRaptorQuery.DepartAt(Instant.parse("2026-07-06T11:20:00Z")),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.SLOW,
			JourneyRequest.MobilityProfile.SLOW,
			JourneyRequest.ConstraintMode.NONE,
			1,
			2,
			() -> false
		);
		var input = RouteTimetableRaptorPlanner.scanInput(query);

		int stationC = compiled.stationIndex("station-c");
		int lineL2 = compiled.lineIndex("l2");
		int stationB = compiled.stationIndex("station-b");
		int lineL1 = compiled.lineIndex("l1");
		int targetStation = compiled.stationIndex("station-d");

		// 1. 조기 가지치기 실행 (footpathDominated && departure >= bestTargetArrival -> break)
		var ws1 = new ScanWorkspace();
		ws1.prepare(compiled.stationCount(), compiled.lineCount(), 10);
		ws1.setTargetStation(targetStation);
		ws1.bestTargetArrivalSeconds[0] = 50000;
		int slotB = ws1.slot(1, stationB, lineL1, 0);
		ws1.arrivalSeconds[slotB] = 60000;

		var ready1 = RouteTimetableRaptorPlanner.bestReadyBoarding(
			compiled, ws1, stationC, lineL2, 1, 0, 0, input, false, Integer.MAX_VALUE
		);
		assertThat(ready1).isNull();

		// 2. 조기 가지치기 미발생 (departure < bestTargetArrival -> 정상 readyBoarding 반환)
		var ws2 = new ScanWorkspace();
		ws2.prepare(compiled.stationCount(), compiled.lineCount(), 10);
		ws2.setTargetStation(targetStation);
		ws2.bestTargetArrivalSeconds[0] = 80000;
		int slotB2 = ws2.slot(1, stationB, lineL1, 0);
		ws2.arrivalSeconds[slotB2] = 50000;

		var ready2 = RouteTimetableRaptorPlanner.bestReadyBoarding(
			compiled, ws2, stationC, lineL2, 1, 0, 0, input, false, Integer.MAX_VALUE
		);
		assertThat(ready2).isNotNull();

		// 3. arrivalSeconds가 UNREACHED인 경우 (minDepartureForFootpath == Integer.MAX_VALUE)
		var ws3 = new ScanWorkspace();
		ws3.prepare(compiled.stationCount(), compiled.lineCount(), 10);
		ws3.setTargetStation(targetStation);
		ws3.bestTargetArrivalSeconds[0] = 50000;

		var ready3 = RouteTimetableRaptorPlanner.bestReadyBoarding(
			compiled, ws3, stationC, lineL2, 1, 0, 0, input, false, Integer.MAX_VALUE
		);
		assertThat(ready3).isNull();

		// 4. 타겟 미도달 상태에서 boardingDeadline 초과 케이스
		var ws4 = new ScanWorkspace();
		ws4.prepare(compiled.stationCount(), compiled.lineCount(), 10);
		ws4.setTargetStation(targetStation);
		int slotB4 = ws4.slot(1, stationB, lineL1, 0);
		ws4.arrivalSeconds[slotB4] = 60000;

		var ready4 = RouteTimetableRaptorPlanner.bestReadyBoarding(
			compiled, ws4, stationC, lineL2, 1, 0, 0, input, false, 50000
		);
		assertThat(ready4).isNull();

		// 5. 다중 warningState에서 첫 번째보다 늦은 두 번째 상태는 갱신되지 않음 (compare >= 0 분기 커버)
		var ws5 = new ScanWorkspace();
		ws5.prepare(compiled.stationCount(), compiled.lineCount(), 10);
		ws5.setTargetStation(targetStation);
		ws5.bestTargetArrivalSeconds[0] = 999999;
		int slotB50 = ws5.slot(1, stationB, lineL1, 0);
		int slotB51 = ws5.slot(1, stationB, lineL1, 1);
		ws5.arrivalSeconds[slotB50] = 50000;
		ws5.arrivalSeconds[slotB51] = 50001;

		var ready5 = RouteTimetableRaptorPlanner.bestReadyBoarding(
			compiled, ws5, stationC, lineL2, 1, 0, 0, input, false, Integer.MAX_VALUE
		);
		assertThat(ready5).isNotNull();
		assertThat(ready5).isEqualTo(ready2);
	}

	@Test
	@DisplayName("evaluateFootpathsIntoReady 외부 footpaths 루프 조기 차단(break FOOTPATHS_LOOP) 스코프 회귀 검증")
	void evaluateFootpathsEarlyPruningLoopScopeBreak() {
		var planner = new RouteTimetableRaptorPlanner();
		var base = RouteTimetableRaptorPlannerOutOfStationTransferGoldenTest.timetable();
		var baseAccess = base.routeAccessData();
		var nodes = new java.util.ArrayList<>(baseAccess.pathwayNodes());
		var edges = new java.util.ArrayList<>(baseAccess.pathwayEdges());
		var evidence = new java.util.ArrayList<>(baseAccess.routeEdgeEvidence());
		var rules = new java.util.ArrayList<>(baseAccess.transferRules());

		// base에는 station-b:l1 -> station-c:l2 (duration 600s, distance 400m)가 존재함.
		// 여기에 duration이 더 긴 두 번째 도보 환승(station-a:l1 -> station-c:l2, duration 900s)을 추가.
		String transferEdge2Id = "a-c-out-transfer-edge";
		edges.add(new LoadRouteTimetablePort.PathwayEdge(
			transferEdge2Id, "station-a:l1", "station-c:l2", 900, 600, false, false, 100,
			"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"
		));
		evidence.add(new LoadRouteTimetablePort.RouteEdgeEvidence(
			"a-c-transfer-evidence", "station-c", "l2", transferEdge2Id, "TRANSFER",
			"OFFICIAL_SOURCE", "VERIFIED", true, null
		));
		rules.add(new LoadRouteTimetablePort.TransferRule(
			"a-c-transfer-rule", "station-a", "l1", "station-c", "l2", "OUT_OF_STATION",
			900, transferEdge2Id, transferEdge2Id, "VERIFIED"
		));

		var multiFootpathAccess = new LoadRouteTimetablePort.RouteAccessData(nodes, edges, rules, evidence);
		var multiFootpathTimetable = new LoadRouteTimetablePort.RouteTimetable(
			base.serviceCalendars(), base.serviceCalendarDates(), base.transitRoutes(),
			base.transitTrips(), base.transitStopTimes(), base.transitFrequencies(),
			base.officialFares(), base.feedEndDate(), multiFootpathAccess
		);

		var compiled = planner.compile(multiFootpathTimetable);
		int stationA = compiled.stationIndex("station-a");
		int stationB = compiled.stationIndex("station-b");
		int stationC = compiled.stationIndex("station-c");
		int targetStation = compiled.stationIndex("station-d");
		int lineL1 = compiled.lineIndex("l1");
		int lineL2 = compiled.lineIndex("l2");

		// footpathsToStationLine이 duration 오름차순으로 정렬되어 있는지 확인 (base의 2개 + 추가 1개 = 총 3개)
		var footpaths = compiled.footpathsToStationLine(stationC, lineL2);
		assertThat(footpaths).hasSize(3);
		assertThat(footpaths[0].fromStation()).isEqualTo(stationB); // duration 600
		assertThat(footpaths[2].fromStation()).isEqualTo(stationA); // duration 900

		var query = new JourneyRaptorQuery(
			"01ARZ3NDEKTSV4RRFFQ69G5FAV", "station-a", "station-d",
			new JourneyRaptorQuery.DepartAt(Instant.parse("2026-07-06T11:20:00Z")),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.SLOW,
			JourneyRequest.MobilityProfile.SLOW,
			JourneyRequest.ConstraintMode.NONE,
			1, 2, () -> false
		);
		var input = RouteTimetableRaptorPlanner.scanInput(query);

		var ws = new ScanWorkspace();
		ws.prepare(compiled.stationCount(), compiled.lineCount(), 10);
		ws.setTargetStation(targetStation);

		// 타깃 목적지 도착 최적 시각: 50,000초
		ws.bestTargetArrivalSeconds[0] = 50000;

		// 첫 번째 도보 간선(fromStation=B)의 출발 시각: 60,000 + 600 = 60,600초 >= 50,000초 (타깃 지배 성립)
		int slotB = ws.slot(1, stationB, lineL1, 0);
		ws.arrivalSeconds[slotB] = 60000;

		// 두 번째 도보 간선(fromStation=A)의 출발 시각: 40,000 + 900 = 40,900초 (< 50,000초)
		// 첫 번째 간선이 지배되었더라도, 출발역이 다른 두 번째 간선은 타깃보다 빠른 출발 시각을 가지므로
		// 조기 차단으로 인해 누락(path-loss)되지 않고 정상적으로 보존되어야 함.
		int slotA = ws.slot(1, stationA, lineL1, 0);
		ws.arrivalSeconds[slotA] = 40000;

		var ready = RouteTimetableRaptorPlanner.bestReadyBoarding(
			compiled, ws, stationC, lineL2, 1, 0, 0, input, false, Integer.MAX_VALUE
		);
		assertThat(ready).isNotNull();
		assertThat(ready.earliestDepartureSeconds()).isEqualTo(40618);
	}

	@Test
	@DisplayName("모든 잔여 도보 간선이 타깃 지배될 때 evaluateFootpaths 조기 차단 정상 작동 검증")
	void evaluateFootpathsEarlyPruningWhenAllRemainingFootpathsDominated() {
		var planner = new RouteTimetableRaptorPlanner();
		var base = RouteTimetableRaptorPlannerOutOfStationTransferGoldenTest.timetable();
		var baseAccess = base.routeAccessData();
		var nodes = new java.util.ArrayList<>(baseAccess.pathwayNodes());
		var edges = new java.util.ArrayList<>(baseAccess.pathwayEdges());
		var evidence = new java.util.ArrayList<>(baseAccess.routeEdgeEvidence());
		var rules = new java.util.ArrayList<>(baseAccess.transferRules());

		String transferEdge2Id = "a-c-out-transfer-edge";
		edges.add(new LoadRouteTimetablePort.PathwayEdge(
			transferEdge2Id, "station-a:l1", "station-c:l2", 900, 600, false, false, 100,
			"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"
		));
		evidence.add(new LoadRouteTimetablePort.RouteEdgeEvidence(
			"a-c-transfer-evidence", "station-c", "l2", transferEdge2Id, "TRANSFER",
			"OFFICIAL_SOURCE", "VERIFIED", true, null
		));
		rules.add(new LoadRouteTimetablePort.TransferRule(
			"a-c-transfer-rule", "station-a", "l1", "station-c", "l2", "OUT_OF_STATION",
			900, transferEdge2Id, transferEdge2Id, "VERIFIED"
		));

		var multiFootpathAccess = new LoadRouteTimetablePort.RouteAccessData(nodes, edges, rules, evidence);
		var multiFootpathTimetable = new LoadRouteTimetablePort.RouteTimetable(
			base.serviceCalendars(), base.serviceCalendarDates(), base.transitRoutes(),
			base.transitTrips(), base.transitStopTimes(), base.transitFrequencies(),
			base.officialFares(), base.feedEndDate(), multiFootpathAccess
		);

		var compiled = planner.compile(multiFootpathTimetable);
		int stationA = compiled.stationIndex("station-a");
		int stationB = compiled.stationIndex("station-b");
		int stationC = compiled.stationIndex("station-c");
		int targetStation = compiled.stationIndex("station-d");
		int lineL1 = compiled.lineIndex("l1");
		int lineL2 = compiled.lineIndex("l2");

		var query = new JourneyRaptorQuery(
			"01ARZ3NDEKTSV4RRFFQ69G5FAV", "station-a", "station-d",
			new JourneyRaptorQuery.DepartAt(Instant.parse("2026-07-06T11:20:00Z")),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.SLOW,
			JourneyRequest.MobilityProfile.SLOW,
			JourneyRequest.ConstraintMode.NONE,
			1, 2, () -> false
		);
		var input = RouteTimetableRaptorPlanner.scanInput(query);

		var ws = new ScanWorkspace();
		ws.prepare(compiled.stationCount(), compiled.lineCount(), 10);
		ws.setTargetStation(targetStation);
		ws.bestTargetArrivalSeconds[0] = 50000;

		// 첫 번째 간선(B): 60,000 + 600 = 60,600초 >= 50,000초 (타깃 지배)
		int slotB = ws.slot(1, stationB, lineL1, 0);
		ws.arrivalSeconds[slotB] = 60000;

		// 두 번째 간선(A): 55,000 + 900 = 55,900초 >= 50,000초 (타깃 지배)
		int slotA = ws.slot(1, stationA, lineL1, 0);
		ws.arrivalSeconds[slotA] = 55000;

		// 모든 잔여 도보 간선이 지배되므로 조기 차단되어 ready가 null이어야 함
		var ready = RouteTimetableRaptorPlanner.bestReadyBoarding(
			compiled, ws, stationC, lineL2, 1, 0, 0, input, false, Integer.MAX_VALUE
		);
		assertThat(ready).isNull();
	}
}

