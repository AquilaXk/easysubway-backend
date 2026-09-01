package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeUpdate;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeUpdates;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.domain.BoardingSlackPolicy;
import com.easysubway.route.domain.ConstraintMode;
import com.easysubway.route.domain.ProfileWalkTimeCalculator.MobilityPreset;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("#309 reverse arrive-by and last-connection primitive")
class ReverseTimetableRaptorPlannerTest {

	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 7, 1);
	private static final int PROFILE_BIT = RouteTimetableRaptorPlanner.profileBit(
		MobilityType.SENIOR, ConstraintMode.ALLOW_WITH_WARNINGS);
	private static final int SLACK_SECONDS = BoardingSlackPolicy.secondsFor(MobilityType.SENIOR);
	private final RouteTimetableRaptorPlanner forward = new RouteTimetableRaptorPlanner();
	private final ReverseTimetableRaptorPlanner planner = new ReverseTimetableRaptorPlanner();

	@Test
	@DisplayName("uses verified ENTRY and EXIT durations at an equal 24-hour deadline")
	void returnsLatestReadyAtForDirectOvernightConnection() {
		var compiled = forward.compile(directTimetable(87_000, 87_600, true, true, 300, 180));

		var result = arriveBy(compiled, "station-a", "station-b", 87_843,
			RouteTimetableRaptorPlanner.RealtimeOverlay.empty());

		assertThat(result.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
		assertThat(result.latestReadyAtSeconds()).isEqualTo(87_000 - 405 - SLACK_SECONDS);
		assertThat(result.arrivalAtDestinationSeconds()).isEqualTo(87_843);
	}

	@Test
	@DisplayName("rejects one-way or unverified access instead of inverting it")
	void rejectsUnverifiedOrWrongDirectionalAccess() {
		var unverified = forward.compile(directTimetable(32_400, 33_000, true, false, 300, 180));
		var wrongDirection = forward.compile(directTimetable(32_400, 33_000, false, true, 300, 180));

		assertThat(arriveBy(unverified, "station-a", "station-b", 33_180,
			RouteTimetableRaptorPlanner.RealtimeOverlay.empty()).outcome())
			.isEqualTo(ReverseTimetableRaptorPlanner.Outcome.NO_VERIFIED_EXIT);
		assertThat(arriveBy(wrongDirection, "station-a", "station-b", 33_180,
			RouteTimetableRaptorPlanner.RealtimeOverlay.empty()).outcome())
			.isEqualTo(ReverseTimetableRaptorPlanner.Outcome.NO_OD_CONNECTION);
	}

	@Test
	@DisplayName("uses only the original directional TRANSFER between two ride legs")
	void followsVerifiedDirectionalTransfer() {
		var compiled = forward.compile(transferTimetable());

		var result = arriveBy(compiled, "station-a", "station-b", 35_000,
			RouteTimetableRaptorPlanner.RealtimeOverlay.empty());

		assertThat(result.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
		assertThat(result.latestReadyAtSeconds()).isEqualTo(32_400 - 405 - SLACK_SECONDS);
		assertThat(result.transfersUsed()).isEqualTo(1);
	}

	@Test
	@DisplayName("distinguishes calendar exclusion, realtime cancellation, and delayed deadline misses")
	void classifiesInactiveAndPinnedRealtimeChanges() {
		var compiled = forward.compile(directTimetable(32_400, 33_000, true, true, 300, 180));
		var noService = planner.arriveBy(query("station-a", "station-b", 33_180), compiled,
			compiled.activeServiceDay(SERVICE_DATE.plusDays(1)), RouteTimetableRaptorPlanner.RealtimeOverlay.empty());
		var cancelled = forward.compileRealtimeOverlay(compiled, updates(
			new TimetableRealtimeUpdate("direct", 0, 0, true, "snapshot-cancel", Instant.parse("2026-07-01T00:00:00Z"))));
		var delayed = forward.compileRealtimeOverlay(compiled, updates(
			new TimetableRealtimeUpdate("direct", 300, 300, false, "snapshot-delay", Instant.parse("2026-07-01T00:00:00Z"))));

		assertThat(noService.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.NO_ACTIVE_SERVICE);
		assertThat(arriveBy(compiled, "station-a", "station-b", 33_180, cancelled).outcome())
			.isEqualTo(ReverseTimetableRaptorPlanner.Outcome.NO_OD_CONNECTION);
		assertThat(arriveBy(compiled, "station-a", "station-b", 33_180, delayed).outcome())
			.isEqualTo(ReverseTimetableRaptorPlanner.Outcome.DEADLINE_MISS);
	}

	@Test
	@DisplayName("requires upstream pickup and downstream drop-off permissions")
	void requiresPickupAndDropOffRestrictions() {
		var noPickup = forward.compile(directTimetableWithRestrictions(1, 0));
		var noDropOff = forward.compile(directTimetableWithRestrictions(0, 1));

		assertThat(arriveBy(noPickup, "station-a", "station-b", 33_180,
			RouteTimetableRaptorPlanner.RealtimeOverlay.empty()).outcome())
			.isEqualTo(ReverseTimetableRaptorPlanner.Outcome.NO_OD_CONNECTION);
		assertThat(arriveBy(noDropOff, "station-a", "station-b", 33_180,
			RouteTimetableRaptorPlanner.RealtimeOverlay.empty()).outcome())
			.isEqualTo(ReverseTimetableRaptorPlanner.Outcome.NO_OD_CONNECTION);
	}

	private ReverseTimetableRaptorPlanner.Result arriveBy(
		RouteTimetableRaptorPlanner.CompiledTimetable compiled,
		String origin,
		String destination,
		int deadlineSeconds,
		RouteTimetableRaptorPlanner.RealtimeOverlay overlay
	) {
		return planner.arriveBy(query(origin, destination, deadlineSeconds), compiled,
			compiled.activeServiceDay(SERVICE_DATE), overlay);
	}

	private static ReverseTimetableRaptorPlanner.Query query(String origin, String destination, int deadlineSeconds) {
		return new ReverseTimetableRaptorPlanner.Query(
			origin, destination, 0, deadlineSeconds, 1, PROFILE_BIT, SLACK_SECONDS,
			MobilityPreset.SLOW, 3_600, false, () -> false);
	}

	private static RouteTimetable directTimetable(
		int departure, int arrival, boolean entryForward, boolean exitVerified, int entrySeconds, int exitSeconds
	) {
		return timetable(
			List.of(trip("direct", "route-direct")),
			List.of(stop("direct", 1, "station-a", "line-a", departure, 0, 0),
				stop("direct", 2, "station-b", "line-a", arrival, 0, 0)),
			access(entryForward, exitVerified, entrySeconds, exitSeconds, false));
	}

	private static RouteTimetable directTimetableWithRestrictions(int pickupType, int dropOffType) {
		return timetable(
			List.of(trip("direct", "route-direct")),
			List.of(stop("direct", 1, "station-a", "line-a", 32_400, pickupType, 0),
				stop("direct", 2, "station-b", "line-a", 33_000, 0, dropOffType)),
			access(true, true, 300, 180, false));
	}

	private static RouteTimetable transferTimetable() {
		return timetable(
			List.of(trip("first", "route-a"), trip("second", "route-b")),
			List.of(stop("first", 1, "station-a", "line-a", 32_400, 0, 0),
				stop("first", 2, "station-transfer", "line-a", 33_000, 0, 0),
				stop("second", 1, "station-transfer", "line-b", 34_200, 0, 0),
				stop("second", 2, "station-b", "line-b", 34_800, 0, 0)),
			access(true, true, 300, 180, true));
	}

	private static RouteTimetable timetable(
		List<LoadRouteTimetablePort.TransitTrip> trips,
		List<LoadRouteTimetablePort.TransitStopTime> stopTimes,
		LoadRouteTimetablePort.RouteAccessData access
	) {
		var calendar = new LoadRouteTimetablePort.ServiceCalendar(
			"daily", false, false, true, false, false, false, false,
			SERVICE_DATE, SERVICE_DATE, "Asia/Seoul");
		var routes = trips.stream().map(trip -> new LoadRouteTimetablePort.TransitRoute(
			trip.routeId(), trip.routeId(), trip.routeId(), trip.routeId(), "terminal", "Asia/Seoul")).toList();
		return new RouteTimetable(List.of(calendar), List.of(), routes, trips, stopTimes, List.of(), null, access);
	}

	private static LoadRouteTimetablePort.TransitTrip trip(String id, String routeId) {
		return new LoadRouteTimetablePort.TransitTrip(id, routeId, "daily", "terminal", "0", "LOCAL", 0);
	}

	private static LoadRouteTimetablePort.TransitStopTime stop(
		String tripId, int sequence, String station, String line, int seconds, int pickupType, int dropOffType
	) {
		return new LoadRouteTimetablePort.TransitStopTime(
			tripId, sequence, station, line, seconds, seconds, pickupType, dropOffType);
	}

	private static LoadRouteTimetablePort.RouteAccessData access(
		boolean entryForward, boolean exitVerified, int entrySeconds, int exitSeconds, boolean transfer
	) {
		String destinationLine = transfer ? "line-b" : "line-a";
		var nodes = List.of(
			new LoadRouteTimetablePort.PathwayNode("entry-outside", "station-a", null, "ENTRANCE"),
			new LoadRouteTimetablePort.PathwayNode("entry-platform", "station-a", "line-a", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("exit-platform", "station-b", destinationLine, "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("exit-outside", "station-b", null, "EXIT"),
			new LoadRouteTimetablePort.PathwayNode("transfer-a", "station-transfer", "line-a", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("transfer-b", "station-transfer", "line-b", "PLATFORM"));
		var entry = entryForward
			? edge("entry", "entry-outside", "entry-platform", entrySeconds, "VERIFIED")
			: edge("entry", "entry-platform", "entry-outside", entrySeconds, "VERIFIED");
		var edges = transfer
			? List.of(entry, edge("exit", "exit-platform", "exit-outside", exitSeconds,
				exitVerified ? "VERIFIED" : "UNKNOWN"), edge("transfer", "transfer-a", "transfer-b", 300, "VERIFIED"))
			: List.of(entry, edge("exit", "exit-platform", "exit-outside", exitSeconds,
				exitVerified ? "VERIFIED" : "UNKNOWN"));
		var evidence = transfer
			? List.of(evidence("entry-e", "station-a", "line-a", "entry", "ENTRY"),
				evidence("exit-e", "station-b", destinationLine, "exit", "EXIT"),
				evidence("transfer-e", "station-transfer", "line-b", "transfer", "TRANSFER"))
			: List.of(evidence("entry-e", "station-a", "line-a", "entry", "ENTRY"),
				evidence("exit-e", "station-b", destinationLine, "exit", "EXIT"));
		var rules = transfer ? List.of(new LoadRouteTimetablePort.TransferRule(
			"transfer-rule", "station-transfer", "line-a", "station-transfer", "line-b", "IN_STATION", 300,
			"transfer", null, "VERIFIED")) : List.<LoadRouteTimetablePort.TransferRule>of();
		return new LoadRouteTimetablePort.RouteAccessData(nodes, edges, rules, evidence);
	}

	private static LoadRouteTimetablePort.PathwayEdge edge(
		String id, String from, String to, int seconds, String verificationStatus
	) {
		return new LoadRouteTimetablePort.PathwayEdge(id, from, to, seconds, 100, false, false, 100,
			"AVAILABLE", "OFFICIAL_SOURCE", verificationStatus);
	}

	private static LoadRouteTimetablePort.RouteEdgeEvidence evidence(
		String id, String station, String line, String edge, String edgeType
	) {
		return new LoadRouteTimetablePort.RouteEdgeEvidence(
			id, station, line, edge, edgeType, "OFFICIAL_SOURCE", "VERIFIED", true, null);
	}

	private static TimetableRealtimeUpdates updates(TimetableRealtimeUpdate... updates) {
		return new TimetableRealtimeUpdates("reverse-test", true, List.of(updates), null);
	}
}
