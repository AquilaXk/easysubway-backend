package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;

@DisplayName("AccessTransitions sparse representation 검증 (#21)")
class RouteTimetableRaptorPlannerSparseTransitionsTest {

	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 7, 6);

	@Test
	@DisplayName("dense stationCount x lineCount x lineCount 대신 실제 전이 키만 희소하게 할당한다")
	void allocatesOnlyActualTransferKeysInsteadOfDenseProduct() {
		var planner = new RouteTimetableRaptorPlanner();
		var timetable = createMultiLineTransferTimetable();
		var compiled = planner.compile(timetable);

		int stationCount = compiled.coveredStationIds().size();
		int lineCount = timetable.transitRoutes().size();
		int denseProduct = stationCount * lineCount * lineCount;

		// 3개 역, 2개 노선 -> dense 곱은 3 * 2 * 2 = 12
		// station-1 (1개: L1->L1), station-2 (4개: L1->L1, L1->L2, L2->L1, L2->L2), station-3 (1개: L2->L2) -> 총 6개
		assertThat(compiled.allocatedTransferSlotCount())
			.isLessThan(denseProduct)
			.isEqualTo(6);

		// 유효한 환승 조회는 정상 후보 반환
		int station2 = compiled.stationIndex("station-2");
		int line1 = compiled.lineIndex("line-1");
		int line2 = compiled.lineIndex("line-2");
		assertThat(compiled.transferTransitions(station2, line1, line2)).isNotEmpty();
		assertThat(compiled.transferTransitions(station2, line2, line1)).isNotEmpty();

		// 존재하지 않거나 환승 불가한 조합은 빈 배열 반환 및 안전 거부
		int station1 = compiled.stationIndex("station-1");
		assertThat(compiled.transferTransitions(station1, line1, line2)).isEmpty();
		assertThat(compiled.transferTransitions(-1, line1, line2)).isEmpty();
		assertThat(compiled.transferTransitions(station2, -1, line2)).isEmpty();
		assertThat(compiled.transferTransitions(station2, line1, 999)).isEmpty();
	}

	@Test
	@DisplayName("결정론적 희소 컴파일 및 순서 보존")
	void deterministicSparseCompilation() {
		var planner = new RouteTimetableRaptorPlanner();
		var timetable = createMultiLineTransferTimetable();
		var compiled1 = planner.compile(timetable);
		var compiled2 = planner.compile(timetable);

		assertThat(compiled1.allocatedTransferSlotCount()).isEqualTo(compiled2.allocatedTransferSlotCount());

		int station2 = compiled1.stationIndex("station-2");
		int line1 = compiled1.lineIndex("line-1");
		int line2 = compiled1.lineIndex("line-2");

		int[] t1 = compiled1.transferTransitions(station2, line1, line2);
		int[] t2 = compiled2.transferTransitions(station2, line1, line2);
		assertThat(t1).containsExactly(t2);
	}

	private static RouteTimetable createMultiLineTransferTimetable() {
		var routes = List.of(
			new LoadRouteTimetablePort.TransitRoute("r1", "line-1", "r1", "line-1", "Line 1", "Asia/Seoul"),
			new LoadRouteTimetablePort.TransitRoute("r2", "line-2", "r2", "line-2", "Line 2", "Asia/Seoul")
		);

		var trips = List.of(
			new LoadRouteTimetablePort.TransitTrip("trip-1", "r1", "daily", "trip-1", "0", "LOCAL", 0),
			new LoadRouteTimetablePort.TransitTrip("trip-2", "r2", "daily", "trip-2", "0", "LOCAL", 0)
		);

		var stopTimes = List.of(
			new LoadRouteTimetablePort.TransitStopTime("trip-1", 1, "station-1", "line-1", 1000, 1000, 0, 0),
			new LoadRouteTimetablePort.TransitStopTime("trip-1", 2, "station-2", "line-1", 1200, 1200, 0, 0),
			new LoadRouteTimetablePort.TransitStopTime("trip-2", 1, "station-2", "line-2", 1300, 1300, 0, 0),
			new LoadRouteTimetablePort.TransitStopTime("trip-2", 2, "station-3", "line-2", 1500, 1500, 0, 0)
		);

		var daily = new LoadRouteTimetablePort.ServiceCalendar(
			"daily", true, true, true, true, true, true, true,
			SERVICE_DATE, SERVICE_DATE.plusDays(7), "Asia/Seoul");

		return new RouteTimetable(
			List.of(daily),
			List.of(),
			routes,
			trips,
			stopTimes,
			List.of(),
			List.of(),
			null,
			new LoadRouteTimetablePort.RouteAccessData(List.of(), List.of(), List.of(), List.of())
		);
	}
}
