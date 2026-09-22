package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.JourneyAccessKind;
import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.JourneyAccessProjection;
import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.Label;
import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.OutOfStationFootpath;
import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.ScanWorkspace;
import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.SearchRouteV2Command;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.domain.ConstraintMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
	@DisplayName("isFeedStale 및 nextServiceTime RouteTimetable 오버로드 검증")
	void isFeedStaleAndNextServiceTimeOverloads() {
		var planner = new RouteTimetableRaptorPlanner();
		var timetable = RouteTimetableRaptorPlannerOutOfStationTransferGoldenTest.timetable();
		var command = new SearchRouteV2Command(
			"station-a", "station-d",
			OffsetDateTime.of(2026, 7, 6, 12, 0, 0, 0, ZoneOffset.ofHours(9)),
			MobilityType.SENIOR, ConstraintMode.ALLOW_WITH_WARNINGS, false, 1, 2
		);
		assertThat(planner.isFeedStale(command, timetable)).isFalse();
		assertThat(planner.nextServiceTime(command, timetable)).isNotNull();
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
		assertThat(compiled.outOfStationFootpaths()).isNotNull();
		assertThat(compiled.isOutOfStationTransition(0)).isFalse();
		int[] cands = new int[] {0};
		int sel = compiled.selectTransition(cands, 0, false, false);
		assertThat(sel).isGreaterThanOrEqualTo(0);
	}
}
