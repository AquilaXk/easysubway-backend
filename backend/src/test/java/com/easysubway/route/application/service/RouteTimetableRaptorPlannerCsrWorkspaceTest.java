package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.ScanWorkspacePool;

import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteAccessData;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendar;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitRoute;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitStopTime;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitTrip;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("#22 Station-Line CSR sparse indexing & O(1) lightweight workspace")
class RouteTimetableRaptorPlannerCsrWorkspaceTest {

	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 7, 6);
	private static final OffsetDateTime DEPARTURE = OffsetDateTime.of(2026, 7, 6, 7, 55, 0, 0, ZoneOffset.ofHours(9));

	@Test
	@DisplayName("CompiledTimetable이 역별 정차 노선을 CSR 구조로 올바르게 인덱싱한다")
	void compiledTimetableBuildsCsrStationLines() {
		var planner = new RouteTimetableRaptorPlanner();
		var timetable = multiLineTimetable();
		var compiled = planner.compile(timetable);

		int stationCount = compiled.stationCount();
		assertThat(stationCount).isEqualTo(4);

		int[] offsets = compiled.stationLineOffsets();
		int[] lines = compiled.stationLines();
		assertThat(offsets).hasSize(stationCount + 1);
		assertThat(offsets[0]).isEqualTo(0);

		// Find index of sta-1, sta-transfer, sta-2, sta-3
		int sta1 = compiled.stationIndex("sta-1");
		int staTransfer = compiled.stationIndex("sta-transfer");
		int sta2 = compiled.stationIndex("sta-2");
		int sta3 = compiled.stationIndex("sta-3");
		int line1 = compiled.lineIndex("line-1");
		int line2 = compiled.lineIndex("line-2");

		// station sta-1: line-1 only
		assertThat(compiled.stationLineCount(sta1)).isEqualTo(1);
		assertThat(compiled.stationLine(sta1, 0)).isEqualTo(line1);
		assertThat(compiled.stationLineLocalIndex(sta1, line1)).isEqualTo(0);
		assertThat(compiled.stationLineLocalIndex(sta1, line2)).isEqualTo(-1);

		// station sta-transfer: transfer station with line-1 and line-2
		assertThat(compiled.stationLineCount(staTransfer)).isEqualTo(2);
		assertThat(compiled.stationLineLocalIndex(staTransfer, line1)).isGreaterThanOrEqualTo(0);
		assertThat(compiled.stationLineLocalIndex(staTransfer, line2)).isGreaterThanOrEqualTo(0);

		// station sta-3: line-2 only
		assertThat(compiled.stationLineCount(sta3)).isEqualTo(1);
		assertThat(compiled.stationLine(sta3, 0)).isEqualTo(line2);
		assertThat(compiled.stationLineLocalIndex(sta3, line1)).isEqualTo(-1);
		assertThat(compiled.stationLineLocalIndex(sta3, line2)).isEqualTo(0);

		// total station slots = sum of (lines per station + 1 for noIncomingLine)
		int expectedSlots = (1 + 1) + (2 + 1) + (1 + 1) + (1 + 1); // 9 station slots
		assertThat(compiled.totalStationSlots()).isEqualTo(expectedSlots);
	}

	@Test
	@DisplayName("ScanWorkspace가 CompiledTimetable의 CSR을 활용해 O(1) 초기화와 희소 슬롯을 관리한다")
	void scanWorkspaceCsrSlotAndEpochManagement() {
		var planner = new RouteTimetableRaptorPlanner();
		var timetable = multiLineTimetable();
		var compiled = planner.compile(timetable);

		var workspace = new RouteTimetableRaptorPlanner.ScanWorkspace();
		workspace.prepare(compiled);

		int epoch1 = workspace.epoch();
		assertThat(epoch1).isPositive();

		int sta1 = compiled.stationIndex("sta-1");
		int line1 = compiled.lineIndex("line-1");
		int line2 = compiled.lineIndex("line-2");

		// Check slot mapping for station sta-1 with noIncomingLine
		int slotOrigin = workspace.slot(0, sta1, workspace.noIncomingLine(), 0);
		assertThat(slotOrigin).isGreaterThanOrEqualTo(0);
		assertThat(slotOrigin).isLessThan(workspace.totalSlots());

		// Inactive line lookup returns unreached dummy slot or safe index
		int invalidSlot = workspace.slot(1, sta1, line2, 0); // line-2 does not stop at sta-1
		assertThat(workspace.arrivalSeconds[invalidSlot]).isEqualTo(RouteTimetableRaptorPlanner.UNREACHED);

		// Touch origin slot properly via real production entrypoint
		assertThat(workspace.improveOrigin(sta1, 28800)).isTrue();
		assertThat(workspace.arrivalSeconds[slotOrigin]).isEqualTo(28800);
		assertThat(workspace.touchedSlotCount()).isEqualTo(1);

		// Set an untouched canary slot to prove that prepare performs selective reset, NOT a full array wipe
		int sta2 = compiled.stationIndex("sta-2");
		int canarySlot = workspace.slot(0, sta2, workspace.noIncomingLine(), 0);
		workspace.arrivalSeconds[canarySlot] = 12345;

		// Re-prepare workspace: epoch should advance, and touched slots should be cleared without full array wipe
		workspace.prepare(compiled);
		int epoch2 = workspace.epoch();
		assertThat(epoch2).isEqualTo(epoch1 + 1);
		assertThat(workspace.touchedSlotCount()).isZero();
		assertThat(workspace.arrivalSeconds[slotOrigin]).isEqualTo(RouteTimetableRaptorPlanner.UNREACHED);
		// Untouched canary slot must NOT be wiped by selective reset
		assertThat(workspace.arrivalSeconds[canarySlot])
			.as("Untouched canary slot must NOT be wiped when touchedSlotCount > 0 triggers selective reset")
			.isEqualTo(12345);

		// Subsequent prepare with touchedSlotCount == 0 triggers fallback full wipe, clearing the canary
		workspace.prepare(compiled);
		int epoch3 = workspace.epoch();
		assertThat(epoch3).isEqualTo(epoch2 + 1);
		assertThat(workspace.arrivalSeconds[canarySlot])
			.as("Canary slot must be wiped when touchedSlotCount == 0 triggers full array wipe")
			.isEqualTo(RouteTimetableRaptorPlanner.UNREACHED);
	}

	@Test
	@DisplayName("이전 쿼리에서 touchedSlotCount > 0인 상태에서 버퍼가 재할당되어도 모든 슬롯이 UNREACHED로 초기화된다")
	void scanWorkspaceReallocationInitializesAllSlotsEvenWhenPreviouslyTouched() {
		var workspace = new RouteTimetableRaptorPlanner.ScanWorkspace();
		// Initial smaller timetable
		workspace.prepare(2, 1, 1);
		int slot0 = workspace.slot(0, 0, workspace.noIncomingLine(), 0);
		assertThat(workspace.improveOrigin(0, 28800)).isTrue();
		assertThat(workspace.arrivalSeconds[slot0]).isEqualTo(28800);
		assertThat(workspace.touchedSlotCount()).isPositive();

		// Re-prepare with larger timetable requiring buffer expansion
		workspace.prepare(10, 2, 2);

		// After reallocation, touchedSlotCount must be 0 and all slots must be properly initialized
		assertThat(workspace.touchedSlotCount()).isZero();
		for (int s = 0; s < workspace.arrivalSeconds.length; s += 1) {
			assertThat(workspace.arrivalSeconds[s])
				.as("Slot %d must be UNREACHED after reallocation", s)
				.isEqualTo(RouteTimetableRaptorPlanner.UNREACHED);
			assertThat(workspace.parentTrip(s))
				.as("Slot %d parentTrip must be -1 after reallocation", s)
				.isEqualTo(-1);
			assertThat(workspace.warningBits[s])
				.as("Slot %d warningBits must be 0 after reallocation", s)
				.isZero();
		}

		// improveOrigin must succeed on the expanded workspace
		int newOriginSlot = workspace.slot(0, 1, workspace.noIncomingLine(), 0);
		assertThat(workspace.improveOrigin(1, 30600)).isTrue();
		assertThat(workspace.arrivalSeconds[newOriginSlot]).isEqualTo(30600);
	}

	@Test
	@DisplayName("쿼리 간 시간표 확장으로 버퍼 재할당이 발생해도 자가 치유 지연 없이 첫 쿼리부터 정상 탐색된다")
	void journeySearchSucceedsImmediatelyAfterBufferReallocationWithoutSelfHealingLag() {
		var pool = new RouteTimetableRaptorPlanner.ScanWorkspacePool(1);
		var planner = new RouteTimetableRaptorPlanner(pool);
		var smallTimetable = smallTimetable();
		var compiledSmall = planner.compile(smallTimetable);

		// Query 1: Run search on small timetable on the current thread
		var query1 = new JourneyRaptorQuery(
			"01ARZ3NDEKTSV4RRFFQ69G5FA1",
			"sta-1",
			"sta-transfer",
			new JourneyRaptorQuery.DepartAt(DEPARTURE.toInstant()),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.SLOW,
			JourneyRequest.MobilityProfile.SLOW,
			JourneyRequest.ConstraintMode.NONE,
			1,
			2,
			() -> false
		);
		var result1 = planner.journeyItineraries(query1, compiledSmall);
		assertThat(result1.itineraries()).isNotEmpty();

		// Query 2: Immediately execute query on larger timetable on the same thread
		// This forces buffer reallocation while touchedSlotCount > 0 in ThreadLocal<ScanWorkspace>.
		var largeTimetable = multiLineTimetable();
		var compiledLarge = planner.compile(largeTimetable);

		var query2 = new JourneyRaptorQuery(
			"01ARZ3NDEKTSV4RRFFQ69G5FA2",
			"sta-1",
			"sta-3",
			new JourneyRaptorQuery.DepartAt(DEPARTURE.toInstant()),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.SLOW,
			JourneyRequest.MobilityProfile.SLOW,
			JourneyRequest.ConstraintMode.NONE,
			1,
			2,
			() -> false
		);
		var result2 = planner.journeyItineraries(query2, compiledLarge);

		// Must succeed immediately on the very first query without transient failure or self-healing delay
		assertThat(result2.itineraries())
			.as("Search must find itineraries immediately upon buffer reallocation without transient failure")
			.isNotEmpty();
		var itinerary = result2.itineraries().getFirst();
		var rides = itinerary.legs().stream()
			.filter(RouteTimetableRaptorPlanner.JourneyRideProjection.class::isInstance)
			.map(RouteTimetableRaptorPlanner.JourneyRideProjection.class::cast)
			.toList();
		assertThat(rides).hasSize(2);
		assertThat(rides.get(0).lineId()).isEqualTo("line-1");
		assertThat(rides.get(1).lineId()).isEqualTo("line-2");

		// Query 3: Consecutive query on large timetable also succeeds
		var result3 = planner.journeyItineraries(query2, compiledLarge);
		assertThat(result3.itineraries()).isNotEmpty();
	}

	@Test
	@DisplayName("CSR 희소 순회 경로 탐색 결과가 환승 경로를 정상 탐색한다")
	void csrRoutingFindsValidTransferPath() {
		var planner = new RouteTimetableRaptorPlanner();
		var timetable = multiLineTimetable();
		var compiled = planner.compile(timetable);

		var query = new JourneyRaptorQuery(
			"01ARZ3NDEKTSV4RRFFQ69G5FAV",
			"sta-1",
			"sta-3",
			new JourneyRaptorQuery.DepartAt(DEPARTURE.toInstant()),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.SLOW,
			JourneyRequest.MobilityProfile.SLOW,
			JourneyRequest.ConstraintMode.NONE,
			1,
			2,
			() -> false
		);

		var result = planner.journeyItineraries(query, compiled);
		assertThat(result.itineraries()).isNotEmpty();
		var itinerary = result.itineraries().getFirst();
		assertThat(itinerary.legs()).hasSize(3); // 승차·환승·승차(#454: 진입·하차 없음)
		var rides = itinerary.legs().stream()
			.filter(RouteTimetableRaptorPlanner.JourneyRideProjection.class::isInstance)
			.map(RouteTimetableRaptorPlanner.JourneyRideProjection.class::cast)
			.toList();
		assertThat(rides).hasSize(2);
		assertThat(rides.get(0).lineId()).isEqualTo("line-1");
		assertThat(rides.get(1).lineId()).isEqualTo("line-2");
	}

	private static RouteTimetable multiLineTimetable() {
		var calendar = new ServiceCalendar("cal-1", true, true, true, true, true, true, true, SERVICE_DATE, SERVICE_DATE, "Asia/Seoul");
		var route1 = new TransitRoute("route-1", "line-1", "1", "1호선", "up", "Asia/Seoul");
		var route2 = new TransitRoute("route-2", "line-2", "2", "2호선", "up", "Asia/Seoul");

		// Line 1: sta-1 -> sta-transfer -> sta-2
		var trip1 = new TransitTrip("trip-l1", "route-1", "cal-1", "head-1", "0", "LOCAL", 28800);
		var st1 = new TransitStopTime("trip-l1", 1, "sta-1", "line-1", 28800, 28860, 0, 0);
		var st2 = new TransitStopTime("trip-l1", 2, "sta-transfer", "line-1", 29100, 29160, 1, 0);
		var st3 = new TransitStopTime("trip-l1", 3, "sta-2", "line-1", 29400, 29460, 2, 0);

		// Line 2: sta-transfer -> sta-3
		var trip2 = new TransitTrip("trip-l2", "route-2", "cal-1", "head-2", "0", "LOCAL", 28800);
		var st4 = new TransitStopTime("trip-l2", 1, "sta-transfer", "line-2", 29280, 29340, 0, 0);
		var st5 = new TransitStopTime("trip-l2", 2, "sta-3", "line-2", 29580, 29640, 1, 0);

		return new RouteTimetable(
			List.of(calendar),
			List.of(),
			List.of(route1, route2),
			List.of(trip1, trip2),
			List.of(st1, st2, st3, st4, st5),
			List.of(),
			List.of(),
			null,
			multiLineAccess()
		);
	}

	private static RouteAccessData multiLineAccess() {
		var edges = List.of(
			new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayEdge(
				"entry", "entrance", "platform-sta1", 120, 60, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"),
			new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayEdge(
				"transfer", "platform-transfer-l1", "platform-transfer-l2", 60, 60, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"),
			new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayEdge(
				"exit", "platform-sta3", "outside", 60, 40, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"));
		var evidence = List.of(
			new com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteEdgeEvidence(
				"entry-evidence", "sta-1", "line-1", "entry", "ENTRY",
				"OFFICIAL_SOURCE", "VERIFIED", true, null),
			new com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteEdgeEvidence(
				"transfer-evidence", "sta-transfer", "line-2", "transfer", "TRANSFER",
				"OFFICIAL_SOURCE", "VERIFIED", true, null),
			new com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteEdgeEvidence(
				"exit-evidence", "sta-3", "line-2", "exit", "EXIT",
				"OFFICIAL_SOURCE", "VERIFIED", true, null));
		var transferRule = new com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransferRule(
			"transfer-rule", "sta-transfer", "line-1", "sta-transfer", "line-2",
			"IN_STATION", 60, "transfer", "transfer", "VERIFIED");
		return new RouteAccessData(
			List.of(
				new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode("entrance", "sta-1", null, "ENTRANCE"),
				new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode("platform-sta1", "sta-1", "line-1", "PLATFORM"),
				new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode("platform-transfer-l1", "sta-transfer", "line-1", "PLATFORM"),
				new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode("platform-transfer-l2", "sta-transfer", "line-2", "PLATFORM"),
				new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode("platform-sta3", "sta-3", "line-2", "PLATFORM"),
				new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode("outside", "sta-3", null, "EXIT")),
			edges,
			List.of(transferRule),
			evidence
		);
	}

	private static RouteTimetable smallTimetable() {
		var calendar = new ServiceCalendar("cal-1", true, true, true, true, true, true, true, SERVICE_DATE, SERVICE_DATE, "Asia/Seoul");
		var route1 = new TransitRoute("route-1", "line-1", "1", "1호선", "up", "Asia/Seoul");

		// Line 1: sta-1 -> sta-transfer
		var trip1 = new TransitTrip("trip-l1", "route-1", "cal-1", "head-1", "0", "LOCAL", 28800);
		var st1 = new TransitStopTime("trip-l1", 1, "sta-1", "line-1", 28800, 28860, 0, 0);
		var st2 = new TransitStopTime("trip-l1", 2, "sta-transfer", "line-1", 29100, 29160, 1, 0);

		var edges = List.of(
			new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayEdge(
				"entry", "entrance", "platform-sta1", 120, 60, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"),
			new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayEdge(
				"exit", "platform-transfer-l1", "outside", 60, 40, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"));
		var evidence = List.of(
			new com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteEdgeEvidence(
				"entry-evidence", "sta-1", "line-1", "entry", "ENTRY",
				"OFFICIAL_SOURCE", "VERIFIED", true, null),
			new com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteEdgeEvidence(
				"exit-evidence", "sta-transfer", "line-1", "exit", "EXIT",
				"OFFICIAL_SOURCE", "VERIFIED", true, null));
		return new RouteTimetable(
			List.of(calendar),
			List.of(),
			List.of(route1),
			List.of(trip1),
			List.of(st1, st2),
			List.of(),
			List.of(),
			null,
			new RouteAccessData(
				List.of(
					new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode("entrance", "sta-1", null, "ENTRANCE"),
					new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode("platform-sta1", "sta-1", "line-1", "PLATFORM"),
					new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode("platform-transfer-l1", "sta-transfer", "line-1", "PLATFORM"),
					new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode("outside", "sta-transfer", null, "EXIT")),
				edges,
				List.of(),
				evidence
			)
		);
	}
}
