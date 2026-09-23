package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeUpdate;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeUpdates;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.SearchRouteV2Command;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendar;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitRoute;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitStopTime;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitTrip;
import com.easysubway.route.domain.ConstraintMode;
import com.easysubway.route.domain.EtaSource;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("#2252 RAPTOR pre-scan realtime sparse overlay")
class RouteTimetableRaptorPlannerRealtimeOverlayTest {

	private static final Instant OBSERVED_AT = Instant.parse("2026-07-01T00:49:30Z");
	private final RouteTimetableRaptorPlanner planner = new RouteTimetableRaptorPlanner();
	private final RouteTimetableRaptorPlanner.CompiledTimetable compiled = planner.compile(timetable());

	@Test
	@DisplayName("fresh delay를 스캔 전에 반영해 더 빠른 대체 trip을 선택한다")
	void delayChangesSelectedTripBeforeScan() {
		var overlay = planner.compileRealtimeOverlay(compiled, updates(
			new TimetableRealtimeUpdate("trip-express", 900, 900, false, "snapshot-delay", OBSERVED_AT)
		));

		var result = planner.search(command(), compiled, overlay).getFirst();

		assertThat(ride(result).tripId()).isEqualTo("trip-local");
		assertThat(result.etaSource()).isEqualTo(EtaSource.PLANNED);
	}

	@Test
	@DisplayName("cancel된 trip을 스캔에서 제외해 대체 trip을 선택한다")
	void cancellationExcludesTripBeforeScan() {
		var overlay = planner.compileRealtimeOverlay(compiled, updates(
			new TimetableRealtimeUpdate("trip-express", 0, 0, true, "snapshot-cancel", OBSERVED_AT)
		));

		assertThat(ride(planner.search(command(), compiled, overlay).getFirst()).tripId())
			.isEqualTo("trip-local");
	}

	@Test
	@DisplayName("현재 service day의 취소 열차는 nextServiceTime에서도 제외한다")
	void cancellationExcludesTripFromCurrentNextServiceTime() {
		var overlay = planner.compileRealtimeOverlay(compiled, updates(
			new TimetableRealtimeUpdate("trip-local", 0, 0, true, "snapshot-cancel", OBSERVED_AT),
			new TimetableRealtimeUpdate("trip-express", 0, 0, true, "snapshot-cancel", OBSERVED_AT)
		));

		assertThat(planner.search(command(), compiled, overlay)).isEmpty();
		assertThat(planner.nextServiceTime(command(), compiled, overlay))
			.contains(OffsetDateTime.parse("2026-07-02T09:00:00+09:00"));
	}

	@Test
	@DisplayName("출발 열차 지연으로 환승을 놓치면 현재 service day를 nextServiceTime으로 안내하지 않는다")
	void delayedArrivalInvalidatesCurrentDayTransferForNextServiceTime() {
		var transferCompiled = planner.compile(transferTimetable());
		var overlay = planner.compileRealtimeOverlay(transferCompiled, updates(
			new TimetableRealtimeUpdate("trip-first", 900, 900, false, "snapshot-delay", OBSERVED_AT)
		));
		var command = new SearchRouteV2Command(
			"station-a", "station-b", OffsetDateTime.parse("2026-07-01T08:50:00+09:00"),
			MobilityType.SENIOR, ConstraintMode.ALLOW_WITH_WARNINGS, true, 1, 1);

		assertThat(planner.search(command, transferCompiled, overlay)).isEmpty();
		assertThat(planner.nextServiceTime(command, transferCompiled, overlay))
			.contains(OffsetDateTime.parse("2026-07-02T09:00:00+09:00"));
	}

	@Test
	@DisplayName("선택된 trip의 delta와 evidence는 REALTIME ride에만 표시한다")
	void selectedUpdatedTripCarriesRealtimeEvidence() {
		var overlay = planner.compileRealtimeOverlay(compiled, updates(
			new TimetableRealtimeUpdate("trip-express", 60, 60, false, "snapshot-live", OBSERVED_AT)
		));

		var ride = ride(planner.search(command(), compiled, overlay).getFirst());

		assertThat(ride.tripId()).isEqualTo("trip-express");
		assertThat(ride.timeSource()).isEqualTo(EtaSource.REALTIME.name());
		assertThat(ride.reasonCodes()).containsExactly("REALTIME_PRE_SCAN_OVERLAY");
		assertThat(ride.providerSnapshotId()).isEqualTo("snapshot-live");
		assertThat(ride.providerObservedAt()).isEqualTo(OBSERVED_AT.toString());
	}

	@Test
	@DisplayName("realtime delta로 정적 trip 순서가 역전돼도 실제 최속 trip을 선택한다")
	void overlayHandlesRealtimeOvertakingWithinStaticPattern() {
		var overtakingCompiled = planner.compile(nonOvertakingTimetable());
		var overlay = planner.compileRealtimeOverlay(overtakingCompiled, updates(
			new TimetableRealtimeUpdate("trip-first", 600, 600, false, "snapshot-overtake", OBSERVED_AT)
		));

		assertThat(ride(planner.search(command(), overtakingCompiled, overlay).getFirst()).tripId())
			.isEqualTo("trip-second");
	}

	@Test
	@DisplayName("sparse overlay는 update가 속한 pattern만 realtime full scan 대상으로 표시한다")
	void overlayMarksOnlyAffectedPatterns() {
		var overlay = planner.compileRealtimeOverlay(compiled, updates(
			new TimetableRealtimeUpdate("trip-express", 60, 60, false, "snapshot-live", OBSERVED_AT)
		));
		int expressPattern = compiled.patternOfScheduledTrip(compiled.uniqueScheduledTripIndex("trip-express"));
		int localPattern = compiled.patternOfScheduledTrip(compiled.uniqueScheduledTripIndex("trip-local"));

		assertThat(overlay.affectsPattern(expressPattern)).isTrue();
		assertThat(overlay.affectsPattern(localPattern)).isFalse();
	}

	@Test
	@DisplayName("overlay가 없으면 기존 golden 선택과 PLANNED semantics가 동일하다")
	void absentOverlayPreservesPlannedResult() {
		var baseline = planner.search(command(), compiled).getFirst();
		var withoutOverlay = planner.search(
			command(), compiled, RouteTimetableRaptorPlanner.RealtimeOverlay.empty()).getFirst();

		assertThat(withoutOverlay).isEqualTo(baseline);
		assertThat(ride(withoutOverlay).tripId()).isEqualTo("trip-express");
		assertThat(withoutOverlay.etaSource()).isEqualTo(EtaSource.PLANNED);
	}

	private static TimetableRealtimeUpdates updates(TimetableRealtimeUpdate... updates) {
		return new TimetableRealtimeUpdates("overlay-v1", true, List.of(updates), null);
	}

	private static com.easysubway.route.domain.RouteStep ride(com.easysubway.route.domain.RouteSearchResult result) {
		return result.steps().stream().filter(step -> "ride".equals(step.stepType())).findFirst().orElseThrow();
	}

	private static SearchRouteV2Command command() {
		return new SearchRouteV2Command(
			"station-a", "station-b", OffsetDateTime.parse("2026-07-01T08:50:00+09:00"),
			MobilityType.SENIOR, ConstraintMode.ALLOW_WITH_WARNINGS, true, 0, 1);
	}

	private static RouteTimetable timetable() {
		var calendar = new ServiceCalendar(
			"daily", true, true, true, true, true, true, true,
			LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "Asia/Seoul");
		var routes = List.of(
			new TransitRoute("route-local", "line", "L", "Local", "down", "Asia/Seoul"),
			new TransitRoute("route-express", "line", "E", "Express", "down", "Asia/Seoul")
		);
		var trips = List.of(
			new TransitTrip("trip-local", "route-local", "daily", "station-b", "down",
				"SUBWAY", "LOCAL", "1001", 0),
			new TransitTrip("trip-express", "route-express", "daily", "station-b", "down",
				"SUBWAY", "EXPRESS", "1002", 0)
		);
		var stopTimes = List.of(
			new TransitStopTime("trip-local", 1, "station-a", "line", 32_400, 32_400, 0, 0),
			new TransitStopTime("trip-local", 2, "station-b", "line", 33_600, 33_600, 0, 0),
			new TransitStopTime("trip-express", 1, "station-a", "line", 32_700, 32_700, 0, 0),
			new TransitStopTime("trip-express", 2, "station-b", "line", 33_300, 33_300, 0, 0)
		);
		return new RouteTimetable(List.of(calendar), List.of(), routes, trips, stopTimes, List.of());
	}

	private static RouteTimetable nonOvertakingTimetable() {
		var calendar = new ServiceCalendar(
			"daily", true, true, true, true, true, true, true,
			LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "Asia/Seoul");
		var route = new TransitRoute("route", "line", "L", "Line", "down", "Asia/Seoul");
		var trips = List.of(
			new TransitTrip("trip-first", "route", "daily", "station-b", "down",
				"SUBWAY", "LOCAL", "2001", 0),
			new TransitTrip("trip-second", "route", "daily", "station-b", "down",
				"SUBWAY", "LOCAL", "2002", 0));
		var stopTimes = List.of(
			new TransitStopTime("trip-first", 1, "station-a", "line", 32_400, 32_400, 0, 0),
			new TransitStopTime("trip-first", 2, "station-b", "line", 33_600, 33_600, 0, 0),
			new TransitStopTime("trip-second", 1, "station-a", "line", 32_700, 32_700, 0, 0),
			new TransitStopTime("trip-second", 2, "station-b", "line", 33_900, 33_900, 0, 0));
		return new RouteTimetable(List.of(calendar), List.of(), List.of(route), trips, stopTimes, List.of());
	}

	private static RouteTimetable transferTimetable() {
		var calendar = new ServiceCalendar(
			"daily", true, true, true, true, true, true, true,
			LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "Asia/Seoul");
		var routes = List.of(
			new TransitRoute("route-first", "line-a", "A", "First", "transfer", "Asia/Seoul"),
			new TransitRoute("route-second", "line-b", "B", "Second", "destination", "Asia/Seoul"));
		var trips = List.of(
			new TransitTrip("trip-first", "route-first", "daily", "station-transfer", "down",
				"SUBWAY", "LOCAL", "3001", 0),
			new TransitTrip("trip-second", "route-second", "daily", "station-b", "down",
				"SUBWAY", "LOCAL", "3002", 0));
		var stopTimes = List.of(
			new TransitStopTime("trip-first", 1, "station-a", "line-a", 32_400, 32_400, 0, 0),
			new TransitStopTime("trip-first", 2, "station-transfer", "line-a", 33_000, 33_000, 0, 0),
			new TransitStopTime("trip-second", 1, "station-transfer", "line-b", 34_200, 34_200, 0, 0),
			new TransitStopTime("trip-second", 2, "station-b", "line-b", 34_800, 34_800, 0, 0));
		return new RouteTimetable(List.of(calendar), List.of(), routes, trips, stopTimes, List.of());
	}
}
