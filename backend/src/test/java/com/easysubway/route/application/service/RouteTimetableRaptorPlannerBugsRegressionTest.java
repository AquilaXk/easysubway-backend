package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteAccessData;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendar;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitRoute;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitStopTime;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitTrip;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("#368 RAPTOR BMRAP circular loop, integer overflow, and workspace state regression tests")
class RouteTimetableRaptorPlannerBugsRegressionTest {

	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 7, 6);

	@Test
	@DisplayName("순환선(Loop pattern: A -> B -> C -> A)에서 목적지 A에 대한 역방향 하한선이 이전 역들로 정상 전파된다")
	void circularPatternBMRAPLowerBoundsPropagateToAllPredecessors() {
		var planner = new RouteTimetableRaptorPlanner();
		var timetable = circularTimetable();
		var compiled = planner.compile(timetable);

		int sta1 = compiled.stationIndex("sta-1");
		int sta2 = compiled.stationIndex("sta-2");
		int sta3 = compiled.stationIndex("sta-3");

		int[] lowerBounds = RouteTimetableRaptorPlanner.computeStationLowerBounds(compiled, sta1);

		assertThat(lowerBounds[sta1]).isEqualTo(0);
		// sta-3 to sta-1: running time is 29400 - 29200 = 200s
		assertThat(lowerBounds[sta3]).isLessThanOrEqualTo(200);
		// sta-2 to sta-1: running time is 29400 - 29000 = 400s
		assertThat(lowerBounds[sta2]).isLessThanOrEqualTo(400);
	}

	@Test
	@DisplayName("UNREACHED 값 또는 큰 시각 연산 시 정수 오버플로로 인해 조기 차단이 오작동하지 않는다")
	void integerOverflowGuardsInDominationAndLowerBounds() {
		var planner = new RouteTimetableRaptorPlanner();
		var timetable = circularTimetable();
		var compiled = planner.compile(timetable);

		var workspace = new RouteTimetableRaptorPlanner.ScanWorkspace();
		workspace.prepare(compiled);

		int sta1 = compiled.stationIndex("sta-1");
		int sta2 = compiled.stationIndex("sta-2");
		int[] lb = new int[compiled.stationCount()];
		lb[sta2] = 300;
		workspace.setTargetStation(sta1, lb);

		// Target has been reached at 30,000s
		workspace.bestTargetArrivalSeconds[0] = 30000;

		// When candidate is UNREACHED (Integer.MAX_VALUE), it must be dominated, not wrap to negative
		assertThat(workspace.isDominatedByTarget(sta2, RouteTimetableRaptorPlanner.UNREACHED, 0)).isTrue();

		// When departure is UNREACHED, target must dominate it
		assertThat(workspace.isTargetDominatingDeparture(sta2, RouteTimetableRaptorPlanner.UNREACHED)).isTrue();
	}

	@Test
	@DisplayName("미정차 노선에 대한 relax 호출이 공유 dummyUnreachedSlot 상태를 오염시키지 않는다")
	void dummyUnreachedSlotCannotBePollutedByRelax() {
		var planner = new RouteTimetableRaptorPlanner();
		var timetable = circularTimetable();
		var compiled = planner.compile(timetable);

		var workspace = new RouteTimetableRaptorPlanner.ScanWorkspace();
		workspace.prepare(compiled);

		int sta1 = compiled.stationIndex("sta-1");
		// line-99 is an unserved/invalid line for sta-1
		int unservedLine = 99;

		int invalidSlot = workspace.slot(1, sta1, unservedLine, 0);
		assertThat(workspace.arrivalSeconds[invalidSlot]).isEqualTo(RouteTimetableRaptorPlanner.UNREACHED);

		// Attempt to relax on unserved line
		workspace.relax(sta1, 1, unservedLine, 35000, 1, 0, 1, 0, 0, (byte) 0);

		// The dummy slot must NOT be polluted
		assertThat(workspace.arrivalSeconds[invalidSlot]).isEqualTo(RouteTimetableRaptorPlanner.UNREACHED);
	}

	@Test
	@DisplayName("entryTransition과 exitTransition에 음수 인덱스 전달 시 예외 없이 안전하게 -1을 반환한다")
	void accessTransitionsEntryAndExitBoundsSafe() {
		var planner = new RouteTimetableRaptorPlanner();
		var timetable = circularTimetable();
		var compiled = planner.compile(timetable);

		assertThatNoException().isThrownBy(() -> {
			int entry = compiled.entryTransition(-1, 0, 1, false, false);
			assertThat(entry).isEqualTo(-1);
		});

		assertThatNoException().isThrownBy(() -> {
			int entry = compiled.entryTransition(0, -1, 1, false, false);
			assertThat(entry).isEqualTo(-1);
		});

		assertThatNoException().isThrownBy(() -> {
			int exit = compiled.exitTransition(-1, 0, 1, false, false);
			assertThat(exit).isEqualTo(-1);
		});

		assertThatNoException().isThrownBy(() -> {
			int exit = compiled.exitTransition(0, -1, 1, false, false);
			assertThat(exit).isEqualTo(-1);
		});
	}

	@Test
	@DisplayName("ScanWorkspace가 relax/improveOrigin으로 변경된 슬롯만 추적하여 다음 prepare 시 O(touched)로 초기화한다")
	void workspacePrepareClearsTouchedSlotsLazilyWithoutFullWipe() {
		var planner = new RouteTimetableRaptorPlanner();
		var timetable = circularTimetable();
		var compiled = planner.compile(timetable);

		var workspace = new RouteTimetableRaptorPlanner.ScanWorkspace();
		workspace.prepare(compiled);

		int sta1 = compiled.stationIndex("sta-1");
		int sta2 = compiled.stationIndex("sta-2");
		int line = compiled.lineIndex("line-circ");

		// Improve origin: touches origin slot
		workspace.improveOrigin(sta1, 28800);
		int originSlot = workspace.slot(0, sta1, workspace.noIncomingLine(), 0);
		assertThat(workspace.arrivalSeconds[originSlot]).isEqualTo(28800);

		// Relax sta-2: touches sta-2 slot
		workspace.relax(sta2, 1, line, 29000, 0, 0, 1, 0, originSlot, (byte) 0);
		int sta2Slot = workspace.slot(1, sta2, line, 0);
		assertThat(workspace.arrivalSeconds[sta2Slot]).isEqualTo(29000);

		// Re-prepare workspace: should advance epoch and reset touched slots to UNREACHED
		workspace.prepare(compiled);
		assertThat(workspace.arrivalSeconds[originSlot]).isEqualTo(RouteTimetableRaptorPlanner.UNREACHED);
		assertThat(workspace.arrivalSeconds[sta2Slot]).isEqualTo(RouteTimetableRaptorPlanner.UNREACHED);
	}

	private static RouteTimetable circularTimetable() {
		var calendar = new ServiceCalendar("cal-circ", true, true, true, true, true, true, true, SERVICE_DATE, SERVICE_DATE, "Asia/Seoul");
		var route = new TransitRoute("route-circ", "line-circ", "2", "2호선순환", "circle", "Asia/Seoul");

		// Circular pattern: sta-1 -> sta-2 -> sta-3 -> sta-1
		var trip = new TransitTrip("trip-c1", "route-circ", "cal-circ", "head-circ", "0", "LOCAL", 28800);
		var st1 = new TransitStopTime("trip-c1", 1, "sta-1", "line-circ", 28800, 28860, 0, 0);
		var st2 = new TransitStopTime("trip-c1", 2, "sta-2", "line-circ", 29000, 29060, 1, 0);
		var st3 = new TransitStopTime("trip-c1", 3, "sta-3", "line-circ", 29200, 29260, 2, 0);
		var st4 = new TransitStopTime("trip-c1", 4, "sta-1", "line-circ", 29400, 29460, 3, 0);

		return new RouteTimetable(
			List.of(calendar),
			List.of(),
			List.of(route),
			List.of(trip),
			List.of(st1, st2, st3, st4),
			List.of(),
			List.of(),
			null,
			circularAccess()
		);
	}

	private static RouteAccessData circularAccess() {
		var edges = List.of(
			new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayEdge(
				"entry", "entrance", "platform-sta1", 120, 60, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"),
			new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayEdge(
				"exit", "platform-sta1", "outside", 60, 40, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"));
		var evidence = List.of(
			new com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteEdgeEvidence(
				"entry-evidence", "sta-1", "line-circ", "entry", "ENTRY",
				"OFFICIAL_SOURCE", "VERIFIED", true, null),
			new com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteEdgeEvidence(
				"exit-evidence", "sta-1", "line-circ", "exit", "EXIT",
				"OFFICIAL_SOURCE", "VERIFIED", true, null));
		return new RouteAccessData(
			List.of(
				new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode("entrance", "sta-1", null, "ENTRANCE"),
				new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode("platform-sta1", "sta-1", "line-circ", "PLATFORM"),
				new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode("outside", "sta-1", null, "EXIT")),
			edges,
			List.of(),
			evidence
		);
	}
}
