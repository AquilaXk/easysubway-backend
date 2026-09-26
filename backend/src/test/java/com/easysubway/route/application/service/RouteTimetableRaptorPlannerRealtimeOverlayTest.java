package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeUpdate;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeUpdates;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendar;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitRoute;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitStopTime;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitTrip;
import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.JourneyAccessProjection;
import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.JourneyItinerary;
import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.JourneyRideProjection;
import java.time.Instant;
import java.time.LocalDate;
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

		var result = planner.journeyItineraries(query(), compiled, overlay).itineraries().getFirst();

		assertThat(ride(result).tripId()).isEqualTo("trip-local");
		assertThat(ride(result).realtimeDepartureTime()).isNull();
	}

	@Test
	@DisplayName("cancel된 trip을 스캔에서 제외해 대체 trip을 선택한다")
	void cancellationExcludesTripBeforeScan() {
		var overlay = planner.compileRealtimeOverlay(compiled, updates(
			new TimetableRealtimeUpdate("trip-express", 0, 0, true, "snapshot-cancel", OBSERVED_AT)
		));

		assertThat(ride(planner.journeyItineraries(query(), compiled, overlay).itineraries().getFirst()).tripId())
			.isEqualTo("trip-local");
	}

	@Test
	@DisplayName("현재 service day의 모든 열차가 취소되면 경로가 없다")
	void cancellationExcludesTripFromCurrentNextServiceTime() {
		var overlay = planner.compileRealtimeOverlay(compiled, updates(
			new TimetableRealtimeUpdate("trip-local", 0, 0, true, "snapshot-cancel", OBSERVED_AT),
			new TimetableRealtimeUpdate("trip-express", 0, 0, true, "snapshot-cancel", OBSERVED_AT)
		));

		assertThat(planner.journeyItineraries(query(), compiled, overlay).itineraries()).isEmpty();
	}

	@Test
	@DisplayName("출발 열차 지연으로 환승을 놓치면 경로가 없다")
	void delayedArrivalInvalidatesCurrentDayTransferForNextServiceTime() {
		var transferCompiled = planner.compile(transferTimetable());
		var overlay = planner.compileRealtimeOverlay(transferCompiled, updates(
			new TimetableRealtimeUpdate("trip-first", 900, 900, false, "snapshot-delay", OBSERVED_AT)
		));

		assertThat(planner.journeyItineraries(transferQuery(), transferCompiled, overlay).itineraries()).isEmpty();
	}

	@Test
	@DisplayName("선택된 trip의 delta와 evidence는 REALTIME ride에만 표시한다")
	void selectedUpdatedTripCarriesRealtimeEvidence() {
		var overlay = planner.compileRealtimeOverlay(compiled, updates(
			new TimetableRealtimeUpdate("trip-express", 60, 60, false, "snapshot-live", OBSERVED_AT)
		));

		var ride = ride(planner.journeyItineraries(query(), compiled, overlay).itineraries().getFirst());

		assertThat(ride.tripId()).isEqualTo("trip-express");
		assertThat(ride.realtimeDepartureTime()).isEqualTo(ride.plannedDepartureTime().plusSeconds(60));
		assertThat(ride.realtimeArrivalTime()).isEqualTo(ride.plannedArrivalTime().plusSeconds(60));
	}

	@Test
	@DisplayName("realtime delta로 정적 trip 순서가 역전돼도 실제 최속 trip을 선택한다")
	void overlayHandlesRealtimeOvertakingWithinStaticPattern() {
		var overtakingCompiled = planner.compile(nonOvertakingTimetable());
		var overlay = planner.compileRealtimeOverlay(overtakingCompiled, updates(
			new TimetableRealtimeUpdate("trip-first", 600, 600, false, "snapshot-overtake", OBSERVED_AT)
		));

		assertThat(ride(planner.journeyItineraries(query(), overtakingCompiled, overlay).itineraries().getFirst()).tripId())
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
		var baseline = planner.journeyItineraries(query(), compiled).itineraries().getFirst();
		var withoutOverlay = planner.journeyItineraries(
			query(), compiled, RouteTimetableRaptorPlanner.RealtimeOverlay.empty()).itineraries().getFirst();

		assertThat(withoutOverlay).isEqualTo(baseline);
		assertThat(ride(withoutOverlay).tripId()).isEqualTo("trip-express");
		assertThat(ride(withoutOverlay).realtimeDepartureTime()).isNull();
	}

	@Test
	@DisplayName("승강기 장애 edge가 주입되면 overlay가 transition을 차단 상태로 표시한다")
	void overlayMarksBlockedTransitionsForPathwayEdge() {
		var overlay = planner.compileRealtimeOverlay(compiled, updatesWithBlockedEdges(List.of("entry")));
		int[] entryTransitions = compiled.transitionIdsForEdge("entry");

		assertThat(entryTransitions).isNotEmpty();
		for (int transition : entryTransitions) {
			assertThat(overlay.isTransitionBlocked(transition)).isTrue();
		}
	}

	@Test
	@DisplayName("출발역 승강기 고장 시 대체 경로가 없으면 fail-closed로 빈 결과를 반환한다")
	void elevatorOutageExcludesEntryPathwayAndFailsClosedWhenNoAlternative() {
		var overlay = planner.compileRealtimeOverlay(compiled, updatesWithBlockedEdges(List.of("entry")));

		var results = planner.journeyItineraries(wheelchairQuery(), compiled, overlay).itineraries();

		assertThat(results).isEmpty();
	}

	@Test
	@DisplayName("환승 승강기 고장 시 우회 승강기를 자동으로 대체 선택한다")
	void elevatorOutageSelectsAlternativeDetourElevatorForTransfer() {
		var altCompiled = planner.compile(transferTimetableWithAlternatives());
		var baseline = planner.journeyItineraries(wheelchairTransferQuery(), altCompiled).itineraries().getFirst();
		var baselineTransfer = baseline.legs().stream()
			.filter(JourneyAccessProjection.class::isInstance)
			.map(JourneyAccessProjection.class::cast)
			.filter(leg -> leg.kind() == RouteTimetableRaptorPlanner.JourneyAccessKind.TRANSFER)
			.findFirst().orElseThrow();
		assertThat(baselineTransfer.durationSeconds()).isEqualTo(155);
		assertThat(baselineTransfer.distanceMeters()).isEqualTo(150);

		var overlay = planner.compileRealtimeOverlay(altCompiled, updatesWithBlockedEdges(List.of("transfer-primary")));
		var detourResult = planner.journeyItineraries(wheelchairTransferQuery(), altCompiled, overlay).itineraries().getFirst();
		var detourTransfer = detourResult.legs().stream()
			.filter(JourneyAccessProjection.class::isInstance)
			.map(JourneyAccessProjection.class::cast)
			.filter(leg -> leg.kind() == RouteTimetableRaptorPlanner.JourneyAccessKind.TRANSFER)
			.findFirst().orElseThrow();
		assertThat(detourTransfer.durationSeconds()).isEqualTo(258);
		assertThat(detourTransfer.distanceMeters()).isEqualTo(250);
	}

	@Test
	@DisplayName("도착역 승강기 고장 시 대체 경로가 없으면 fail-closed로 빈 결과를 반환한다")
	void elevatorOutageExcludesExitPathwayAndFailsClosedWhenNoAlternative() {
		var overlay = planner.compileRealtimeOverlay(compiled, updatesWithBlockedEdges(List.of("exit")));

		var results = planner.journeyItineraries(wheelchairQuery(), compiled, overlay).itineraries();

		assertThat(results).isEmpty();
	}

	@Test
	@DisplayName("edgeId가 null이거나 미존재할 때 빈 transition 목록을 반환하고 음수 transition은 차단되지 않는다")
	void transitionEdgeLookupAndNegativeTransitionSafety() {
		assertThat(compiled.transitionIdsForEdge(null)).isEmpty();
		assertThat(compiled.transitionIdsForEdge("")).isEmpty();
		assertThat(compiled.transitionIdsForEdge("non-existent")).isEmpty();

		var emptyOverlay = RouteTimetableRaptorPlanner.RealtimeOverlay.empty();
		assertThat(emptyOverlay.isEmpty()).isTrue();
		assertThat(emptyOverlay.isTransitionBlocked(0)).isFalse();
		assertThat(emptyOverlay.isTransitionBlocked(-1)).isFalse();

		var overlay = planner.compileRealtimeOverlay(compiled, updatesWithBlockedEdges(List.of("entry")));
		assertThat(overlay.isEmpty()).isFalse();
		assertThat(overlay.isTransitionBlocked(-1)).isFalse();
	}

	@Test
	@DisplayName("TimetableRealtimeUpdates 계약 및 예외 분기를 철저히 검증한다")
	void timetableRealtimeUpdatesValidationContracts() {
		var updatesWithNullEdges = new TimetableRealtimeUpdates("v1", true, List.of(new TimetableRealtimeUpdate("trip1", 0, 0, false, "snapshot-1", OBSERVED_AT)), null, null);
		assertThat(updatesWithNullEdges.blockedPathwayEdgeIds()).isEmpty();

		assertThatThrownBy(() -> new TimetableRealtimeUpdates(null, true, List.of(new TimetableRealtimeUpdate("trip1", 0, 0, false, "snapshot-1", OBSERVED_AT)), List.of(), null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new TimetableRealtimeUpdates("   ", true, List.of(new TimetableRealtimeUpdate("trip1", 0, 0, false, "snapshot-1", OBSERVED_AT)), List.of(), null))
			.isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> new TimetableRealtimeUpdates("v1", true, List.of(), List.of(), null))
			.isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> new TimetableRealtimeUpdates(null, false, List.of(new TimetableRealtimeUpdate("trip1", 0, 0, false, "snapshot-1", OBSERVED_AT)), List.of(), "FALLBACK"))
			.isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> new TimetableRealtimeUpdates(null, false, List.of(), List.of("edge1"), "FALLBACK"))
			.isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> new TimetableRealtimeUpdates(null, false, List.of(), List.of(), null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new TimetableRealtimeUpdates(null, false, List.of(), List.of(), "   "))
			.isInstanceOf(IllegalArgumentException.class);

		var fourArg = new TimetableRealtimeUpdates("v1", true, List.of(new TimetableRealtimeUpdate("trip1", 0, 0, false, "snapshot-1", OBSERVED_AT)), null);
		assertThat(fourArg.blockedPathwayEdgeIds()).isEmpty();
		var unavail = TimetableRealtimeUpdates.unavailable("GATEWAY_TIMEOUT");
		assertThat(unavail.available()).isFalse();
		assertThat(unavail.fallbackCode()).isEqualTo("GATEWAY_TIMEOUT");
	}

	@Test
	@DisplayName("일반/경고허용 모드에서도 승강기 고장 시 해당 전이가 배제된다")
	void nonVerifiedSelectWithBlockedOverlay() {
		var altCompiled = planner.compile(transferTimetableWithAlternatives());
		var standardQuery = new JourneyRaptorQuery(
			"01ARZ3NDEKTSV4RRFFQ69G5FAV",
			"station-a",
			"station-b",
			new JourneyRaptorQuery.DepartAt(Instant.parse("2026-06-30T23:50:00Z")),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.SLOW,
			JourneyRequest.MobilityProfile.STANDARD,
			JourneyRequest.ConstraintMode.NONE,
			1,
			1,
			() -> false
		);
		var overlay = planner.compileRealtimeOverlay(altCompiled, updatesWithBlockedEdges(List.of("transfer-primary")));
		var results = planner.journeyItineraries(standardQuery, altCompiled, overlay).itineraries();
		assertThat(results).isNotEmpty();
	}

	@Test
	@DisplayName("CompiledTimetable 전이 조회 편의 오버로드들을 모두 검증한다")
	void compiledTimetableOverloads() {
		int originStation = compiled.stationIndex("station-a");
		int line0 = 0;
		int profileBit = 1;

		assertThat(compiled.entryTransition(originStation, line0, profileBit, false, false)).isGreaterThanOrEqualTo(0);
		assertThat(compiled.entryTransition(originStation, line0, profileBit, false)).isGreaterThanOrEqualTo(0);

		int destStation = compiled.stationIndex("station-b");
		assertThat(compiled.exitTransition(destStation, line0, profileBit, false, false)).isGreaterThanOrEqualTo(0);
		assertThat(compiled.exitTransition(destStation, line0, profileBit, false)).isGreaterThanOrEqualTo(0);

		var altCompiled = planner.compile(transferTimetableWithAlternatives());
		int transferStation = altCompiled.stationIndex("station-transfer");
		int line1 = 1;
		assertThat(altCompiled.transferTransition(transferStation, line0, line1, profileBit, false, false)).isGreaterThanOrEqualTo(0);
		assertThat(altCompiled.transferTransition(transferStation, line0, line1, profileBit, false)).isGreaterThanOrEqualTo(0);

		var entryOutage = planner.compileRealtimeOverlay(compiled, updatesWithBlockedEdges(List.of("entry")));
		assertThat(compiled.entryTransition(originStation, line0, profileBit, false, false, entryOutage)).isEqualTo(-1);

		var altOverlay = planner.compileRealtimeOverlay(altCompiled, updatesWithBlockedEdges(List.of("transfer-primary")));
		assertThat(altCompiled.transferTransition(transferStation, line0, line1, profileBit, false, false, altOverlay)).isGreaterThanOrEqualTo(0);

		var exitOutage = planner.compileRealtimeOverlay(compiled, updatesWithBlockedEdges(List.of("exit")));
		assertThat(compiled.exitTransition(destStation, line0, profileBit, false, false, exitOutage)).isEqualTo(-1);
	}

	@Test
	@DisplayName("RealtimeOverlay의 모든 분기(isEmpty 조합 및 음수/양수/미차단/차단)를 검증한다")
	void realtimeOverlayExhaustiveBranches() {
		var emptyOverlay = RouteTimetableRaptorPlanner.RealtimeOverlay.empty();
		assertThat(emptyOverlay.isEmpty()).isTrue();
		assertThat(emptyOverlay.isTransitionBlocked(-1)).isFalse();
		assertThat(emptyOverlay.isTransitionBlocked(0)).isFalse();

		var outageOnly = planner.compileRealtimeOverlay(compiled, updatesWithBlockedEdges(List.of("entry")));
		assertThat(outageOnly.isEmpty()).isFalse();
		int entryTrans = compiled.transitionIdsForEdge("entry")[0];
		assertThat(outageOnly.isTransitionBlocked(entryTrans)).isTrue();
		assertThat(outageOnly.isTransitionBlocked(999999)).isFalse();

		var tripOnly = planner.compileRealtimeOverlay(compiled, updates(new TimetableRealtimeUpdate("trip-local", 10, 10, false, "snapshot-1", OBSERVED_AT)));
		assertThat(tripOnly.isEmpty()).isFalse();
		assertThat(tripOnly.isTransitionBlocked(entryTrans)).isFalse();

		var both = planner.compileRealtimeOverlay(compiled, updatesWithBlockedEdges(List.of("entry"), new TimetableRealtimeUpdate("trip-local", 10, 10, false, "snapshot-1", OBSERVED_AT)));
		assertThat(both.isEmpty()).isFalse();
		assertThat(both.isTransitionBlocked(entryTrans)).isTrue();
	}

	@Test
	@DisplayName("transitionIdsForEdge의 모든 경계조건(null, blank, empty, 미존재, 존재)을 검증한다")
	void transitionIdsForEdgeAllBranches() {
		assertThat(compiled.transitionIdsForEdge(null)).isEmpty();
		assertThat(compiled.transitionIdsForEdge("")).isEmpty();
		assertThat(compiled.transitionIdsForEdge("   ")).isEmpty();
		assertThat(compiled.transitionIdsForEdge("unknown-edge")).isEmpty();
		assertThat(compiled.transitionIdsForEdge("entry")).isNotEmpty();
	}

	private static TimetableRealtimeUpdates updates(TimetableRealtimeUpdate... updates) {
		return new TimetableRealtimeUpdates("overlay-v1", true, List.of(updates), null);
	}

	private static TimetableRealtimeUpdates updatesWithBlockedEdges(List<String> blockedEdges, TimetableRealtimeUpdate... updates) {
		return new TimetableRealtimeUpdates("overlay-v1", true, List.of(updates), blockedEdges, null);
	}

	private static JourneyRideProjection ride(JourneyItinerary result) {
		return result.legs().stream()
			.filter(JourneyRideProjection.class::isInstance)
			.map(JourneyRideProjection.class::cast)
			.findFirst().orElseThrow();
	}

	private static JourneyRaptorQuery query() {
		return new JourneyRaptorQuery(
			"01ARZ3NDEKTSV4RRFFQ69G5FAV",
			"station-a",
			"station-b",
			new JourneyRaptorQuery.DepartAt(Instant.parse("2026-06-30T23:50:00Z")),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.SLOW,
			JourneyRequest.MobilityProfile.SLOW,
			JourneyRequest.ConstraintMode.NONE,
			0,
			1,
			() -> false
		);
	}

	private static JourneyRaptorQuery transferQuery() {
		return new JourneyRaptorQuery(
			"01ARZ3NDEKTSV4RRFFQ69G5FAV",
			"station-a",
			"station-b",
			new JourneyRaptorQuery.DepartAt(Instant.parse("2026-06-30T23:50:00Z")),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.SLOW,
			JourneyRequest.MobilityProfile.SLOW,
			JourneyRequest.ConstraintMode.NONE,
			1,
			1,
			() -> false
		);
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
		return new RouteTimetable(List.of(calendar), List.of(), routes, trips, stopTimes, List.of(), List.of(), null, verifiedAccess("line"));
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
		return new RouteTimetable(List.of(calendar), List.of(), List.of(route), trips, stopTimes, List.of(), List.of(), null, verifiedAccess("line"));
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
		return new RouteTimetable(List.of(calendar), List.of(), routes, trips, stopTimes, List.of(), List.of(), null, transferAccess());
	}

	private static com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteAccessData verifiedAccess(String lineId) {
		var edges = List.of(
			new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayEdge(
				"entry", "entrance", "platform-a", 120, 60, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"),
			new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayEdge(
				"exit", "platform-b", "outside", 60, 40, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"));
		var evidence = List.of(
			new com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteEdgeEvidence(
				"entry-evidence", "station-a", lineId, "entry", "ENTRY",
				"OFFICIAL_SOURCE", "VERIFIED", true, null),
			new com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteEdgeEvidence(
				"exit-evidence", "station-b", lineId, "exit", "EXIT",
				"OFFICIAL_SOURCE", "VERIFIED", true, null));
		return new com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteAccessData(
			List.of(
				new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode("entrance", "station-a", null, "ENTRANCE"),
				new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode("platform-a", "station-a", lineId, "PLATFORM"),
				new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode("platform-b", "station-b", lineId, "PLATFORM"),
				new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode("outside", "station-b", null, "EXIT")),
			edges, List.of(), evidence);
	}

	private static com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteAccessData transferAccess() {
		var edges = List.of(
			new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayEdge(
				"entry", "entrance", "platform-a", 120, 60, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"),
			new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayEdge(
				"transfer", "platform-transfer-a", "platform-transfer-b", 300, 300, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"),
			new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayEdge(
				"exit", "platform-b", "outside", 60, 40, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"));
		var evidence = List.of(
			new com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteEdgeEvidence(
				"entry-evidence", "station-a", "line-a", "entry", "ENTRY",
				"OFFICIAL_SOURCE", "VERIFIED", true, null),
			new com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteEdgeEvidence(
				"transfer-evidence", "station-transfer", "line-b", "transfer", "TRANSFER",
				"OFFICIAL_SOURCE", "VERIFIED", true, null),
			new com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteEdgeEvidence(
				"exit-evidence", "station-b", "line-b", "exit", "EXIT",
				"OFFICIAL_SOURCE", "VERIFIED", true, null));
		var transferRule = new com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransferRule(
			"transfer-rule", "station-transfer", "line-a", "station-transfer", "line-b",
			"IN_STATION", 300, "transfer", "transfer", "VERIFIED");
		return new com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteAccessData(
			List.of(
				new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode("entrance", "station-a", null, "ENTRANCE"),
				new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode("platform-a", "station-a", "line-a", "PLATFORM"),
				new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode("platform-transfer-a", "station-transfer", "line-a", "PLATFORM"),
				new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode("platform-transfer-b", "station-transfer", "line-b", "PLATFORM"),
				new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode("platform-b", "station-b", "line-b", "PLATFORM"),
				new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode("outside", "station-b", null, "EXIT")),
			edges, List.of(transferRule), evidence);
	}

	private static JourneyRaptorQuery wheelchairQuery() {
		return new JourneyRaptorQuery(
			"01ARZ3NDEKTSV4RRFFQ69G5FAV",
			"station-a",
			"station-b",
			new JourneyRaptorQuery.DepartAt(Instant.parse("2026-06-30T23:50:00Z")),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.SLOW,
			JourneyRequest.MobilityProfile.NO_STAIRS,
			JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE,
			0,
			1,
			() -> false
		);
	}

	private static JourneyRaptorQuery wheelchairTransferQuery() {
		return new JourneyRaptorQuery(
			"01ARZ3NDEKTSV4RRFFQ69G5FAV",
			"station-a",
			"station-b",
			new JourneyRaptorQuery.DepartAt(Instant.parse("2026-06-30T23:50:00Z")),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.SLOW,
			JourneyRequest.MobilityProfile.NO_STAIRS,
			JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE,
			1,
			1,
			() -> false
		);
	}

	private static RouteTimetable transferTimetableWithAlternatives() {
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
		return new RouteTimetable(List.of(calendar), List.of(), routes, trips, stopTimes, List.of(), List.of(), null, transferAccessWithAlternatives());
	}

	private static com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteAccessData transferAccessWithAlternatives() {
		var edges = List.of(
			new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayEdge(
				"entry", "entrance", "platform-a", 120, 60, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"),
			new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayEdge(
				"transfer-primary", "platform-transfer-a", "platform-transfer-b", 180, 150, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"),
			new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayEdge(
				"transfer-detour", "platform-transfer-a", "platform-transfer-b", 300, 250, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"),
			new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayEdge(
				"exit", "platform-b", "outside", 60, 40, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"));
		var evidence = List.of(
			new com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteEdgeEvidence(
				"entry-evidence", "station-a", "line-a", "entry", "ENTRY",
				"OFFICIAL_SOURCE", "VERIFIED", true, null),
			new com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteEdgeEvidence(
				"transfer-evidence-primary", "station-transfer", "line-b", "transfer-primary", "TRANSFER",
				"OFFICIAL_SOURCE", "VERIFIED", true, null),
			new com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteEdgeEvidence(
				"transfer-evidence-detour", "station-transfer", "line-b", "transfer-detour", "TRANSFER",
				"OFFICIAL_SOURCE", "VERIFIED", true, null),
			new com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteEdgeEvidence(
				"exit-evidence", "station-b", "line-b", "exit", "EXIT",
				"OFFICIAL_SOURCE", "VERIFIED", true, null));
		var transferRulePrimary = new com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransferRule(
			"transfer-rule-primary", "station-transfer", "line-a", "station-transfer", "line-b",
			"IN_STATION", 180, "transfer-primary", "transfer-primary", "VERIFIED");
		var transferRuleDetour = new com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransferRule(
			"transfer-rule-detour", "station-transfer", "line-a", "station-transfer", "line-b",
			"IN_STATION", 300, "transfer-detour", "transfer-detour", "VERIFIED");
		return new com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteAccessData(
			List.of(
				new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode("entrance", "station-a", null, "ENTRANCE"),
				new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode("platform-a", "station-a", "line-a", "PLATFORM"),
				new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode("platform-transfer-a", "station-transfer", "line-a", "PLATFORM"),
				new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode("platform-transfer-b", "station-transfer", "line-b", "PLATFORM"),
				new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode("platform-b", "station-b", "line-b", "PLATFORM"),
				new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode("outside", "station-b", null, "EXIT")),
			edges, List.of(transferRulePrimary, transferRuleDetour), evidence);
	}
}
