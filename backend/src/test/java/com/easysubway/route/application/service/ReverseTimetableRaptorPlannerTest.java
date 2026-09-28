package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.journey.application.JourneyProfileResourcePolicy;
import com.easysubway.journey.application.JourneyRaptorPruningInventoryV1;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.application.ServiceDayResolver;
import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeUpdate;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeUpdates;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.domain.BoardingSlackPolicy;
import com.easysubway.route.domain.ConstraintMode;
import com.easysubway.route.domain.ProfileWalkTimeCalculator;
import com.easysubway.route.domain.ProfileWalkTimeCalculator.MobilityPreset;
import com.easysubway.route.domain.ProfileWalkTimeCalculator.WalkTimeSource;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("#309 reverse arrive-by and last-connection primitive")
class ReverseTimetableRaptorPlannerTest {

	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 7, 1);
	private static final String ORACLE_REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
	private static final int PROFILE_BIT = RouteTimetableRaptorPlanner.profileBit(
		MobilityType.SENIOR, ConstraintMode.ALLOW_WITH_WARNINGS);
	private static final int SLACK_SECONDS = BoardingSlackPolicy.secondsFor(MobilityType.SENIOR);
	private static final int WHEELCHAIR_PROFILE_BIT = RouteTimetableRaptorPlanner.profileBit(
		MobilityType.WHEELCHAIR, ConstraintMode.ALLOW_WITH_WARNINGS);
	private static final int WHEELCHAIR_SLACK_SECONDS = BoardingSlackPolicy.secondsFor(MobilityType.WHEELCHAIR);
	private final RouteTimetableRaptorPlanner forward = new RouteTimetableRaptorPlanner();
	private final ReverseTimetableRaptorPlanner planner = new ReverseTimetableRaptorPlanner();

	private static ReverseTimetableRaptorPlanner.Query wheelchairQuery(String origin, String destination, int deadlineSeconds) {
		return new ReverseTimetableRaptorPlanner.Query(
			origin, destination, SERVICE_DATE, 0, deadlineSeconds, 1, WHEELCHAIR_PROFILE_BIT, WHEELCHAIR_SLACK_SECONDS,
			MobilityPreset.SLOW, 3_600, false, () -> false);
	}

	private static TimetableRealtimeUpdates updatesWithBlockedEdges(List<String> blockedEdges, TimetableRealtimeUpdate... updates) {
		return new TimetableRealtimeUpdates("reverse-test", true, List.of(updates), blockedEdges, null);
	}

	@Test
	@DisplayName("uses verified ENTRY and EXIT durations at an equal 24-hour deadline")
	void returnsLatestReadyAtForDirectOvernightConnection() {
		var compiled = forward.compile(directTimetable(87_000, 87_600, true, true, 300, 180));

		var result = arriveBy(compiled, "station-a", "station-b", 87_843,
			RouteTimetableRaptorPlanner.RealtimeOverlay.empty());

		assertThat(result.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
		assertThat(result.latestReadyAtSeconds()).isEqualTo(87_000 - 405 - SLACK_SECONDS);
		assertThat(result.arrivalAtDestinationSeconds()).isEqualTo(87_843);
		assertThat(result.itineraries()).singleElement().satisfies(itinerary ->
			assertThat(itinerary.legs().stream()
				.filter(RouteTimetableRaptorPlanner.JourneyRideProjection.class::isInstance)
				.map(RouteTimetableRaptorPlanner.JourneyRideProjection.class::cast)
				.map(RouteTimetableRaptorPlanner.JourneyRideProjection::lineId).toList())
				.containsExactly("route-direct"));
	}

	@Test
	@DisplayName("preserves non-dominated reverse candidates across ready time and verified access cost")
	void preservesReverseProfileFrontierCandidates() {
		var compiled = forward.compile(reverseFrontierTimetable());

		var result = arriveBy(compiled, "station-a", "station-b", deadlineAt(33_500, 180),
			RouteTimetableRaptorPlanner.RealtimeOverlay.empty());

		assertThat(result.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
		assertThat(result.itineraries()).hasSize(2);
		assertThat(result.itineraries()).extracting(itinerary -> itinerary.legs().stream()
			.filter(RouteTimetableRaptorPlanner.JourneyRideProjection.class::isInstance)
			.map(RouteTimetableRaptorPlanner.JourneyRideProjection.class::cast)
			.findFirst().orElseThrow().tripId())
			.containsExactlyInAnyOrder("later-ready", "lower-walk");
		assertThat(result.itineraries()).extracting(itinerary -> itinerary.metrics().accessMovementSeconds())
			.containsExactlyInAnyOrder(
				(long) accessMovementAt(300, 180),
				(long) accessMovementAt(30, 180));
	}

	@Test
	@DisplayName("fails closed instead of truncating a reverse destination frontier")
	void rejectsReverseDestinationFrontierBeyondCallerLimit() {
		var compiled = forward.compile(reverseFrontierTimetable());
		var observations = new JourneyProfilePruningObservationAccumulator(
			ORACLE_REQUEST_ID, JourneyRaptorPruningInventoryV1.REVERSE_RANGE_RAPTOR);

		assertThatThrownBy(() -> planner.arriveBy(
			query("station-a", "station-b", deadlineAt(33_500, 180)), compiled,
			compiled.activeServiceDay(SERVICE_DATE), RouteTimetableRaptorPlanner.RealtimeOverlay.empty(),
			new JourneyProfileResourcePolicy.ProfilePlanningLimits(100_000L, 32, 1, 32), observations))
			.isInstanceOfSatisfying(ReverseTimetableRaptorPlanner.ReversePlanningLimitException.class, exceeded -> {
				assertThat(exceeded.limit()).isEqualTo(ReverseTimetableRaptorPlanner.PlanningLimit.MAX_DESTINATION_PROFILE_LABELS);
				assertThat(exceeded.observed()).isEqualTo(2);
				assertThat(exceeded.max()).isEqualTo(1);
			});
		assertThat(observations.snapshot().countsByRuleId().get("FAIL_CLOSED_FRONTIER_CAPACITY_V1"))
			.isEqualTo(1L);
	}

	@Test
	@DisplayName("keeps a common upstream trace bound to each distinct downstream suffix")
	void doesNotMixReverseCandidatesAcrossDownstreamSuffixes() {
		var compiled = forward.compile(sharedUpstreamSuffixTimetable());

		var result = arriveBy(compiled, "station-a", "station-b", deadlineAt(34_800, 180),
			RouteTimetableRaptorPlanner.RealtimeOverlay.empty());

		assertThat(result.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
		assertThat(result.itineraries()).hasSize(2);
		assertThat(result.itineraries()).extracting(itinerary -> itinerary.legs().stream()
			.filter(RouteTimetableRaptorPlanner.JourneyRideProjection.class::isInstance)
			.map(RouteTimetableRaptorPlanner.JourneyRideProjection.class::cast)
			.reduce((ignored, ride) -> ride).orElseThrow().tripId())
			.containsExactlyInAnyOrder("fast-second", "safer-second");
	}

	@Test
	@DisplayName("materializes a 24-hour reverse result as forward verified legs with pinned realtime times")
	void materializesDirectItineraryWithPlannedAndRealtimeTimes() {
		var compiled = forward.compile(directTimetable(87_000, 87_600, true, true, 300, 180));
		var overlay = forward.compileRealtimeOverlay(compiled, updates(
			new TimetableRealtimeUpdate("direct", 60, 60, false, "snapshot-delay",
				Instant.parse("2026-07-01T00:00:00Z"))));

		var result = arriveBy(compiled, "station-a", "station-b", 87_903, overlay);

		assertThat(result.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
		assertThat(result.latestReadyAtSeconds()).isEqualTo(87_060 - 405 - SLACK_SECONDS);
		assertThat(result.arrivalAtDestinationSeconds()).isEqualTo(87_903);
		assertThat(result.itinerary().serviceDate()).isEqualTo(SERVICE_DATE);
		assertThat(result.itinerary().plannedDepartureTime()).isEqualTo(Instant.parse("2026-07-01T15:01:45Z"));
		assertThat(result.itinerary().realtimeDepartureTime()).isEqualTo(Instant.parse("2026-07-01T15:02:45Z"));
		assertThat(result.itinerary().plannedArrivalTime()).isEqualTo(Instant.parse("2026-07-01T15:24:03Z"));
		assertThat(result.itinerary().realtimeArrivalTime()).isEqualTo(Instant.parse("2026-07-01T15:25:03Z"));
		assertThat(result.itinerary().legs()).hasSize(3);
		assertThat(result.itinerary().legs().get(0))
			.isInstanceOfSatisfying(RouteTimetableRaptorPlanner.JourneyAccessProjection.class, entry -> {
				assertThat(entry.kind()).isEqualTo(RouteTimetableRaptorPlanner.JourneyAccessKind.ENTRY);
				assertThat(entry.durationSeconds()).isEqualTo(405);
				assertThat(entry.verified()).isTrue();
			});
		assertThat(result.itinerary().legs().get(1))
			.isInstanceOfSatisfying(RouteTimetableRaptorPlanner.JourneyRideProjection.class, ride -> {
				assertThat(ride.tripId()).isEqualTo("direct");
				assertThat(ride.plannedDepartureTime()).isEqualTo(Instant.parse("2026-07-01T15:10:00Z"));
				assertThat(ride.plannedArrivalTime()).isEqualTo(Instant.parse("2026-07-01T15:20:00Z"));
				assertThat(ride.realtimeDepartureTime()).isEqualTo(Instant.parse("2026-07-01T15:11:00Z"));
				assertThat(ride.realtimeArrivalTime()).isEqualTo(Instant.parse("2026-07-01T15:21:00Z"));
			});
		assertThat(result.itinerary().legs().get(2))
			.isInstanceOfSatisfying(RouteTimetableRaptorPlanner.JourneyAccessProjection.class, exit -> {
				assertThat(exit.kind()).isEqualTo(RouteTimetableRaptorPlanner.JourneyAccessKind.EXIT);
				assertThat(exit.durationSeconds()).isEqualTo(243);
				assertThat(exit.verified()).isTrue();
			});
	}

	@Test
	@DisplayName("rejects one-way or unverified access instead of inverting it")
	void rejectsUnverifiedOrWrongDirectionalAccess() {
		var unverified = forward.compile(directTimetable(32_400, 33_000, true, false, 300, 180));
		var wrongDirection = forward.compile(directTimetable(32_400, 33_000, false, true, 300, 180));

		assertThat(arriveBy(unverified, "station-a", "station-b", deadlineAt(33_000, 180),
			RouteTimetableRaptorPlanner.RealtimeOverlay.empty()).outcome())
			.isEqualTo(ReverseTimetableRaptorPlanner.Outcome.NO_VERIFIED_EXIT);
		assertThat(arriveBy(wrongDirection, "station-a", "station-b", deadlineAt(33_000, 180),
			RouteTimetableRaptorPlanner.RealtimeOverlay.empty()).outcome())
			.isEqualTo(ReverseTimetableRaptorPlanner.Outcome.NO_OD_CONNECTION);
	}

	@Test
	@DisplayName("uses only the original directional TRANSFER between two ride legs")
	void followsVerifiedDirectionalTransfer() {
		var compiled = forward.compile(transferTimetable());

		var result = arriveBy(compiled, "station-a", "station-b", deadlineAt(34_800, 180),
			RouteTimetableRaptorPlanner.RealtimeOverlay.empty());

		assertThat(result.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
		assertThat(result.latestReadyAtSeconds()).isEqualTo(32_400 - 405 - SLACK_SECONDS);
		assertThat(result.transfersUsed()).isEqualTo(1);
		assertThat(result.itinerary().legs()).extracting(Object::getClass).containsExactly(
			RouteTimetableRaptorPlanner.JourneyAccessProjection.class,
			RouteTimetableRaptorPlanner.JourneyRideProjection.class,
			RouteTimetableRaptorPlanner.JourneyAccessProjection.class,
			RouteTimetableRaptorPlanner.JourneyRideProjection.class,
			RouteTimetableRaptorPlanner.JourneyAccessProjection.class);
	}

	@Test
	@DisplayName("searches dated trips across a cutoff instead of treating the cutoff as service end")
	void followsOnlyFeasibleTransferFromPredecessorServiceDayIntoNextDay() {
		var compiled = forward.compile(crossCutoffTransferTimetable(false));

		var result = planner.arriveBy(crossDateQuery(96_000, deadlineAt(99_600, 180)), compiled,
			SERVICE_DATE, SERVICE_DATE.plusDays(1), RouteTimetableRaptorPlanner.RealtimeOverlay.empty(), limits());

		assertThat(result.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
		assertThat(result.itinerary().legs()).filteredOn(RouteTimetableRaptorPlanner.JourneyRideProjection.class::isInstance)
			.extracting(RouteTimetableRaptorPlanner.JourneyRideProjection.class::cast)
			.extracting(RouteTimetableRaptorPlanner.JourneyRideProjection::plannedDepartureTime)
			.containsExactly(Instant.parse("2026-07-01T18:00:00Z"), Instant.parse("2026-07-01T18:30:00Z"));
	}

	@Test
	@DisplayName("keeps the same scheduled trip index on separate service dates as separate dated events")
	void keepsSameScheduledTripIndexDistinctAcrossServiceDates() {
		var compiled = forward.compile(repeatedDailyDirectTimetable());

		var result = planner.arriveBy(crossDateQuery(35_000, 124_000), compiled,
			SERVICE_DATE, SERVICE_DATE.plusDays(1), RouteTimetableRaptorPlanner.RealtimeOverlay.empty(), limits());

		assertThat(result.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
		assertThat(result.itineraries()).hasSize(2);
		assertThat(result.itineraries()).extracting(itinerary -> itinerary.legs().stream()
			.filter(RouteTimetableRaptorPlanner.JourneyRideProjection.class::isInstance)
			.map(RouteTimetableRaptorPlanner.JourneyRideProjection.class::cast)
			.findFirst().orElseThrow().plannedDepartureTime())
			.containsExactlyInAnyOrder(Instant.parse("2026-07-01T01:00:00Z"), Instant.parse("2026-07-02T01:00:00Z"));
	}

	@Test
	void selectsDateLocalRealtimeOccurrencesForReverseSearchAndLastConnection() {
		var source = repeatedDailyDirectTimetable();
		var compiled = forward.compile(source);
		var routeRuntime = RaptorRouteBundleRuntimeView.compile("a".repeat(64), 1, source);
		var realtime = RaptorRealtimeRuntimeView.compile("reverse-native", routeRuntime, nativeUpdates(
			nativeUpdate(SERVICE_DATE, 60, false),
			nativeUpdate(SERVICE_DATE.plusDays(1), 0, true)));

		var dated = planner.arriveBy(crossDateQuery(35_000, 124_000), compiled,
			SERVICE_DATE, SERVICE_DATE.plusDays(1), realtime, limits(), null);
		assertThat(dated.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
		assertThat(dated.itineraries()).singleElement().satisfies(itinerary -> {
			assertThat(itinerary.serviceDate()).isEqualTo(SERVICE_DATE);
			assertThat(itinerary.legs()).filteredOn(RouteTimetableRaptorPlanner.JourneyRideProjection.class::isInstance)
				.singleElement().isInstanceOfSatisfying(RouteTimetableRaptorPlanner.JourneyRideProjection.class,
					ride -> assertThat(ride.realtimeDepartureTime()).isEqualTo(serviceInstant(36_060)));
		});
		var firstDay = planner.lastConnection(new ReverseTimetableRaptorPlanner.LastConnectionQuery(
			"station-a", "station-b", SERVICE_DATE, 0, PROFILE_BIT, SLACK_SECONDS, MobilityPreset.SLOW, 3_600,
			false, () -> false), compiled, compiled.activeServiceDay(SERVICE_DATE), realtime, limits(), null).result();
		var secondDay = planner.lastConnection(new ReverseTimetableRaptorPlanner.LastConnectionQuery(
			"station-a", "station-b", SERVICE_DATE.plusDays(1), 0, PROFILE_BIT, SLACK_SECONDS, MobilityPreset.SLOW,
			3_600, false, () -> false), compiled, compiled.activeServiceDay(SERVICE_DATE.plusDays(1)), realtime,
			limits(), null).result();
		assertThat(firstDay.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
		assertThat(secondDay.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.NO_OD_CONNECTION);

		var missingDate = RaptorRealtimeRuntimeView.compile("reverse-native", routeRuntime,
			nativeUpdates(nativeUpdate(SERVICE_DATE, 60, false)));
		assertThatThrownBy(() -> planner.arriveBy(crossDateQuery(35_000, 124_000), compiled,
			SERVICE_DATE, SERVICE_DATE.plusDays(1), missingDate, limits(), null))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("service date");
	}

	@Test
	@DisplayName("fails closed when a dated range exceeds work limits or the predecessor calendar is removed")
	void failsClosedForDatedRangeLimitCancellationAndCalendarRemoval() {
		var compiled = forward.compile(crossCutoffTransferTimetable(false));
		assertThatThrownBy(() -> planner.arriveBy(crossDateQuery(96_000, deadlineAt(99_600, 180)), compiled,
			SERVICE_DATE, SERVICE_DATE.plusDays(1), RouteTimetableRaptorPlanner.RealtimeOverlay.empty(),
			new JourneyProfileResourcePolicy.ProfilePlanningLimits(1, 32, 32, 32)))
			.isInstanceOfSatisfying(ReverseTimetableRaptorPlanner.ReversePlanningLimitException.class,
				exceeded -> assertThat(exceeded.limit())
					.isEqualTo(ReverseTimetableRaptorPlanner.PlanningLimit.MAX_ESTIMATED_WORK));
		assertThat(planner.arriveBy(new ReverseTimetableRaptorPlanner.Query(
			"station-a", "station-b", SERVICE_DATE, 96_000, deadlineAt(99_600, 180), 1, PROFILE_BIT,
			SLACK_SECONDS, MobilityPreset.SLOW, 3_600, false, () -> true), compiled,
			SERVICE_DATE, SERVICE_DATE.plusDays(1), RouteTimetableRaptorPlanner.RealtimeOverlay.empty(), limits()).outcome())
			.isEqualTo(ReverseTimetableRaptorPlanner.Outcome.CANCELLED);
		var removed = forward.compile(crossCutoffTransferTimetable(true));
		assertThat(planner.arriveBy(crossDateQuery(96_000, deadlineAt(99_600, 180)), removed,
			SERVICE_DATE, SERVICE_DATE.plusDays(1), RouteTimetableRaptorPlanner.RealtimeOverlay.empty(), limits()).outcome())
			.isEqualTo(ReverseTimetableRaptorPlanner.Outcome.NO_OD_CONNECTION);
	}

	@Test
	@DisplayName("observes cancellation during dated enumeration before spending the next work unit")
	void cancelsDuringDatedEnumeration() {
		var compiled = forward.compile(crossCutoffTransferTimetable(false));
		var checks = new java.util.concurrent.atomic.AtomicInteger();
		var query = new ReverseTimetableRaptorPlanner.Query(
			"station-a", "station-b", SERVICE_DATE, 96_000, deadlineAt(99_600, 180), 1, PROFILE_BIT,
			SLACK_SECONDS, MobilityPreset.SLOW, 3_600, false, () -> checks.incrementAndGet() >= 3);
		assertThat(planner.arriveBy(query, compiled, SERVICE_DATE, SERVICE_DATE,
			RouteTimetableRaptorPlanner.RealtimeOverlay.empty(),
			new JourneyProfileResourcePolicy.ProfilePlanningLimits(1, 32, 32, 32)).outcome())
			.isEqualTo(ReverseTimetableRaptorPlanner.Outcome.CANCELLED);
	}

	@Test
	@DisplayName("matches the raw scheduled exact oracle for shared upstream arrive-by suffixes")
	void arriveByMatchesRawScheduledExactOracleForSharedUpstreamSuffixes() {
		var source = sharedUpstreamSuffixTimetable();
		int deadline = deadlineAt(34_800, 180);
		var expected = scheduledOracle(source, serviceInstant(0), serviceInstant(deadline));
		var compiled = forward.compile(source);
		var actual = planner.arriveBy(new ReverseTimetableRaptorPlanner.Query(
			"station-a", "station-b", SERVICE_DATE, 0, deadline, 1, PROFILE_BIT, SLACK_SECONDS,
			MobilityPreset.SLOW, 3_600, true, () -> false), compiled, compiled.activeServiceDay(SERVICE_DATE),
			RouteTimetableRaptorPlanner.RealtimeOverlay.empty(), limits());

		assertThat(expected).extracting(candidate -> candidate.rides().stream()
			.map(JourneyProfileExactOracle.Ride::tripId).toList())
			.containsExactlyInAnyOrder(List.of("shared-first", "fast-second"), List.of("shared-first", "safer-second"));
		assertThat(actual.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
		assertExactOracleMatches(source, expected, actual.itineraries());
	}

	@Test
	@DisplayName("distinguishes calendar exclusion, realtime cancellation, and delayed deadline misses")
	void classifiesInactiveAndPinnedRealtimeChanges() {
		var compiled = forward.compile(directTimetable(32_400, 33_000, true, true, 300, 180));
		var noService = planner.arriveBy(query("station-a", "station-b", deadlineAt(33_000, 180)), compiled,
			compiled.activeServiceDay(SERVICE_DATE.plusDays(1)), RouteTimetableRaptorPlanner.RealtimeOverlay.empty(), limits());
		var cancelled = forward.compileRealtimeOverlay(compiled, updates(
			new TimetableRealtimeUpdate("direct", 0, 0, true, "snapshot-cancel", Instant.parse("2026-07-01T00:00:00Z"))));
		var delayed = forward.compileRealtimeOverlay(compiled, updates(
			new TimetableRealtimeUpdate("direct", 300, 300, false, "snapshot-delay", Instant.parse("2026-07-01T00:00:00Z"))));

		assertThat(noService.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.NO_ACTIVE_SERVICE);
		assertThat(noService.itinerary()).isNull();
		assertThat(arriveBy(compiled, "station-a", "station-b", deadlineAt(33_000, 180), cancelled).outcome())
			.isEqualTo(ReverseTimetableRaptorPlanner.Outcome.NO_OD_CONNECTION);
		assertThat(arriveBy(compiled, "station-a", "station-b", deadlineAt(33_000, 180), delayed).outcome())
			.isEqualTo(ReverseTimetableRaptorPlanner.Outcome.DEADLINE_MISS);
	}

	@Test
	@DisplayName("requires upstream pickup and downstream drop-off permissions")
	void requiresPickupAndDropOffRestrictions() {
		var noPickup = forward.compile(directTimetableWithRestrictions(1, 0));
		var noDropOff = forward.compile(directTimetableWithRestrictions(0, 1));

		assertThat(arriveBy(noPickup, "station-a", "station-b", deadlineAt(33_000, 180),
			RouteTimetableRaptorPlanner.RealtimeOverlay.empty()).outcome())
			.isEqualTo(ReverseTimetableRaptorPlanner.Outcome.NO_OD_CONNECTION);
		assertThat(arriveBy(noDropOff, "station-a", "station-b", deadlineAt(33_000, 180),
			RouteTimetableRaptorPlanner.RealtimeOverlay.empty()).outcome())
			.isEqualTo(ReverseTimetableRaptorPlanner.Outcome.NO_OD_CONNECTION);
		assertThat(lastConnection(noDropOff, MobilityPreset.SLOW,
			RouteTimetableRaptorPlanner.RealtimeOverlay.empty()).outcome())
			.isEqualTo(ReverseTimetableRaptorPlanner.Outcome.NO_OD_CONNECTION);
	}

	@Test
	@DisplayName("chooses the latest feasible O/D connection rather than an unconnectable later origin trip")
	void lastConnectionUsesTransferFeasibility() {
		var compiled = forward.compile(lastConnectionTransferTimetable());
		var resultWithHorizon = planner.lastConnection(new ReverseTimetableRaptorPlanner.LastConnectionQuery(
			"station-a", "station-b", SERVICE_DATE, 1, PROFILE_BIT, SLACK_SECONDS, MobilityPreset.SLOW, 3_600, false,
			() -> false), compiled, compiled.activeServiceDay(SERVICE_DATE),
			RouteTimetableRaptorPlanner.RealtimeOverlay.empty(), limits());
		var result = resultWithHorizon.result();

		assertThat(result.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
		assertThat(result.latestReadyAtSeconds()).isEqualTo(36_000 - 405 - SLACK_SECONDS);
		assertThat(result.itinerary().legs()).filteredOn(RouteTimetableRaptorPlanner.JourneyRideProjection.class::isInstance)
			.extracting(RouteTimetableRaptorPlanner.JourneyRideProjection.class::cast)
			.extracting(RouteTimetableRaptorPlanner.JourneyRideProjection::tripId)
			.containsExactly("feasible-first", "feasible-second");
		assertThat(resultWithHorizon.terminalArrivalAtDestinationSeconds())
			.isEqualTo(deadlineAt(39_300, 180));
	}

	@Test
	@DisplayName("matches the raw scheduled exact oracle for the last feasible transfer")
	void lastConnectionMatchesRawScheduledExactOracleForLastFeasibleTransfer() {
		var source = lastConnectionTransferTimetable();
		var allExpected = scheduledOracle(source, serviceInstant(0), serviceInstant(deadlineAt(39_300, 180)));
		var latestReadyAt = allExpected.stream().map(JourneyProfileExactOracle.Candidate::readyAt)
			.max(Instant::compareTo).orElseThrow();
		var expected = allExpected.stream()
			.filter(candidate -> candidate.readyAt().equals(latestReadyAt))
			.toList();
		var compiled = forward.compile(source);
		var actual = planner.lastConnection(new ReverseTimetableRaptorPlanner.LastConnectionQuery(
			"station-a", "station-b", SERVICE_DATE, 1, PROFILE_BIT, SLACK_SECONDS, MobilityPreset.SLOW, 3_600, true,
			() -> false), compiled, compiled.activeServiceDay(SERVICE_DATE),
			RouteTimetableRaptorPlanner.RealtimeOverlay.empty(), limits()).result();

		assertThat(expected).extracting(candidate -> candidate.rides().stream()
			.map(JourneyProfileExactOracle.Ride::tripId).toList())
			.containsExactlyInAnyOrder(List.of("feasible-first", "feasible-second"),
				List.of("feasible-first", "late-second"));
		assertThat(allExpected).extracting(candidate -> candidate.rides().stream()
			.map(JourneyProfileExactOracle.Ride::tripId).toList()).doesNotContain(List.of("late-first", "late-second"));
		assertThat(actual.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
		assertThat(actual.latestReadyAtSeconds()).isEqualTo(serviceSeconds(latestReadyAt));
		assertExactOracleMatches(source, expected, actual.itineraries());
	}

	@Test
	@DisplayName("uses the actual extended-hour terminal event instead of a clock cutoff")
	void lastConnectionUsesExtendedHourTerminalEvent() {
		var compiled = forward.compile(directTimetable(93_000, 93_600, true, true, 300, 180));

		var result = lastConnection(compiled, MobilityPreset.SLOW, RouteTimetableRaptorPlanner.RealtimeOverlay.empty());

		assertThat(result.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
		assertThat(result.latestReadyAtSeconds()).isEqualTo(93_000 - 405 - SLACK_SECONDS);
		assertThat(result.arrivalAtDestinationSeconds()).isEqualTo(deadlineAt(93_600, 180));
	}

	@Test
	@DisplayName("applies mobility access cost when deriving the last feasible ready time")
	void lastConnectionAppliesMobilityAccessCost() {
		var compiled = forward.compile(directTimetable(40_000, 40_600, true, true, 300, 180));

		var normal = lastConnection(compiled, MobilityPreset.STANDARD, RouteTimetableRaptorPlanner.RealtimeOverlay.empty());
		var slow = lastConnection(compiled, MobilityPreset.SLOW, RouteTimetableRaptorPlanner.RealtimeOverlay.empty());

		assertThat(normal.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
		assertThat(slow.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
		assertThat(slow.latestReadyAtSeconds()).isLessThan(normal.latestReadyAtSeconds());
	}

	@Test
	@DisplayName("uses the pinned overlay for cancelled and delayed terminal events")
	void lastConnectionUsesPinnedRealtimeTerminalEvents() {
		var compiled = forward.compile(twoDirectTimetable());
		var cancelledLatest = forward.compileRealtimeOverlay(compiled, updates(
			new TimetableRealtimeUpdate("late", 0, 0, true, "cancel-late", Instant.parse("2026-07-01T00:00:00Z"))));
		var delayedLatest = forward.compileRealtimeOverlay(compiled, updates(
			new TimetableRealtimeUpdate("late", 300, 300, false, "delay-late", Instant.parse("2026-07-01T00:00:00Z"))));

		var cancelled = lastConnection(compiled, MobilityPreset.SLOW, cancelledLatest);
		var delayed = lastConnection(compiled, MobilityPreset.SLOW, delayedLatest);

		assertThat(cancelled.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
		assertThat(cancelled.latestReadyAtSeconds()).isEqualTo(40_000 - 405 - SLACK_SECONDS);
		assertThat(delayed.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
		assertThat(delayed.latestReadyAtSeconds()).isEqualTo(42_000 + 300 - 405 - SLACK_SECONDS);
	}

	@Test
	@DisplayName("finds reverse arrive-by journey through out-of-station transfer")
	void findsReverseArriveByThroughOutOfStationTransfer() {
		var compiled = forward.compile(outOfStationTransferTimetable());

		var result = arriveBy(compiled, "station-a", "station-b", deadlineAt(34_800, 180),
			RouteTimetableRaptorPlanner.RealtimeOverlay.empty());

		assertThat(result.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
		assertThat(result.itineraries()).singleElement().satisfies(itinerary -> {
			assertThat(itinerary.legs()).hasSize(5);
			var transfer = itinerary.legs().stream()
				.filter(leg -> leg instanceof RouteTimetableRaptorPlanner.JourneyAccessProjection acc
					&& acc.kind() == RouteTimetableRaptorPlanner.JourneyAccessKind.TRANSFER)
				.map(RouteTimetableRaptorPlanner.JourneyAccessProjection.class::cast)
				.findFirst().orElseThrow();
			assertThat(transfer.fromStationId()).isEqualTo("station-transfer-1");
			assertThat(transfer.toStationId()).isEqualTo("station-transfer-2");
			assertThat(transfer.transferType()).isEqualTo("OUT_OF_STATION");
			assertThat(transfer.farePenaltyApplies()).isFalse();
			assertThat(transfer.additionalFareWon()).isEqualTo(0);
		});
	}

	@Test
	@DisplayName("finds last connection through out-of-station transfer")
	void findsLastConnectionThroughOutOfStationTransfer() {
		var compiled = forward.compile(outOfStationTransferTimetable());

		var result = lastConnection(compiled, MobilityPreset.SLOW, RouteTimetableRaptorPlanner.RealtimeOverlay.empty());

		assertThat(result.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
		assertThat(result.itineraries()).singleElement().satisfies(itinerary -> {
			var transfer = itinerary.legs().stream()
				.filter(leg -> leg instanceof RouteTimetableRaptorPlanner.JourneyAccessProjection acc
					&& acc.kind() == RouteTimetableRaptorPlanner.JourneyAccessKind.TRANSFER)
				.map(RouteTimetableRaptorPlanner.JourneyAccessProjection.class::cast)
				.findFirst().orElseThrow();
			assertThat(transfer.fromStationId()).isEqualTo("station-transfer-1");
			assertThat(transfer.toStationId()).isEqualTo("station-transfer-2");
			assertThat(transfer.transferType()).isEqualTo("OUT_OF_STATION");
		});
	}

	@Test
	@DisplayName("applies fare penalty when out-of-station transfer exceeds limit")
	void outOfStationTransferTimeoutAppliesFarePenalty() {
		var compiled = forward.compile(outOfStationTimeoutTimetable());

		var result = arriveBy(compiled, "station-a", "station-b", deadlineAt(54_000, 180),
			RouteTimetableRaptorPlanner.RealtimeOverlay.empty());

		assertThat(result.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
		assertThat(result.itineraries()).singleElement().satisfies(itinerary -> {
			var transfer = itinerary.legs().stream()
				.filter(leg -> leg instanceof RouteTimetableRaptorPlanner.JourneyAccessProjection acc
					&& acc.kind() == RouteTimetableRaptorPlanner.JourneyAccessKind.TRANSFER)
				.map(RouteTimetableRaptorPlanner.JourneyAccessProjection.class::cast)
				.findFirst().orElseThrow();
			assertThat(transfer.transferType()).isEqualTo("OUT_OF_STATION");
			assertThat(transfer.farePenaltyApplies()).isTrue();
			assertThat(transfer.additionalFareWon()).isEqualTo(1400);
			assertThat(transfer.transferLimitMinutes()).isEqualTo(30);
		});
	}

	@Test
	@DisplayName("unverified out-of-station transfer fails search and records eligibility count")
	void unverifiedOutOfStationTransferCountsEligibility() {
		var compiled = forward.compile(unverifiedOutOfStationTransferTimetable());
		var observations = new JourneyProfilePruningObservationAccumulator(
			ORACLE_REQUEST_ID, JourneyRaptorPruningInventoryV1.REVERSE_RANGE_RAPTOR);

		var result = planner.arriveBy(
			query("station-a", "station-b", deadlineAt(34_800, 180)), compiled,
			compiled.activeServiceDay(SERVICE_DATE), RouteTimetableRaptorPlanner.RealtimeOverlay.empty(),
			limits(), observations);

		assertThat(result.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.NO_OD_CONNECTION);
		assertThat(observations.snapshot().countsByRuleId().get("HARD_TRANSFER_ACCESS_ELIGIBILITY_V1"))
			.isNotNull().isGreaterThanOrEqualTo(1L);
	}

	@Test
	@DisplayName("finds reverse arrive-by journey through same-station out-of-station transfer")
	void findsReverseArriveByThroughSameStationOutOfStationTransfer() {
		var compiled = forward.compile(sameStationOutOfStationTransferTimetable());

		var result = arriveBy(compiled, "station-a", "station-b", deadlineAt(34_800, 180),
			RouteTimetableRaptorPlanner.RealtimeOverlay.empty());

		assertThat(result.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
		assertThat(result.itineraries()).singleElement().satisfies(itinerary -> {
			var transfer = itinerary.legs().stream()
				.filter(leg -> leg instanceof RouteTimetableRaptorPlanner.JourneyAccessProjection acc
					&& acc.kind() == RouteTimetableRaptorPlanner.JourneyAccessKind.TRANSFER)
				.map(RouteTimetableRaptorPlanner.JourneyAccessProjection.class::cast)
				.findFirst().orElseThrow();
			assertThat(transfer.fromStationId()).isEqualTo("station-transfer");
			assertThat(transfer.toStationId()).isEqualTo("station-transfer");
			assertThat(transfer.transferType()).isEqualTo("OUT_OF_STATION");
		});
	}

	@Test
	@DisplayName("finds reverse arrive-by when first footpath candidate is unverified but second is verified")
	void findsReverseArriveByWhenFirstFootpathCandidateIsUnverified() {
		var compiled = forward.compile(multiCandidateOutOfStationTimetable(true));

		var result = arriveBy(compiled, "station-a", "station-b", deadlineAt(34_800, 180),
			RouteTimetableRaptorPlanner.RealtimeOverlay.empty());

		assertThat(result.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
		assertThat(result.itineraries()).singleElement().satisfies(itinerary -> {
			var transfer = itinerary.legs().stream()
				.filter(leg -> leg instanceof RouteTimetableRaptorPlanner.JourneyAccessProjection acc
					&& acc.kind() == RouteTimetableRaptorPlanner.JourneyAccessKind.TRANSFER)
				.map(RouteTimetableRaptorPlanner.JourneyAccessProjection.class::cast)
				.findFirst().orElseThrow();
			assertThat(transfer.fromStationId()).isEqualTo("station-transfer-1");
			assertThat(transfer.toStationId()).isEqualTo("station-transfer-2");
			assertThat(transfer.transferType()).isEqualTo("OUT_OF_STATION");
			assertThat(transfer.verified()).isTrue();
			assertThat(transfer.includesStairs()).isFalse();
		});
	}

	@Test
	@DisplayName("finds reverse arrive-by preferring step-free candidate on out-of-station footpath")
	void findsReverseArriveByPreferringStepFreeOnOutOfStationFootpath() {
		var compiled = forward.compile(multiCandidateOutOfStationTimetable(false));
		int stepFreeProfileBit = RouteTimetableRaptorPlanner.profileBit(
			MobilityType.SENIOR, ConstraintMode.PREFER_STEP_FREE);
		var query = new ReverseTimetableRaptorPlanner.Query(
			"station-a", "station-b", SERVICE_DATE, 0, deadlineAt(34_800, 180), 1, stepFreeProfileBit,
			SLACK_SECONDS, MobilityPreset.SLOW, 3_600, false, () -> false);

		var result = planner.arriveBy(query, compiled,
			compiled.activeServiceDay(SERVICE_DATE), RouteTimetableRaptorPlanner.RealtimeOverlay.empty(), limits());

		assertThat(result.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
		assertThat(result.itineraries()).singleElement().satisfies(itinerary -> {
			var transfer = itinerary.legs().stream()
				.filter(leg -> leg instanceof RouteTimetableRaptorPlanner.JourneyAccessProjection acc
					&& acc.kind() == RouteTimetableRaptorPlanner.JourneyAccessKind.TRANSFER)
				.map(RouteTimetableRaptorPlanner.JourneyAccessProjection.class::cast)
				.findFirst().orElseThrow();
			assertThat(transfer.transferType()).isEqualTo("OUT_OF_STATION");
			assertThat(transfer.includesStairs()).isFalse();
		});
	}

	@Test
	@DisplayName("finds reverse arrive-by through multi-transfer combining in-station and out-of-station")
	void findsReverseArriveByThroughMultiTransferWithOutOfStation() {
		var compiled = forward.compile(multiTransferTimetable());
		var query = new ReverseTimetableRaptorPlanner.Query(
			"station-a", "station-b", SERVICE_DATE, 0, deadlineAt(33_900, 180), 2, PROFILE_BIT,
			SLACK_SECONDS, MobilityPreset.SLOW, 3_600, false, () -> false);

		var result = planner.arriveBy(query, compiled,
			compiled.activeServiceDay(SERVICE_DATE), RouteTimetableRaptorPlanner.RealtimeOverlay.empty(), limits());

		assertThat(result.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
		assertThat(result.itineraries()).singleElement().satisfies(itinerary -> {
			assertThat(itinerary.metrics().transfersUsed()).isEqualTo(2);
			var transfers = itinerary.legs().stream()
				.filter(leg -> leg instanceof RouteTimetableRaptorPlanner.JourneyAccessProjection acc
					&& acc.kind() == RouteTimetableRaptorPlanner.JourneyAccessKind.TRANSFER)
				.map(RouteTimetableRaptorPlanner.JourneyAccessProjection.class::cast)
				.toList();
			assertThat(transfers).hasSize(2);
			// In-station transfer
			assertThat(transfers.get(0).fromStationId()).isEqualTo("station-transfer-in");
			assertThat(transfers.get(0).toStationId()).isEqualTo("station-transfer-in");
			assertThat(transfers.get(0).transferType()).isNull();
			// Out-of-station transfer
			assertThat(transfers.get(1).fromStationId()).isEqualTo("station-out-1");
			assertThat(transfers.get(1).toStationId()).isEqualTo("station-out-2");
			assertThat(transfers.get(1).transferType()).isEqualTo("OUT_OF_STATION");
		});
	}

	@Test
	@DisplayName("invalid station or line index does not count transfer opportunity")
	void invalidStationOrLineDoesNotCountTransferOpportunity() {
		var compiled = forward.compile(outOfStationTransferTimetable());
		int downstreamStation = compiled.stationIndex("station-transfer-2");
		int downstreamLine = compiled.lineIndex("line-b");
		int upstreamStation = compiled.stationIndex("station-transfer-1");
		int upstreamLine = compiled.lineIndex("line-a");
		var footpaths = compiled.footpathsToStationLine(downstreamStation, downstreamLine);
		assertThat(footpaths).isNotNull();

		var query = query("station-a", "station-b", 40_000);
		// evaluateTransfer: boundary guards must return NONE for each negative index individually
		var evalStation = ReverseTimetableRaptorPlanner.evaluateTransfer(
			compiled, query, -1, "station-transfer-2", downstreamLine, upstreamStation, "station-transfer-1", upstreamLine, footpaths);
		assertThat(evalStation.hasOpportunity()).isFalse();
		assertThat(evalStation.match()).isNull();

		var evalDownstreamLine = ReverseTimetableRaptorPlanner.evaluateTransfer(
			compiled, query, downstreamStation, "station-transfer-2", -1, upstreamStation, "station-transfer-1", upstreamLine, footpaths);
		assertThat(evalDownstreamLine.hasOpportunity()).isFalse();
		assertThat(evalDownstreamLine.match()).isNull();

		var evalUpstreamStation = ReverseTimetableRaptorPlanner.evaluateTransfer(
			compiled, query, downstreamStation, "station-transfer-2", downstreamLine, -1, "station-transfer-1", upstreamLine, footpaths);
		assertThat(evalUpstreamStation.hasOpportunity()).isFalse();
		assertThat(evalUpstreamStation.match()).isNull();

		var evalUpstreamLine = ReverseTimetableRaptorPlanner.evaluateTransfer(
			compiled, query, downstreamStation, "station-transfer-2", downstreamLine, upstreamStation, "station-transfer-1", -1, footpaths);
		assertThat(evalUpstreamLine.hasOpportunity()).isFalse();
		assertThat(evalUpstreamLine.match()).isNull();

		// Critical boundary case: station == upstreamStation == -1 must NOT match
		var evalBothStations = ReverseTimetableRaptorPlanner.evaluateTransfer(
			compiled, query, -1, "station-transfer-2", downstreamLine, -1, "station-transfer-1", upstreamLine, footpaths);
		assertThat(evalBothStations.hasOpportunity()).isFalse();
		assertThat(evalBothStations.match()).isNull();

		// Boundary case: downstreamLine == upstreamLine == -1 must NOT match
		var evalBothLines = ReverseTimetableRaptorPlanner.evaluateTransfer(
			compiled, query, downstreamStation, "station-transfer-2", -1, upstreamStation, "station-transfer-1", -1, footpaths);
		assertThat(evalBothLines.hasOpportunity()).isFalse();
		assertThat(evalBothLines.match()).isNull();

		// Boundary case: null footpaths must safely return NONE without NPE
		var evalNullFootpaths = ReverseTimetableRaptorPlanner.evaluateTransfer(
			compiled, query, downstreamStation, "station-transfer-2", downstreamLine, upstreamStation, "station-transfer-1", upstreamLine, null);
		assertThat(evalNullFootpaths.hasOpportunity()).isFalse();
		assertThat(evalNullFootpaths.match()).isNull();

		// Unmatched footpath candidate station or line must return NONE
		int stationA = compiled.stationIndex("station-a");
		int lineA = compiled.lineIndex("line-a");
		var evalUnmatched = ReverseTimetableRaptorPlanner.evaluateTransfer(
			compiled, query, downstreamStation, "station-transfer-2", downstreamLine, stationA, "station-a", lineA, footpaths);
		assertThat(evalUnmatched.hasOpportunity()).isFalse();
		assertThat(evalUnmatched.match()).isNull();

		int lineB = compiled.lineIndex("line-b");
		var evalUnmatchedLine = ReverseTimetableRaptorPlanner.evaluateTransfer(
			compiled, query, downstreamStation, "station-transfer-2", downstreamLine, upstreamStation, "station-transfer-1", lineB, footpaths);
		assertThat(evalUnmatchedLine.hasOpportunity()).isFalse();
		assertThat(evalUnmatchedLine.match()).isNull();

		// Empty transition candidate array returns -1
		assertThat(ReverseTimetableRaptorPlanner.selectTransferTransition(compiled, new int[0], query)).isEqualTo(-1);

		// stationId indexing boundary checks
		assertThat(compiled.stationId(-1)).isNull();
		assertThat(compiled.stationId(Integer.MAX_VALUE)).isNull();
		assertThat(compiled.stationId(downstreamStation)).isEqualTo("station-transfer-2");
	}

	@Test
	@DisplayName("prefers step-free out-of-station footpath over in-station transfer with stairs at same station")
	void prefersStepFreeOutOfStationFootpathOverInStationTransferWithStairsAtSameStation() {
		var compiled = forward.compile(sameStationStepFreePreferenceTimetable());
		int stepFreeProfileBit = RouteTimetableRaptorPlanner.profileBit(
			MobilityType.SENIOR, ConstraintMode.PREFER_STEP_FREE);
		var query = new ReverseTimetableRaptorPlanner.Query(
			"station-a", "station-b", SERVICE_DATE, 0, deadlineAt(34_800, 180), 1, stepFreeProfileBit,
			SLACK_SECONDS, MobilityPreset.SLOW, 3_600, false, () -> false);

		var result = planner.arriveBy(query, compiled,
			compiled.activeServiceDay(SERVICE_DATE), RouteTimetableRaptorPlanner.RealtimeOverlay.empty(), limits());

		assertThat(result.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
		assertThat(result.itineraries()).singleElement().satisfies(itinerary -> {
			var transfer = itinerary.legs().stream()
				.filter(leg -> leg instanceof RouteTimetableRaptorPlanner.JourneyAccessProjection acc
					&& acc.kind() == RouteTimetableRaptorPlanner.JourneyAccessKind.TRANSFER)
				.map(RouteTimetableRaptorPlanner.JourneyAccessProjection.class::cast)
				.findFirst().orElseThrow();
			assertThat(transfer.fromStationId()).isEqualTo("station-transfer");
			assertThat(transfer.toStationId()).isEqualTo("station-transfer");
			assertThat(transfer.transferType()).isEqualTo("OUT_OF_STATION");
			assertThat(transfer.includesStairs()).isFalse();
			assertThat(transfer.verified()).isTrue();
		});
	}

	@Test
	@DisplayName("prefers step-free footpath when in-station transfer is not available at same station")
	void prefersStepFreeFootpathWhenInStationTransferIsNotAvailableAtSameStation() {
		var compiled = forward.compile(sameStationNoInStationTransferTimetable());
		int stepFreeProfileBit = RouteTimetableRaptorPlanner.profileBit(
			MobilityType.SENIOR, ConstraintMode.PREFER_STEP_FREE);
		var query = new ReverseTimetableRaptorPlanner.Query(
			"station-a", "station-b", SERVICE_DATE, 0, deadlineAt(34_800, 180), 1, stepFreeProfileBit,
			SLACK_SECONDS, MobilityPreset.SLOW, 3_600, false, () -> false);

		var result = planner.arriveBy(query, compiled,
			compiled.activeServiceDay(SERVICE_DATE), RouteTimetableRaptorPlanner.RealtimeOverlay.empty(), limits());

		assertThat(result.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
		assertThat(result.itineraries()).singleElement().satisfies(itinerary -> {
			var transfer = itinerary.legs().stream()
				.filter(leg -> leg instanceof RouteTimetableRaptorPlanner.JourneyAccessProjection acc
					&& acc.kind() == RouteTimetableRaptorPlanner.JourneyAccessKind.TRANSFER)
				.map(RouteTimetableRaptorPlanner.JourneyAccessProjection.class::cast)
				.findFirst().orElseThrow();
			assertThat(transfer.fromStationId()).isEqualTo("station-transfer");
			assertThat(transfer.toStationId()).isEqualTo("station-transfer");
			assertThat(transfer.transferType()).isEqualTo("OUT_OF_STATION");
			assertThat(transfer.includesStairs()).isFalse();
			assertThat(transfer.verified()).isTrue();
		});
	}

	@Test
	@DisplayName("prefers faster in-station transfer with stairs when step-free is not requested")
	void prefersInStationTransferWhenStepFreeIsNotRequested() {
		var compiled = forward.compile(sameStationStepFreePreferenceTimetable());

		var result = arriveBy(compiled, "station-a", "station-b", deadlineAt(34_800, 180),
			RouteTimetableRaptorPlanner.RealtimeOverlay.empty());

		assertThat(result.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
		assertThat(result.itineraries()).singleElement().satisfies(itinerary -> {
			var transfer = itinerary.legs().stream()
				.filter(leg -> leg instanceof RouteTimetableRaptorPlanner.JourneyAccessProjection acc
					&& acc.kind() == RouteTimetableRaptorPlanner.JourneyAccessKind.TRANSFER)
				.map(RouteTimetableRaptorPlanner.JourneyAccessProjection.class::cast)
				.findFirst().orElseThrow();
			assertThat(transfer.fromStationId()).isEqualTo("station-transfer");
			assertThat(transfer.toStationId()).isEqualTo("station-transfer");
			assertThat(transfer.transferType()).isNull();
			assertThat(transfer.includesStairs()).isTrue();
		});
	}

	@Test
	@DisplayName("prefers step-free in-station transfer over out-of-station footpath under step-free preference")
	void prefersStepFreeInStationTransferOverOutOfStationFootpathUnderStepFreePreference() {
		var compiled = forward.compile(sameStationInStationStepFreeTimetable());
		int stepFreeProfileBit = RouteTimetableRaptorPlanner.profileBit(
			MobilityType.SENIOR, ConstraintMode.PREFER_STEP_FREE);
		var query = new ReverseTimetableRaptorPlanner.Query(
			"station-a", "station-b", SERVICE_DATE, 0, deadlineAt(34_800, 180), 1, stepFreeProfileBit,
			SLACK_SECONDS, MobilityPreset.SLOW, 3_600, false, () -> false);

		var result = planner.arriveBy(query, compiled,
			compiled.activeServiceDay(SERVICE_DATE), RouteTimetableRaptorPlanner.RealtimeOverlay.empty(), limits());

		assertThat(result.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
		assertThat(result.itineraries()).singleElement().satisfies(itinerary -> {
			var transfer = itinerary.legs().stream()
				.filter(leg -> leg instanceof RouteTimetableRaptorPlanner.JourneyAccessProjection acc
					&& acc.kind() == RouteTimetableRaptorPlanner.JourneyAccessKind.TRANSFER)
				.map(RouteTimetableRaptorPlanner.JourneyAccessProjection.class::cast)
				.findFirst().orElseThrow();
			assertThat(transfer.fromStationId()).isEqualTo("station-transfer");
			assertThat(transfer.toStationId()).isEqualTo("station-transfer");
			assertThat(transfer.transferType()).isNull();
			assertThat(transfer.includesStairs()).isFalse();
			assertThat(transfer.verified()).isTrue();
		});
	}

	@Test
	@DisplayName("falls back to in-station transfer when both in-station and out-of-station have stairs under step-free preference")
	void fallsBackToInStationTransferWhenBothHaveStairsUnderStepFreePreference() {
		var compiled = forward.compile(sameStationBothStairsTimetable());
		int stepFreeProfileBit = RouteTimetableRaptorPlanner.profileBit(
			MobilityType.SENIOR, ConstraintMode.PREFER_STEP_FREE);
		var query = new ReverseTimetableRaptorPlanner.Query(
			"station-a", "station-b", SERVICE_DATE, 0, deadlineAt(34_800, 180), 1, stepFreeProfileBit,
			SLACK_SECONDS, MobilityPreset.SLOW, 3_600, false, () -> false);

		var result = planner.arriveBy(query, compiled,
			compiled.activeServiceDay(SERVICE_DATE), RouteTimetableRaptorPlanner.RealtimeOverlay.empty(), limits());

		assertThat(result.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
		assertThat(result.itineraries()).singleElement().satisfies(itinerary -> {
			var transfer = itinerary.legs().stream()
				.filter(leg -> leg instanceof RouteTimetableRaptorPlanner.JourneyAccessProjection acc
					&& acc.kind() == RouteTimetableRaptorPlanner.JourneyAccessKind.TRANSFER)
				.map(RouteTimetableRaptorPlanner.JourneyAccessProjection.class::cast)
				.findFirst().orElseThrow();
			assertThat(transfer.fromStationId()).isEqualTo("station-transfer");
			assertThat(transfer.toStationId()).isEqualTo("station-transfer");
			assertThat(transfer.transferType()).isNull();
			assertThat(transfer.includesStairs()).isTrue();
			assertThat(transfer.verified()).isTrue();
		});
	}

	@Test
	@DisplayName("evaluates same-station transfers across step-free preference, stairs fallback, and ineligibility")
	void evaluatesSameStationTransfersAcrossModes() {
		var compiledStepFree = forward.compile(sameStationInStationStepFreeTimetable());
		int station = compiledStepFree.stationIndex("station-transfer");
		int lineA = compiledStepFree.lineIndex("line-a");
		int lineB = compiledStepFree.lineIndex("line-b");
		var footpaths = compiledStepFree.footpathsToStationLine(station, lineB);

		int stepFreeProfileBit = RouteTimetableRaptorPlanner.profileBit(
			MobilityType.SENIOR, ConstraintMode.PREFER_STEP_FREE);
		var queryStepFree = new ReverseTimetableRaptorPlanner.Query(
			"station-a", "station-b", SERVICE_DATE, 0, deadlineAt(34_800, 180), 1, stepFreeProfileBit,
			SLACK_SECONDS, MobilityPreset.SLOW, 3_600, false, () -> false);
		var queryStandard = query("station-a", "station-b", 40_000);

		// 1. Step-free in-station transfer under PREFER_STEP_FREE
		var evalStepFreeIn = ReverseTimetableRaptorPlanner.evaluateTransfer(
			compiledStepFree, queryStepFree, station, "station-transfer", lineB, station, "station-transfer", lineA, footpaths);
		assertThat(evalStepFreeIn.hasOpportunity()).isTrue();
		assertThat(evalStepFreeIn.match()).isNotNull();
		assertThat(compiledStepFree.transitionIncludesStairs(evalStepFreeIn.match().transition())).isFalse();
		assertThat(compiledStepFree.isOutOfStationTransition(evalStepFreeIn.match().transition())).isFalse();

		// 2. Both in-station and out-of-station have stairs under PREFER_STEP_FREE -> falls back to in-station transfer with stairs
		var compiledBothStairs = forward.compile(sameStationBothStairsTimetable());
		var evalBothStairs = ReverseTimetableRaptorPlanner.evaluateTransfer(
			compiledBothStairs, queryStepFree, station, "station-transfer", lineB, station, "station-transfer", lineA,
			compiledBothStairs.footpathsToStationLine(station, lineB));
		assertThat(evalBothStairs.hasOpportunity()).isTrue();
		assertThat(evalBothStairs.match()).isNotNull();
		assertThat(compiledBothStairs.transitionIncludesStairs(evalBothStairs.match().transition())).isTrue();
		assertThat(compiledBothStairs.isOutOfStationTransition(evalBothStairs.match().transition())).isFalse();

		// 3. In-station unverified, out-of-station has stairs under PREFER_STEP_FREE -> falls back to out-of-station footpath
		var compiledStairsFootpathOnly = forward.compile(sameStationStairsFootpathOnlyTimetable());
		var evalStairsFootpathFallback = ReverseTimetableRaptorPlanner.evaluateTransfer(
			compiledStairsFootpathOnly, queryStepFree, station, "station-transfer", lineB, station, "station-transfer", lineA,
			compiledStairsFootpathOnly.footpathsToStationLine(station, lineB));
		assertThat(evalStairsFootpathFallback.hasOpportunity()).isTrue();
		assertThat(evalStairsFootpathFallback.match()).isNotNull();
		assertThat(compiledStairsFootpathOnly.isOutOfStationTransition(evalStairsFootpathFallback.match().transition())).isTrue();

		// 4. Same station with no verified in-station and no footpath -> returns INELIGIBLE under both PREFER_STEP_FREE and standard
		var evalIneligibleStepFree = ReverseTimetableRaptorPlanner.evaluateTransfer(
			compiledStairsFootpathOnly, queryStepFree, station, "station-transfer", lineB, station, "station-transfer", lineA, null);
		assertThat(evalIneligibleStepFree.hasOpportunity()).isTrue();
		assertThat(evalIneligibleStepFree.match()).isNull();

		var evalIneligibleStandard = ReverseTimetableRaptorPlanner.evaluateTransfer(
			compiledStairsFootpathOnly, queryStandard, station, "station-transfer", lineB, station, "station-transfer", lineA, null);
		assertThat(evalIneligibleStandard.hasOpportunity()).isTrue();
		assertThat(evalIneligibleStandard.match()).isNull();
	}

	@Test
	@DisplayName("footpath transfer aligns with query requiresVerifiedJourneyDistance parameter")
	void footpathTransferAlignsWithQueryRequiresVerifiedJourneyDistance() {
		var compiled = forward.compile(unverifiedDistanceOutOfStationTransferTimetable());

		var queryVerified = new ReverseTimetableRaptorPlanner.Query(
			"station-a", "station-b", SERVICE_DATE, 0, deadlineAt(34_800, 180), 1, PROFILE_BIT,
			SLACK_SECONDS, MobilityPreset.SLOW, 3_600, true, () -> false);
		var resultVerified = planner.arriveBy(queryVerified, compiled,
			compiled.activeServiceDay(SERVICE_DATE), RouteTimetableRaptorPlanner.RealtimeOverlay.empty(), limits());
		assertThat(resultVerified.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.NO_OD_CONNECTION);

		var queryUnverified = new ReverseTimetableRaptorPlanner.Query(
			"station-a", "station-b", SERVICE_DATE, 0, deadlineAt(34_800, 180), 1, PROFILE_BIT,
			SLACK_SECONDS, MobilityPreset.SLOW, 3_600, false, () -> false);
		var resultUnverified = planner.arriveBy(queryUnverified, compiled,
			compiled.activeServiceDay(SERVICE_DATE), RouteTimetableRaptorPlanner.RealtimeOverlay.empty(), limits());
		assertThat(resultUnverified.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
		assertThat(resultUnverified.itineraries()).singleElement().satisfies(itinerary -> {
			var transfer = itinerary.legs().stream()
				.filter(leg -> leg instanceof RouteTimetableRaptorPlanner.JourneyAccessProjection acc
					&& acc.kind() == RouteTimetableRaptorPlanner.JourneyAccessKind.TRANSFER)
				.map(RouteTimetableRaptorPlanner.JourneyAccessProjection.class::cast)
				.findFirst().orElseThrow();
			assertThat(transfer.transferType()).isEqualTo("OUT_OF_STATION");
			assertThat(transfer.distanceMeters()).isEqualTo(0);
			assertThat(transfer.verified()).isTrue();
		});
	}

	private ReverseTimetableRaptorPlanner.Result arriveBy(
		RouteTimetableRaptorPlanner.CompiledTimetable compiled,
		String origin,
		String destination,
		int deadlineSeconds,
		RouteTimetableRaptorPlanner.RealtimeOverlay overlay
	) {
		return planner.arriveBy(query(origin, destination, deadlineSeconds), compiled,
			compiled.activeServiceDay(SERVICE_DATE), overlay, limits());
	}

	private ReverseTimetableRaptorPlanner.Result lastConnection(
		RouteTimetableRaptorPlanner.CompiledTimetable compiled,
		MobilityPreset mobilityPreset,
		RouteTimetableRaptorPlanner.RealtimeOverlay overlay
	) {
		return planner.lastConnection(new ReverseTimetableRaptorPlanner.LastConnectionQuery(
			"station-a", "station-b", SERVICE_DATE, 1, PROFILE_BIT, SLACK_SECONDS, mobilityPreset, 3_600, false,
			() -> false), compiled, compiled.activeServiceDay(SERVICE_DATE), overlay, limits()).result();
	}

	private static JourneyProfileResourcePolicy.ProfilePlanningLimits limits() {
		return new JourneyProfileResourcePolicy.ProfilePlanningLimits(100_000L, 32, 32, 32);
	}

	private static List<JourneyProfileExactOracle.Candidate> scheduledOracle(
		RouteTimetable source, Instant earliestReadyAt, Instant arrivalDeadline
	) {
		return new JourneyProfileExactOracle().solve(new JourneyProfileExactOracle.Query(
			"station-a", "station-b", earliestReadyAt, arrivalDeadline, 1, SLACK_SECONDS, 100_000L, () -> false),
			JourneyProfileScheduledOracleInputs.rides(source, SERVICE_DATE, 32),
			JourneyProfileOracleAccessInputs.normalize(source.routeAccessData(), JourneyRequest.MobilityProfile.SLOW,
				JourneyRequest.ConstraintMode.NONE, 3_600, 32));
	}

	private static void assertExactOracleMatches(
		RouteTimetable source, List<JourneyProfileExactOracle.Candidate> expected,
		List<RouteTimetableRaptorPlanner.JourneyItinerary> actual
	) {
		assertThat(actual).hasSize(expected.size());
		var unmatched = new java.util.ArrayList<>(actual);
		for (JourneyProfileExactOracle.Candidate candidate : expected) {
			var matches = unmatched.stream().filter(itinerary -> matchesExactOracle(source, candidate, itinerary)).toList();
			assertThat(matches).singleElement();
			unmatched.remove(matches.getFirst());
		}
		assertThat(unmatched).isEmpty();
	}

	private static boolean matchesExactOracle(
		RouteTimetable source, JourneyProfileExactOracle.Candidate expected,
		RouteTimetableRaptorPlanner.JourneyItinerary actual
	) {
		if (!SERVICE_DATE.equals(actual.serviceDate()) || !expected.readyAt().equals(actual.plannedDepartureTime())
			|| !expected.arrivalAtDestination().equals(actual.plannedArrivalTime())
			|| actual.realtimeDepartureTime() != null || actual.realtimeArrivalTime() != null
			|| expected.transfersUsed() != actual.metrics().transfersUsed()
			|| expected.walkingSeconds() != actual.metrics().accessMovementSeconds()
			|| expected.walkingDistanceMeters() != actual.metrics().accessDistanceMeters()
			|| expected.accessibilityBurden() != actual.metrics().accessibilityBurden()
			|| !sameSlack(expected.minimumConnectionSlack(), actual.metrics().connectionSlack())
			|| actual.legs().size() != expected.accesses().size() + expected.rides().size()) return false;
		for (int index = 0; index < expected.accesses().size(); index += 1) {
			var access = expected.accesses().get(index);
			if (!(actual.legs().get(index * 2) instanceof RouteTimetableRaptorPlanner.JourneyAccessProjection observed)
				|| !access.kind().name().equals(observed.kind().name())
				|| !access.fromStationId().equals(observed.fromStationId()) || !access.toStationId().equals(observed.toStationId())
				|| access.durationSeconds() != observed.durationSeconds() || access.walkingDistanceMeters() != observed.distanceMeters()
				|| (access.accessibilityBurden() != 0) != observed.includesStairs()
				|| !access.usable() || !observed.verified() || !"VERIFIED".equals(observed.verificationStatus())) return false;
			if (index == expected.rides().size()) continue;
			var ride = expected.rides().get(index);
			if (!(actual.legs().get(index * 2 + 1) instanceof RouteTimetableRaptorPlanner.JourneyRideProjection observedRide)
				|| !ride.tripId().equals(observedRide.tripId()) || !ride.fromStationId().equals(observedRide.fromStationId())
				|| !ride.toStationId().equals(observedRide.toStationId()) || !ride.departureAt().equals(observedRide.plannedDepartureTime())
				|| !ride.arrivalAt().equals(observedRide.plannedArrivalTime()) || observedRide.realtimeDepartureTime() != null
				|| observedRide.realtimeArrivalTime() != null || !rawLineId(source, ride.tripId()).equals(observedRide.lineId())
				|| !rawDirectionStationId(source, ride.tripId()).equals(observedRide.directionStationId())) return false;
		}
		return true;
	}

	private static boolean sameSlack(
		JourneyProfileExactOracle.ConnectionSlack expected, com.easysubway.journey.application.JourneyProfileRaptorPort.ConnectionSlack actual
	) {
		return expected instanceof JourneyProfileExactOracle.ConnectionSlack.NoTransfer
			? actual instanceof com.easysubway.journey.application.JourneyProfileRaptorPort.NoTransfer
			: actual instanceof com.easysubway.journey.application.JourneyProfileRaptorPort.MinimumTransferSeconds observed
				&& ((JourneyProfileExactOracle.ConnectionSlack.MinimumTransferSeconds) expected).seconds() == observed.seconds();
	}

	private static String rawLineId(RouteTimetable source, String tripId) {
		var trip = source.transitTrips().stream().filter(value -> tripId.equals(value.id())).findFirst().orElseThrow();
		return source.transitRoutes().stream().filter(route -> trip.routeId().equals(route.id())).findFirst().orElseThrow().lineId();
	}

	private static String rawDirectionStationId(RouteTimetable source, String tripId) {
		return source.transitStopTimes().stream().filter(stop -> tripId.equals(stop.tripId()))
			.max(java.util.Comparator.comparingInt(LoadRouteTimetablePort.TransitStopTime::stopSequence)).orElseThrow().stationId();
	}

	private static Instant serviceInstant(int serviceSeconds) {
		return SERVICE_DATE.atStartOfDay(ServiceDayResolver.ZONE).plusSeconds(serviceSeconds).toInstant();
	}

	private static int serviceSeconds(Instant instant) {
		return Math.toIntExact(Duration.between(
			SERVICE_DATE.atStartOfDay(ServiceDayResolver.ZONE).toInstant(), instant).toSeconds());
	}

	private static ReverseTimetableRaptorPlanner.Query query(String origin, String destination, int deadlineSeconds) {
		return new ReverseTimetableRaptorPlanner.Query(
			origin, destination, SERVICE_DATE, 0, deadlineSeconds, 1, PROFILE_BIT, SLACK_SECONDS,
			MobilityPreset.SLOW, 3_600, false, () -> false);
	}

	private static ReverseTimetableRaptorPlanner.Query crossDateQuery(int earliest, int deadline) {
		return new ReverseTimetableRaptorPlanner.Query(
			"station-a", "station-b", SERVICE_DATE, earliest, deadline, 1, PROFILE_BIT, SLACK_SECONDS,
			MobilityPreset.SLOW, 3_600, false, () -> false);
	}

	private static int deadlineAt(int trainArrivalSeconds, int baselineExitSeconds) {
		return trainArrivalSeconds + ProfileWalkTimeCalculator.estimateSeconds(
			baselineExitSeconds, MobilityPreset.SLOW, WalkTimeSource.MEASURED_PATHWAY, false).seconds();
	}

	private static int accessMovementAt(int baselineEntrySeconds, int baselineExitSeconds) {
		return ProfileWalkTimeCalculator.estimateSeconds(
			baselineEntrySeconds, MobilityPreset.SLOW, WalkTimeSource.OFFICIAL_BASELINE, false).seconds()
			+ ProfileWalkTimeCalculator.estimateSeconds(
				baselineExitSeconds, MobilityPreset.SLOW, WalkTimeSource.OFFICIAL_BASELINE, false).seconds();
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

	private static RouteTimetable reverseFrontierTimetable() {
		return timetable(
			List.of(trip("later-ready", "route-a"), trip("lower-walk", "route-b")),
			List.of(stop("later-ready", 1, "station-a", "line-a", 33_000, 0, 0),
				stop("later-ready", 2, "station-b", "line-a", 33_500, 0, 0),
				stop("lower-walk", 1, "station-a", "line-b", 32_500, 0, 0),
				stop("lower-walk", 2, "station-b", "line-b", 33_400, 0, 0)),
			reverseFrontierAccess());
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

	private static RouteTimetable sharedUpstreamSuffixTimetable() {
		return timetable(
			List.of(trip("shared-first", "route-a"), trip("fast-second", "route-b"),
				trip("safer-second", "route-b")),
			List.of(stop("shared-first", 1, "station-a", "line-a", 32_400, 0, 0),
				stop("shared-first", 2, "station-transfer", "line-a", 33_000, 0, 0),
				stop("fast-second", 1, "station-transfer", "line-b", 33_600, 0, 0),
				stop("fast-second", 2, "station-b", "line-b", 34_200, 0, 0),
				stop("safer-second", 1, "station-transfer", "line-b", 34_200, 0, 0),
				stop("safer-second", 2, "station-b", "line-b", 34_800, 0, 0)),
			access(true, true, 300, 180, true));
	}

	private static RouteTimetable lastConnectionTransferTimetable() {
		return timetable(
			List.of(trip("feasible-first", "route-a"), trip("late-first", "route-a"),
				trip("feasible-second", "route-b"), trip("late-second", "route-b")),
			List.of(stop("feasible-first", 1, "station-a", "line-a", 36_000, 0, 0),
				stop("feasible-first", 2, "station-transfer", "line-a", 36_600, 0, 0),
				stop("late-first", 1, "station-a", "line-a", 38_000, 0, 0),
				stop("late-first", 2, "station-transfer", "line-a", 38_600, 0, 0),
				stop("feasible-second", 1, "station-transfer", "line-b", 37_200, 0, 0),
				stop("feasible-second", 2, "station-b", "line-b", 37_800, 0, 0),
				stop("late-second", 1, "station-transfer", "line-b", 38_700, 0, 0),
				stop("late-second", 2, "station-b", "line-b", 39_300, 0, 0)),
			access(true, true, 300, 180, true));
	}

	private static RouteTimetable twoDirectTimetable() {
		return timetable(
			List.of(trip("early", "route-direct"), trip("late", "route-direct")),
			List.of(stop("early", 1, "station-a", "line-a", 40_000, 0, 0),
				stop("early", 2, "station-b", "line-a", 40_600, 0, 0),
				stop("late", 1, "station-a", "line-a", 42_000, 0, 0),
				stop("late", 2, "station-b", "line-a", 42_600, 0, 0)),
			access(true, true, 300, 180, false));
	}

	private static RouteTimetable crossCutoffTransferTimetable(boolean removePredecessorServiceDay) {
		var calendar = new LoadRouteTimetablePort.ServiceCalendar(
			"daily", true, true, true, true, true, true, true,
			SERVICE_DATE, SERVICE_DATE.plusDays(1), "Asia/Seoul");
		var exceptions = removePredecessorServiceDay
			? List.of(new LoadRouteTimetablePort.ServiceCalendarDate("daily", SERVICE_DATE, 2))
			: List.<LoadRouteTimetablePort.ServiceCalendarDate>of();
		var trips = List.of(trip("predecessor-27h", "route-a"), trip("next-day", "route-b"));
		var routes = trips.stream().map(value -> new LoadRouteTimetablePort.TransitRoute(
			value.routeId(), value.routeId(), value.routeId(), value.routeId(), "terminal", "Asia/Seoul")).toList();
		return new RouteTimetable(List.of(calendar), exceptions, routes, trips, List.of(
			stop("predecessor-27h", 1, "station-a", "line-a", 97_200, 0, 0),
			stop("predecessor-27h", 2, "station-transfer", "line-a", 97_800, 0, 0),
			stop("next-day", 1, "station-transfer", "line-b", 12_600, 0, 0),
			stop("next-day", 2, "station-b", "line-b", 13_200, 0, 0)),
			List.of(), List.of(), null, access(true, true, 300, 180, true));
	}

	private static RouteTimetable repeatedDailyDirectTimetable() {
		var calendar = new LoadRouteTimetablePort.ServiceCalendar(
			"daily", true, true, true, true, true, true, true,
			SERVICE_DATE, SERVICE_DATE.plusDays(1), "Asia/Seoul");
		var direct = trip("same-index", "route-direct");
		var route = new LoadRouteTimetablePort.TransitRoute(
			"route-direct", "route-direct", "route-direct", "route-direct", "terminal", "Asia/Seoul");
		return new RouteTimetable(List.of(calendar), List.of(), List.of(route), List.of(direct), List.of(
			stop("same-index", 1, "station-a", "line-a", 36_000, 0, 0),
			stop("same-index", 2, "station-b", "line-a", 36_600, 0, 0)),
			List.of(), List.of(), null, access(true, true, 300, 180, false));
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
			trip.routeId(), trip.routeId(), trip.routeId(), trip.routeId(), "terminal", "Asia/Seoul")).distinct().toList();
		return new RouteTimetable(
			List.of(calendar), List.of(), routes, trips, stopTimes, List.of(), List.of(), null, access);
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

	private static LoadRouteTimetablePort.RouteAccessData reverseFrontierAccess() {
		var nodes = List.of(
			new LoadRouteTimetablePort.PathwayNode("entry-outside-a", "station-a", null, "ENTRANCE"),
			new LoadRouteTimetablePort.PathwayNode("entry-platform-a", "station-a", "line-a", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("entry-outside-b", "station-a", null, "ENTRANCE"),
			new LoadRouteTimetablePort.PathwayNode("entry-platform-b", "station-a", "line-b", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("exit-platform-a", "station-b", "line-a", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("exit-outside-a", "station-b", null, "EXIT"),
			new LoadRouteTimetablePort.PathwayNode("exit-platform-b", "station-b", "line-b", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("exit-outside-b", "station-b", null, "EXIT"));
		var edges = List.of(
			edge("entry-a", "entry-outside-a", "entry-platform-a", 300, "VERIFIED"),
			edge("entry-b", "entry-outside-b", "entry-platform-b", 30, "VERIFIED"),
			edge("exit-a", "exit-platform-a", "exit-outside-a", 180, "VERIFIED"),
			edge("exit-b", "exit-platform-b", "exit-outside-b", 180, "VERIFIED"));
		var evidence = List.of(
			evidence("entry-a-e", "station-a", "line-a", "entry-a", "ENTRY"),
			evidence("entry-b-e", "station-a", "line-b", "entry-b", "ENTRY"),
			evidence("exit-a-e", "station-b", "line-a", "exit-a", "EXIT"),
			evidence("exit-b-e", "station-b", "line-b", "exit-b", "EXIT"));
		return new LoadRouteTimetablePort.RouteAccessData(nodes, edges, List.of(), evidence);
	}

	private static LoadRouteTimetablePort.PathwayEdge edge(
		String id, String from, String to, int seconds, String verificationStatus
	) {
		return edge(id, from, to, seconds, false, verificationStatus);
	}

	private static LoadRouteTimetablePort.PathwayEdge edge(
		String id, String from, String to, int seconds, boolean includesStairs, String verificationStatus
	) {
		return edge(id, from, to, seconds, 100, includesStairs, verificationStatus);
	}

	private static LoadRouteTimetablePort.PathwayEdge edge(
		String id, String from, String to, int seconds, int distanceMeters, boolean includesStairs, String verificationStatus
	) {
		return new LoadRouteTimetablePort.PathwayEdge(id, from, to, seconds, distanceMeters, false, includesStairs, 100,
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

	private static JourneyTimetableRealtimeResolver.Updates nativeUpdates(
		JourneyTimetableRealtimeResolver.Update... updates
	) {
		return new JourneyTimetableRealtimeResolver.Updates("reverse-native", true, List.of(updates), null);
	}

	private static JourneyTimetableRealtimeResolver.Update nativeUpdate(
		LocalDate serviceDate, int deltaSeconds, boolean cancelled
	) {
		Instant scheduled = serviceDate.atStartOfDay(ServiceDayResolver.ZONE).plusSeconds(36_000).toInstant();
		return new JourneyTimetableRealtimeResolver.Update(
			new JourneyTimetableRealtimeResolver.Departure(
				"station-a", "line-a", "same-index", null, "LOCAL", serviceDate, 1, scheduled, scheduled),
			deltaSeconds, deltaSeconds, cancelled, "reverse-native", Instant.parse("2026-07-01T00:00:00Z"));
	}

	private static RouteTimetable outOfStationTransferTimetable() {
		return timetable(
			List.of(trip("first", "route-a"), trip("second", "route-b")),
			List.of(stop("first", 1, "station-a", "line-a", 32_400, 0, 0),
				stop("first", 2, "station-transfer-1", "line-a", 33_000, 0, 0),
				stop("second", 1, "station-transfer-2", "line-b", 34_200, 0, 0),
				stop("second", 2, "station-b", "line-b", 34_800, 0, 0)),
			outOfStationAccess(300, 180, 600));
	}

	private static LoadRouteTimetablePort.RouteAccessData outOfStationAccess(
		int entrySeconds, int exitSeconds, int transferSeconds
	) {
		var nodes = List.of(
			new LoadRouteTimetablePort.PathwayNode("entry-outside", "station-a", null, "ENTRANCE"),
			new LoadRouteTimetablePort.PathwayNode("entry-platform", "station-a", "line-a", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("exit-platform", "station-b", "line-b", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("exit-outside", "station-b", null, "EXIT"),
			new LoadRouteTimetablePort.PathwayNode("transfer-1", "station-transfer-1", "line-a", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("transfer-2", "station-transfer-2", "line-b", "PLATFORM"));
		var entry = edge("entry", "entry-outside", "entry-platform", entrySeconds, "VERIFIED");
		var exit = edge("exit", "exit-platform", "exit-outside", exitSeconds, "VERIFIED");
		var transfer = edge("transfer-edge", "transfer-1", "transfer-2", transferSeconds, "VERIFIED");
		var evidence = List.of(
			evidence("entry-e", "station-a", "line-a", "entry", "ENTRY"),
			evidence("exit-e", "station-b", "line-b", "exit", "EXIT"),
			evidence("transfer-e", "station-transfer-2", "line-b", "transfer-edge", "TRANSFER"));
		var rules = List.of(new LoadRouteTimetablePort.TransferRule(
			"out-transfer-rule", "station-transfer-1", "line-a", "station-transfer-2", "line-b",
			"OUT_OF_STATION", transferSeconds, "transfer-edge", null, "VERIFIED"));
		return new LoadRouteTimetablePort.RouteAccessData(nodes, List.of(entry, exit, transfer), rules, evidence);
	}

	private static RouteTimetable outOfStationTimeoutTimetable() {
		return timetable(
			List.of(trip("first", "route-a"), trip("second", "route-b")),
			List.of(stop("first", 1, "station-a", "line-a", 49_200, 0, 0),
				stop("first", 2, "station-transfer-1", "line-a", 50_400, 0, 0),
				stop("second", 1, "station-transfer-2", "line-b", 52_800, 0, 0),
				stop("second", 2, "station-b", "line-b", 54_000, 0, 0)),
			outOfStationAccess(300, 180, 600));
	}

	private static RouteTimetable unverifiedOutOfStationTransferTimetable() {
		return timetable(
			List.of(trip("first", "route-a"), trip("second", "route-b")),
			List.of(stop("first", 1, "station-a", "line-a", 32_400, 0, 0),
				stop("first", 2, "station-transfer-1", "line-a", 33_000, 0, 0),
				stop("second", 1, "station-transfer-2", "line-b", 34_200, 0, 0),
				stop("second", 2, "station-b", "line-b", 34_800, 0, 0)),
			unverifiedOutOfStationAccess(300, 180, 600));
	}

	private static LoadRouteTimetablePort.RouteAccessData unverifiedOutOfStationAccess(
		int entrySeconds, int exitSeconds, int transferSeconds
	) {
		var nodes = List.of(
			new LoadRouteTimetablePort.PathwayNode("entry-outside", "station-a", null, "ENTRANCE"),
			new LoadRouteTimetablePort.PathwayNode("entry-platform", "station-a", "line-a", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("exit-platform", "station-b", "line-b", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("exit-outside", "station-b", null, "EXIT"),
			new LoadRouteTimetablePort.PathwayNode("transfer-1", "station-transfer-1", "line-a", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("transfer-2", "station-transfer-2", "line-b", "PLATFORM"));
		var entry = edge("entry", "entry-outside", "entry-platform", entrySeconds, "VERIFIED");
		var exit = edge("exit", "exit-platform", "exit-outside", exitSeconds, "VERIFIED");
		var transfer = edge("transfer-edge", "transfer-1", "transfer-2", transferSeconds, "UNKNOWN");
		var evidence = List.of(
			evidence("entry-e", "station-a", "line-a", "entry", "ENTRY"),
			evidence("exit-e", "station-b", "line-b", "exit", "EXIT"),
			evidence("transfer-e", "station-transfer-2", "line-b", "transfer-edge", "TRANSFER"));
		var rules = List.of(new LoadRouteTimetablePort.TransferRule(
			"out-transfer-rule", "station-transfer-1", "line-a", "station-transfer-2", "line-b",
			"OUT_OF_STATION", transferSeconds, "transfer-edge", null, "VERIFIED"));
		return new LoadRouteTimetablePort.RouteAccessData(nodes, List.of(entry, exit, transfer), rules, evidence);
	}

	private static RouteTimetable sameStationOutOfStationTransferTimetable() {
		return timetable(
			List.of(trip("first", "route-a"), trip("second", "route-b")),
			List.of(stop("first", 1, "station-a", "line-a", 32_400, 0, 0),
				stop("first", 2, "station-transfer", "line-a", 33_000, 0, 0),
				stop("second", 1, "station-transfer", "line-b", 34_200, 0, 0),
				stop("second", 2, "station-b", "line-b", 34_800, 0, 0)),
			sameStationOutOfStationAccess(300, 180, 600));
	}

	private static LoadRouteTimetablePort.RouteAccessData sameStationOutOfStationAccess(
		int entrySeconds, int exitSeconds, int transferSeconds
	) {
		var nodes = List.of(
			new LoadRouteTimetablePort.PathwayNode("entry-outside", "station-a", null, "ENTRANCE"),
			new LoadRouteTimetablePort.PathwayNode("entry-platform", "station-a", "line-a", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("exit-platform", "station-b", "line-b", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("exit-outside", "station-b", null, "EXIT"),
			new LoadRouteTimetablePort.PathwayNode("transfer-1", "station-transfer", "line-a", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("transfer-2", "station-transfer", "line-b", "PLATFORM"));
		var entry = edge("entry", "entry-outside", "entry-platform", entrySeconds, "VERIFIED");
		var exit = edge("exit", "exit-platform", "exit-outside", exitSeconds, "VERIFIED");
		var transfer = edge("transfer-edge", "transfer-1", "transfer-2", transferSeconds, "VERIFIED");
		var evidence = List.of(
			evidence("entry-e", "station-a", "line-a", "entry", "ENTRY"),
			evidence("exit-e", "station-b", "line-b", "exit", "EXIT"),
			evidence("transfer-e", "station-transfer", "line-b", "transfer-edge", "TRANSFER"));
		var rules = List.of(new LoadRouteTimetablePort.TransferRule(
			"out-transfer-rule", "station-transfer", "line-a", "station-transfer", "line-b",
			"OUT_OF_STATION", transferSeconds, "transfer-edge", null, "VERIFIED"));
		return new LoadRouteTimetablePort.RouteAccessData(nodes, List.of(entry, exit, transfer), rules, evidence);
	}

	private static RouteTimetable multiCandidateOutOfStationTimetable(boolean firstCandidateUnverified) {
		return timetable(
			List.of(trip("first", "route-a"), trip("second", "route-b")),
			List.of(stop("first", 1, "station-a", "line-a", 32_400, 0, 0),
				stop("first", 2, "station-transfer-1", "line-a", 33_000, 0, 0),
				stop("second", 1, "station-transfer-2", "line-b", 34_200, 0, 0),
				stop("second", 2, "station-b", "line-b", 34_800, 0, 0)),
			multiCandidateOutOfStationAccess(firstCandidateUnverified));
	}

	private static LoadRouteTimetablePort.RouteAccessData multiCandidateOutOfStationAccess(boolean firstCandidateUnverified) {
		var nodes = List.of(
			new LoadRouteTimetablePort.PathwayNode("entry-outside", "station-a", null, "ENTRANCE"),
			new LoadRouteTimetablePort.PathwayNode("entry-platform", "station-a", "line-a", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("exit-platform", "station-b", "line-b", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("exit-outside", "station-b", null, "EXIT"),
			new LoadRouteTimetablePort.PathwayNode("transfer-1", "station-transfer-1", "line-a", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("transfer-2", "station-transfer-2", "line-b", "PLATFORM"));
		var entry = edge("entry", "entry-outside", "entry-platform", 300, "VERIFIED");
		var exit = edge("exit", "exit-platform", "exit-outside", 180, "VERIFIED");
		var normalEdge = edge("normal-edge", "transfer-1", "transfer-2", 240, true,
			firstCandidateUnverified ? "UNKNOWN" : "VERIFIED");
		var strictEdge = edge("strict-edge", "transfer-1", "transfer-2", 360, false, "VERIFIED");
		var evidence = List.of(
			evidence("entry-e", "station-a", "line-a", "entry", "ENTRY"),
			evidence("exit-e", "station-b", "line-b", "exit", "EXIT"),
			evidence("transfer-e-normal", "station-transfer-2", "line-b", "normal-edge", "TRANSFER"),
			evidence("transfer-e-strict", "station-transfer-2", "line-b", "strict-edge", "TRANSFER"));
		var rules = List.of(new LoadRouteTimetablePort.TransferRule(
			"out-transfer-rule", "station-transfer-1", "line-a", "station-transfer-2", "line-b",
			"OUT_OF_STATION", 240, "normal-edge", "strict-edge", "VERIFIED"));
		return new LoadRouteTimetablePort.RouteAccessData(nodes, List.of(entry, exit, normalEdge, strictEdge), rules, evidence);
	}

	private static RouteTimetable multiTransferTimetable() {
		return timetable(
			List.of(trip("first", "route-a"), trip("second", "route-b"), trip("third", "route-c")),
			List.of(stop("first", 1, "station-a", "line-a", 30_000, 0, 0),
				stop("first", 2, "station-transfer-in", "line-a", 30_600, 0, 0),
				stop("second", 1, "station-transfer-in", "line-b", 31_500, 0, 0),
				stop("second", 2, "station-out-1", "line-b", 32_100, 0, 0),
				stop("third", 1, "station-out-2", "line-c", 33_300, 0, 0),
				stop("third", 2, "station-b", "line-c", 33_900, 0, 0)),
			multiTransferAccess());
	}

	private static LoadRouteTimetablePort.RouteAccessData multiTransferAccess() {
		var nodes = List.of(
			new LoadRouteTimetablePort.PathwayNode("entry-outside", "station-a", null, "ENTRANCE"),
			new LoadRouteTimetablePort.PathwayNode("entry-platform", "station-a", "line-a", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("in-transfer-1", "station-transfer-in", "line-a", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("in-transfer-2", "station-transfer-in", "line-b", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("out-transfer-1", "station-out-1", "line-b", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("out-transfer-2", "station-out-2", "line-c", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("exit-platform", "station-b", "line-c", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("exit-outside", "station-b", null, "EXIT"));
		var entry = edge("entry", "entry-outside", "entry-platform", 300, "VERIFIED");
		var inTransfer = edge("in-transfer-edge", "in-transfer-1", "in-transfer-2", 300, "VERIFIED");
		var outTransfer = edge("out-transfer-edge", "out-transfer-1", "out-transfer-2", 600, "VERIFIED");
		var exit = edge("exit", "exit-platform", "exit-outside", 180, "VERIFIED");
		var evidence = List.of(
			evidence("entry-e", "station-a", "line-a", "entry", "ENTRY"),
			evidence("in-transfer-e", "station-transfer-in", "line-b", "in-transfer-edge", "TRANSFER"),
			evidence("out-transfer-e", "station-out-2", "line-c", "out-transfer-edge", "TRANSFER"),
			evidence("exit-e", "station-b", "line-c", "exit", "EXIT"));
		var rules = List.of(
			new LoadRouteTimetablePort.TransferRule(
				"in-transfer-rule", "station-transfer-in", "line-a", "station-transfer-in", "line-b",
				"IN_STATION", 300, "in-transfer-edge", null, "VERIFIED"),
			new LoadRouteTimetablePort.TransferRule(
				"out-transfer-rule", "station-out-1", "line-b", "station-out-2", "line-c",
				"OUT_OF_STATION", 600, "out-transfer-edge", null, "VERIFIED"));
		return new LoadRouteTimetablePort.RouteAccessData(nodes, List.of(entry, inTransfer, outTransfer, exit), rules, evidence);
	}

	private static RouteTimetable sameStationStepFreePreferenceTimetable() {
		return timetable(
			List.of(trip("first", "route-a"), trip("second", "route-b")),
			List.of(stop("first", 1, "station-a", "line-a", 32_400, 0, 0),
				stop("first", 2, "station-transfer", "line-a", 33_000, 0, 0),
				stop("second", 1, "station-transfer", "line-b", 34_200, 0, 0),
				stop("second", 2, "station-b", "line-b", 34_800, 0, 0)),
			sameStationStepFreePreferenceAccess());
	}

	private static LoadRouteTimetablePort.RouteAccessData sameStationStepFreePreferenceAccess() {
		var nodes = List.of(
			new LoadRouteTimetablePort.PathwayNode("entry-outside", "station-a", null, "ENTRANCE"),
			new LoadRouteTimetablePort.PathwayNode("entry-platform", "station-a", "line-a", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("exit-platform", "station-b", "line-b", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("exit-outside", "station-b", null, "EXIT"),
			new LoadRouteTimetablePort.PathwayNode("transfer-1", "station-transfer", "line-a", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("transfer-2", "station-transfer", "line-b", "PLATFORM"));
		var entry = edge("entry", "entry-outside", "entry-platform", 300, "VERIFIED");
		var exit = edge("exit", "exit-platform", "exit-outside", 180, "VERIFIED");
		var inTransfer = edge("in-transfer-edge", "transfer-1", "transfer-2", 240, true, "VERIFIED");
		var outTransfer = edge("out-transfer-edge", "transfer-1", "transfer-2", 360, false, "VERIFIED");
		var evidence = List.of(
			evidence("entry-e", "station-a", "line-a", "entry", "ENTRY"),
			evidence("exit-e", "station-b", "line-b", "exit", "EXIT"),
			evidence("in-transfer-e", "station-transfer", "line-b", "in-transfer-edge", "TRANSFER"),
			evidence("out-transfer-e", "station-transfer", "line-b", "out-transfer-edge", "TRANSFER"));
		var rules = List.of(
			new LoadRouteTimetablePort.TransferRule(
				"in-transfer-rule", "station-transfer", "line-a", "station-transfer", "line-b",
				"IN_STATION", 240, "in-transfer-edge", null, "VERIFIED"),
			new LoadRouteTimetablePort.TransferRule(
				"out-transfer-rule", "station-transfer", "line-a", "station-transfer", "line-b",
				"OUT_OF_STATION", 360, "out-transfer-edge", null, "VERIFIED"));
		return new LoadRouteTimetablePort.RouteAccessData(nodes, List.of(entry, exit, inTransfer, outTransfer), rules, evidence);
	}

	private static RouteTimetable unverifiedDistanceOutOfStationTransferTimetable() {
		return timetable(
			List.of(trip("first", "route-a"), trip("second", "route-b")),
			List.of(stop("first", 1, "station-a", "line-a", 32_400, 0, 0),
				stop("first", 2, "station-transfer-1", "line-a", 33_000, 0, 0),
				stop("second", 1, "station-transfer-2", "line-b", 34_200, 0, 0),
				stop("second", 2, "station-b", "line-b", 34_800, 0, 0)),
			unverifiedDistanceOutOfStationAccess(300, 180, 600));
	}

	private static LoadRouteTimetablePort.RouteAccessData unverifiedDistanceOutOfStationAccess(
		int entrySeconds, int exitSeconds, int transferSeconds
	) {
		var nodes = List.of(
			new LoadRouteTimetablePort.PathwayNode("entry-outside", "station-a", null, "ENTRANCE"),
			new LoadRouteTimetablePort.PathwayNode("entry-platform", "station-a", "line-a", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("exit-platform", "station-b", "line-b", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("exit-outside", "station-b", null, "EXIT"),
			new LoadRouteTimetablePort.PathwayNode("transfer-1", "station-transfer-1", "line-a", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("transfer-2", "station-transfer-2", "line-b", "PLATFORM"));
		var entry = edge("entry", "entry-outside", "entry-platform", entrySeconds, "VERIFIED");
		var exit = edge("exit", "exit-platform", "exit-outside", exitSeconds, "VERIFIED");
		var transfer = edge("transfer-edge", "transfer-1", "transfer-2", transferSeconds, 0, false, "VERIFIED");
		var evidence = List.of(
			evidence("entry-e", "station-a", "line-a", "entry", "ENTRY"),
			evidence("exit-e", "station-b", "line-b", "exit", "EXIT"),
			evidence("transfer-e", "station-transfer-2", "line-b", "transfer-edge", "TRANSFER"));
		var rules = List.of(new LoadRouteTimetablePort.TransferRule(
			"out-transfer-rule", "station-transfer-1", "line-a", "station-transfer-2", "line-b",
			"OUT_OF_STATION", transferSeconds, "transfer-edge", null, "VERIFIED"));
		return new LoadRouteTimetablePort.RouteAccessData(nodes, List.of(entry, exit, transfer), rules, evidence);
	}

	private static RouteTimetable sameStationNoInStationTransferTimetable() {
		return timetable(
			List.of(trip("first", "route-a"), trip("second", "route-b")),
			List.of(stop("first", 1, "station-a", "line-a", 32_400, 0, 0),
				stop("first", 2, "station-transfer", "line-a", 33_000, 0, 0),
				stop("second", 1, "station-transfer", "line-b", 34_200, 0, 0),
				stop("second", 2, "station-b", "line-b", 34_800, 0, 0)),
			sameStationNoInStationTransferAccess());
	}

	private static LoadRouteTimetablePort.RouteAccessData sameStationNoInStationTransferAccess() {
		var nodes = List.of(
			new LoadRouteTimetablePort.PathwayNode("entry-outside", "station-a", null, "ENTRANCE"),
			new LoadRouteTimetablePort.PathwayNode("entry-platform", "station-a", "line-a", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("exit-platform", "station-b", "line-b", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("exit-outside", "station-b", null, "EXIT"),
			new LoadRouteTimetablePort.PathwayNode("transfer-1", "station-transfer", "line-a", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("transfer-2", "station-transfer", "line-b", "PLATFORM"));
		var entry = edge("entry", "entry-outside", "entry-platform", 300, "VERIFIED");
		var exit = edge("exit", "exit-platform", "exit-outside", 180, "VERIFIED");
		var stairsTransfer = edge("out-stairs-edge", "transfer-1", "transfer-2", 240, true, "VERIFIED");
		var stepFreeTransfer = edge("out-stepfree-edge", "transfer-1", "transfer-2", 360, false, "VERIFIED");
		var evidence = List.of(
			evidence("entry-e", "station-a", "line-a", "entry", "ENTRY"),
			evidence("exit-e", "station-b", "line-b", "exit", "EXIT"),
			evidence("stairs-e", "station-transfer", "line-b", "out-stairs-edge", "TRANSFER"),
			evidence("stepfree-e", "station-transfer", "line-b", "out-stepfree-edge", "TRANSFER"));
		var rules = List.of(
			new LoadRouteTimetablePort.TransferRule(
				"out-stairs-rule", "station-transfer", "line-a", "station-transfer", "line-b",
				"OUT_OF_STATION", 240, "out-stairs-edge", null, "VERIFIED"),
			new LoadRouteTimetablePort.TransferRule(
				"out-stepfree-rule", "station-transfer", "line-a", "station-transfer", "line-b",
				"OUT_OF_STATION", 360, "out-stepfree-edge", null, "VERIFIED"));
		return new LoadRouteTimetablePort.RouteAccessData(nodes, List.of(entry, exit, stairsTransfer, stepFreeTransfer), rules, evidence);
	}

	private static RouteTimetable sameStationInStationStepFreeTimetable() {
		return timetable(
			List.of(trip("first", "route-a"), trip("second", "route-b")),
			List.of(stop("first", 1, "station-a", "line-a", 32_400, 0, 0),
				stop("first", 2, "station-transfer", "line-a", 33_000, 0, 0),
				stop("second", 1, "station-transfer", "line-b", 34_200, 0, 0),
				stop("second", 2, "station-b", "line-b", 34_800, 0, 0)),
			sameStationInStationStepFreeAccess());
	}

	private static LoadRouteTimetablePort.RouteAccessData sameStationInStationStepFreeAccess() {
		var nodes = List.of(
			new LoadRouteTimetablePort.PathwayNode("entry-outside", "station-a", null, "ENTRANCE"),
			new LoadRouteTimetablePort.PathwayNode("entry-platform", "station-a", "line-a", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("exit-platform", "station-b", "line-b", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("exit-outside", "station-b", null, "EXIT"),
			new LoadRouteTimetablePort.PathwayNode("transfer-1", "station-transfer", "line-a", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("transfer-2", "station-transfer", "line-b", "PLATFORM"));
		var entry = edge("entry", "entry-outside", "entry-platform", 300, "VERIFIED");
		var exit = edge("exit", "exit-platform", "exit-outside", 180, "VERIFIED");
		var inTransfer = edge("in-transfer-edge", "transfer-1", "transfer-2", 240, false, "VERIFIED");
		var outTransfer = edge("out-transfer-edge", "transfer-1", "transfer-2", 360, false, "VERIFIED");
		var evidence = List.of(
			evidence("entry-e", "station-a", "line-a", "entry", "ENTRY"),
			evidence("exit-e", "station-b", "line-b", "exit", "EXIT"),
			evidence("in-transfer-e", "station-transfer", "line-b", "in-transfer-edge", "TRANSFER"),
			evidence("out-transfer-e", "station-transfer", "line-b", "out-transfer-edge", "TRANSFER"));
		var rules = List.of(
			new LoadRouteTimetablePort.TransferRule(
				"in-transfer-rule", "station-transfer", "line-a", "station-transfer", "line-b",
				"IN_STATION", 240, "in-transfer-edge", null, "VERIFIED"),
			new LoadRouteTimetablePort.TransferRule(
				"out-transfer-rule", "station-transfer", "line-a", "station-transfer", "line-b",
				"OUT_OF_STATION", 360, "out-transfer-edge", null, "VERIFIED"));
		return new LoadRouteTimetablePort.RouteAccessData(nodes, List.of(entry, exit, inTransfer, outTransfer), rules, evidence);
	}

	private static RouteTimetable sameStationBothStairsTimetable() {
		return timetable(
			List.of(trip("first", "route-a"), trip("second", "route-b")),
			List.of(stop("first", 1, "station-a", "line-a", 32_400, 0, 0),
				stop("first", 2, "station-transfer", "line-a", 33_000, 0, 0),
				stop("second", 1, "station-transfer", "line-b", 34_200, 0, 0),
				stop("second", 2, "station-b", "line-b", 34_800, 0, 0)),
			sameStationBothStairsAccess());
	}

	private static LoadRouteTimetablePort.RouteAccessData sameStationBothStairsAccess() {
		var nodes = List.of(
			new LoadRouteTimetablePort.PathwayNode("entry-outside", "station-a", null, "ENTRANCE"),
			new LoadRouteTimetablePort.PathwayNode("entry-platform", "station-a", "line-a", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("exit-platform", "station-b", "line-b", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("exit-outside", "station-b", null, "EXIT"),
			new LoadRouteTimetablePort.PathwayNode("transfer-1", "station-transfer", "line-a", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("transfer-2", "station-transfer", "line-b", "PLATFORM"));
		var entry = edge("entry", "entry-outside", "entry-platform", 300, "VERIFIED");
		var exit = edge("exit", "exit-platform", "exit-outside", 180, "VERIFIED");
		var inTransfer = edge("in-transfer-edge", "transfer-1", "transfer-2", 240, true, "VERIFIED");
		var outTransfer = edge("out-transfer-edge", "transfer-1", "transfer-2", 360, true, "VERIFIED");
		var evidence = List.of(
			evidence("entry-e", "station-a", "line-a", "entry", "ENTRY"),
			evidence("exit-e", "station-b", "line-b", "exit", "EXIT"),
			evidence("in-transfer-e", "station-transfer", "line-b", "in-transfer-edge", "TRANSFER"),
			evidence("out-transfer-e", "station-transfer", "line-b", "out-transfer-edge", "TRANSFER"));
		var rules = List.of(
			new LoadRouteTimetablePort.TransferRule(
				"in-transfer-rule", "station-transfer", "line-a", "station-transfer", "line-b",
				"IN_STATION", 240, "in-transfer-edge", null, "VERIFIED"),
			new LoadRouteTimetablePort.TransferRule(
				"out-transfer-rule", "station-transfer", "line-a", "station-transfer", "line-b",
				"OUT_OF_STATION", 360, "out-transfer-edge", null, "VERIFIED"));
		return new LoadRouteTimetablePort.RouteAccessData(nodes, List.of(entry, exit, inTransfer, outTransfer), rules, evidence);
	}

	private static RouteTimetable sameStationStairsFootpathOnlyTimetable() {
		return timetable(
			List.of(trip("first", "route-a"), trip("second", "route-b")),
			List.of(stop("first", 1, "station-a", "line-a", 32_400, 0, 0),
				stop("first", 2, "station-transfer", "line-a", 33_000, 0, 0),
				stop("second", 1, "station-transfer", "line-b", 34_200, 0, 0),
				stop("second", 2, "station-b", "line-b", 34_800, 0, 0)),
			sameStationStairsFootpathOnlyAccess());
	}

	private static LoadRouteTimetablePort.RouteAccessData sameStationStairsFootpathOnlyAccess() {
		var nodes = List.of(
			new LoadRouteTimetablePort.PathwayNode("entry-outside", "station-a", null, "ENTRANCE"),
			new LoadRouteTimetablePort.PathwayNode("entry-platform", "station-a", "line-a", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("exit-platform", "station-b", "line-b", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("exit-outside", "station-b", null, "EXIT"),
			new LoadRouteTimetablePort.PathwayNode("transfer-1", "station-transfer", "line-a", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("transfer-2", "station-transfer", "line-b", "PLATFORM"));
		var entry = edge("entry", "entry-outside", "entry-platform", 300, "VERIFIED");
		var exit = edge("exit", "exit-platform", "exit-outside", 180, "VERIFIED");
		var stairsTransfer = edge("out-stairs-edge", "transfer-1", "transfer-2", 240, true, "VERIFIED");
		var evidence = List.of(
			evidence("entry-e", "station-a", "line-a", "entry", "ENTRY"),
			evidence("exit-e", "station-b", "line-b", "exit", "EXIT"),
			evidence("stairs-e", "station-transfer", "line-b", "out-stairs-edge", "TRANSFER"));
		var rules = List.of(
			new LoadRouteTimetablePort.TransferRule(
				"out-stairs-rule", "station-transfer", "line-a", "station-transfer", "line-b",
				"OUT_OF_STATION", 240, "out-stairs-edge", null, "VERIFIED"));
		return new LoadRouteTimetablePort.RouteAccessData(nodes, List.of(entry, exit, stairsTransfer), rules, evidence);
	}

	@Test
	@DisplayName("도착역 승강기 고장 시 대체 경로가 없으면 fail-closed(NO_VERIFIED_EXIT)로 거절한다")
	void wheelchairArriveBy_whenDestinationExitElevatorBlockedAndNoAlternative_failsClosedWithNoVerifiedExit() {
		var compiled = forward.compile(directTimetable(32_400, 33_000, true, true, 300, 180));
		var overlay = forward.compileRealtimeOverlay(compiled, updatesWithBlockedEdges(List.of("exit")));

		var query = wheelchairQuery("station-a", "station-b", deadlineAt(33_000, 180));
		var result = planner.arriveBy(query, compiled, compiled.activeServiceDay(SERVICE_DATE), overlay, limits());

		assertThat(result.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.NO_VERIFIED_EXIT);
		assertThat(result.itineraries()).isEmpty();
	}

	@Test
	@DisplayName("도착역 주 승강기 고장 시 대체 무계단 출구가 존재하면 대체 출구로 정상 우회한다")
	void wheelchairArriveBy_whenDestinationExitElevatorBlockedAndAlternativeAvailable_detoursToAlternativeExit() {
		var compiled = forward.compile(directTimetableWithAlternativeExit());
		var overlay = forward.compileRealtimeOverlay(compiled, updatesWithBlockedEdges(List.of("exit-primary")));

		var query = wheelchairQuery("station-a", "station-b", deadlineAt(33_000, 300));
		var result = planner.arriveBy(query, compiled, compiled.activeServiceDay(SERVICE_DATE), overlay, limits());

		assertThat(result.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
		assertThat(result.itineraries()).singleElement().satisfies(itinerary -> {
			var exitLeg = itinerary.legs().stream()
				.filter(RouteTimetableRaptorPlanner.JourneyAccessProjection.class::isInstance)
				.map(RouteTimetableRaptorPlanner.JourneyAccessProjection.class::cast)
				.filter(leg -> leg.kind() == RouteTimetableRaptorPlanner.JourneyAccessKind.EXIT)
				.findFirst().orElseThrow();
			// baseline 180s (243s with SLOW preset) was blocked; detours to alternative 300s (405s with SLOW preset)
			assertThat(exitLeg.durationSeconds()).isEqualTo(405);
		});
	}

	@Test
	@DisplayName("출발역 승강기 고장 시 대체 경로가 없으면 fail-closed(NO_OD_CONNECTION)로 거절한다")
	void wheelchairArriveBy_whenOriginEntryElevatorBlockedAndNoAlternative_failsClosedWithNoOdConnection() {
		var compiled = forward.compile(directTimetable(32_400, 33_000, true, true, 300, 180));
		var overlay = forward.compileRealtimeOverlay(compiled, updatesWithBlockedEdges(List.of("entry")));

		var query = wheelchairQuery("station-a", "station-b", deadlineAt(33_000, 180));
		var result = planner.arriveBy(query, compiled, compiled.activeServiceDay(SERVICE_DATE), overlay, limits());

		assertThat(result.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.NO_OD_CONNECTION);
		assertThat(result.itineraries()).isEmpty();
	}

	@Test
	@DisplayName("출발역 주 승강기 고장 시 대체 무계단 입구가 존재하면 대체 입구로 정상 우회한다")
	void wheelchairArriveBy_whenOriginEntryElevatorBlockedAndAlternativeAvailable_detoursToAlternativeEntry() {
		var compiled = forward.compile(directTimetableWithAlternativeEntry());
		var overlay = forward.compileRealtimeOverlay(compiled, updatesWithBlockedEdges(List.of("entry-primary")));

		var query = wheelchairQuery("station-a", "station-b", deadlineAt(33_000, 180));
		var result = planner.arriveBy(query, compiled, compiled.activeServiceDay(SERVICE_DATE), overlay, limits());

		assertThat(result.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
		assertThat(result.itineraries()).singleElement().satisfies(itinerary -> {
			var entryLeg = itinerary.legs().stream()
				.filter(RouteTimetableRaptorPlanner.JourneyAccessProjection.class::isInstance)
				.map(RouteTimetableRaptorPlanner.JourneyAccessProjection.class::cast)
				.filter(leg -> leg.kind() == RouteTimetableRaptorPlanner.JourneyAccessKind.ENTRY)
				.findFirst().orElseThrow();
			// baseline 100s (135s with SLOW preset) was blocked; detours to alternative 250s (338s with SLOW preset)
			assertThat(entryLeg.durationSeconds()).isEqualTo(338);
		});
	}

	@Test
	@DisplayName("환승역 주 승강기 고장 시 대체 무계단 환승이 존재하면 대체 환승으로 정상 우회한다")
	void wheelchairArriveBy_whenTransferElevatorBlockedAndAlternativeAvailable_detoursToAlternativeTransfer() {
		var compiled = forward.compile(transferTimetableWithAlternativeTransfer());
		var overlay = forward.compileRealtimeOverlay(compiled, updatesWithBlockedEdges(List.of("transfer-primary")));

		var query = wheelchairQuery("station-a", "station-b", deadlineAt(34_800, 180));
		var result = planner.arriveBy(query, compiled, compiled.activeServiceDay(SERVICE_DATE), overlay, limits());

		assertThat(result.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
		assertThat(result.itineraries()).singleElement().satisfies(itinerary -> {
			var transferLeg = itinerary.legs().stream()
				.filter(RouteTimetableRaptorPlanner.JourneyAccessProjection.class::isInstance)
				.map(RouteTimetableRaptorPlanner.JourneyAccessProjection.class::cast)
				.filter(leg -> leg.kind() == RouteTimetableRaptorPlanner.JourneyAccessKind.TRANSFER)
				.findFirst().orElseThrow();
			// baseline 240s (324s with SLOW preset) was blocked; detours to alternative 400s (540s with SLOW preset)
			assertThat(transferLeg.durationSeconds()).isEqualTo(540);
		});
	}

	@Test
	@DisplayName("환승역 승강기 고장 시 대체 환승 경로가 없으면 fail-closed(NO_OD_CONNECTION)로 거절한다")
	void wheelchairArriveBy_whenTransferElevatorBlockedAndNoAlternative_failsClosedWithNoOdConnection() {
		var compiled = forward.compile(transferTimetable());
		var overlay = forward.compileRealtimeOverlay(compiled, updatesWithBlockedEdges(List.of("transfer")));

		var query = wheelchairQuery("station-a", "station-b", deadlineAt(34_800, 180));
		var result = planner.arriveBy(query, compiled, compiled.activeServiceDay(SERVICE_DATE), overlay, limits());

		assertThat(result.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.NO_OD_CONNECTION);
		assertThat(result.itineraries()).isEmpty();
	}

	@Test
	@DisplayName("노외 환승(OutOfStationFootpath) 엣지 장애 시 fail-closed(NO_OD_CONNECTION)로 거절한다")
	void wheelchairArriveBy_whenOutOfStationFootpathBlocked_failsClosedWithNoOdConnection() {
		var compiled = forward.compile(outOfStationTransferTimetable());
		var overlay = forward.compileRealtimeOverlay(compiled, updatesWithBlockedEdges(List.of("transfer-edge")));

		var query = wheelchairQuery("station-a", "station-b", deadlineAt(34_800, 180));
		var result = planner.arriveBy(query, compiled, compiled.activeServiceDay(SERVICE_DATE), overlay, limits());

		assertThat(result.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.NO_OD_CONNECTION);
		assertThat(result.itineraries()).isEmpty();
	}

	@Test
	@DisplayName("막차(lastConnection) 조회 시 도착역 승강기 고장을 감지하여 fail-closed로 거절한다")
	void wheelchairLastConnection_whenDestinationExitElevatorBlocked_failsClosedWithNoVerifiedExit() {
		var compiled = forward.compile(directTimetable(32_400, 33_000, true, true, 300, 180));
		var overlay = forward.compileRealtimeOverlay(compiled, updatesWithBlockedEdges(List.of("exit")));

		var query = new ReverseTimetableRaptorPlanner.LastConnectionQuery(
			"station-a", "station-b", SERVICE_DATE, 1, WHEELCHAIR_PROFILE_BIT, WHEELCHAIR_SLACK_SECONDS,
			MobilityPreset.SLOW, 3_600, false, () -> false);
		var result = planner.lastConnection(query, compiled, compiled.activeServiceDay(SERVICE_DATE), overlay, limits());

		assertThat(result.result().outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.NO_VERIFIED_EXIT);
		assertThat(result.terminalArrivalAtDestinationSeconds()).isNull();
	}

	@Test
	@DisplayName("자정 경계(Cross-Date) 환승 시 이전일 또는 익일 중 한 곳이라도 환승 승강기가 차단되면 fail-closed로 거절한다")
	void crossDateTransfer_whenElevatorBlockedOnEitherServiceDate_failsClosed() {
		var compiled = forward.compile(crossCutoffTransferTimetable(false));
		var blockedOverlay = forward.compileRealtimeOverlay(compiled, updatesWithBlockedEdges(List.of("transfer")));
		var cleanOverlay = RouteTimetableRaptorPlanner.RealtimeOverlay.empty();

		// 이전일에만 환승 승강기 차단
		var resultBlockedPredecessor = planner.arriveBy(crossDateQuery(96_000, deadlineAt(99_600, 180)), compiled,
			SERVICE_DATE, SERVICE_DATE.plusDays(1),
			date -> date.equals(SERVICE_DATE) ? blockedOverlay : cleanOverlay, limits(), null);
		assertThat(resultBlockedPredecessor.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.NO_OD_CONNECTION);

		// 익일에만 환승 승강기 차단
		var resultBlockedNextDay = planner.arriveBy(crossDateQuery(96_000, deadlineAt(99_600, 180)), compiled,
			SERVICE_DATE, SERVICE_DATE.plusDays(1),
			date -> date.equals(SERVICE_DATE.plusDays(1)) ? blockedOverlay : cleanOverlay, limits(), null);
		assertThat(resultBlockedNextDay.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.NO_OD_CONNECTION);

		// 양일 모두 정상
		var resultClean = planner.arriveBy(crossDateQuery(96_000, deadlineAt(99_600, 180)), compiled,
			SERVICE_DATE, SERVICE_DATE.plusDays(1),
			date -> cleanOverlay, limits(), null);
		assertThat(resultClean.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
	}

	@Test
	@DisplayName("RealtimeOverlay.combine 계약(null, empty, self, 병합 무결성)을 철저히 검증한다")
	void realtimeOverlayCombineContracts() {
		var compiled = forward.compile(directTimetable(32_400, 33_000, true, true, 300, 180));
		var empty = RouteTimetableRaptorPlanner.RealtimeOverlay.empty();
		assertThat(RouteTimetableRaptorPlanner.RealtimeOverlay.combine(null, null)).isSameAs(empty);
		assertThat(RouteTimetableRaptorPlanner.RealtimeOverlay.combine(empty, null)).isSameAs(empty);
		assertThat(RouteTimetableRaptorPlanner.RealtimeOverlay.combine(null, empty)).isSameAs(empty);

		var overlayEntry = forward.compileRealtimeOverlay(compiled, updatesWithBlockedEdges(List.of("entry")));
		var overlayExit = forward.compileRealtimeOverlay(compiled, updatesWithBlockedEdges(List.of("exit")));

		assertThat(RouteTimetableRaptorPlanner.RealtimeOverlay.combine(overlayEntry, null)).isSameAs(overlayEntry);
		assertThat(RouteTimetableRaptorPlanner.RealtimeOverlay.combine(null, overlayEntry)).isSameAs(overlayEntry);
		assertThat(RouteTimetableRaptorPlanner.RealtimeOverlay.combine(overlayEntry, empty)).isSameAs(overlayEntry);
		assertThat(RouteTimetableRaptorPlanner.RealtimeOverlay.combine(empty, overlayEntry)).isSameAs(overlayEntry);
		assertThat(RouteTimetableRaptorPlanner.RealtimeOverlay.combine(overlayEntry, overlayEntry)).isSameAs(overlayEntry);

		var combined = RouteTimetableRaptorPlanner.RealtimeOverlay.combine(overlayEntry, overlayExit);
		int[] entryTrans = compiled.transitionIdsForEdge("entry");
		int[] exitTrans = compiled.transitionIdsForEdge("exit");
		for (int t : entryTrans) {
			assertThat(combined.isTransitionBlocked(t)).isTrue();
		}
		for (int t : exitTrans) {
			assertThat(combined.isTransitionBlocked(t)).isTrue();
		}
	}

	@Test
	@DisplayName("승강기 고장이라도 계단 통로가 이용 가능한 일반 승객은 정상 탐색된다(과도한 차단 방지)")
	void standardUserArriveBy_whenElevatorBlocked_stillAllowedViaStairs() {
		var compiled = forward.compile(timetableWithStairsAndElevator());
		var overlay = forward.compileRealtimeOverlay(compiled, updatesWithBlockedEdges(List.of("entry-elevator", "exit-elevator")));

		// 계단 허용 일반 프로필 (PROFILE_BIT)
		var query = query("station-a", "station-b", deadlineAt(33_000, 180));
		var result = planner.arriveBy(query, compiled, compiled.activeServiceDay(SERVICE_DATE), overlay, limits());

		assertThat(result.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
	}

	private static RouteTimetable directTimetableWithAlternativeExit() {
		var nodes = List.of(
			new LoadRouteTimetablePort.PathwayNode("entry-outside", "station-a", null, "ENTRANCE"),
			new LoadRouteTimetablePort.PathwayNode("entry-platform", "station-a", "line-a", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("exit-platform", "station-b", "line-a", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("exit-outside-primary", "station-b", null, "EXIT"),
			new LoadRouteTimetablePort.PathwayNode("exit-outside-alt", "station-b", null, "EXIT"));
		var entry = edge("entry", "entry-outside", "entry-platform", 300, "VERIFIED");
		var exitPrimary = edge("exit-primary", "exit-platform", "exit-outside-primary", 180, "VERIFIED");
		var exitAlt = edge("exit-alt", "exit-platform", "exit-outside-alt", 300, "VERIFIED");
		var evidence = List.of(
			evidence("entry-e", "station-a", "line-a", "entry", "ENTRY"),
			evidence("exit-p-e", "station-b", "line-a", "exit-primary", "EXIT"),
			evidence("exit-a-e", "station-b", "line-a", "exit-alt", "EXIT"));
		return timetable(
			List.of(trip("direct", "route-direct")),
			List.of(stop("direct", 1, "station-a", "line-a", 32_400, 0, 0),
				stop("direct", 2, "station-b", "line-a", 33_000, 0, 0)),
			new LoadRouteTimetablePort.RouteAccessData(nodes, List.of(entry, exitPrimary, exitAlt), List.of(), evidence));
	}

	private static RouteTimetable directTimetableWithAlternativeEntry() {
		var nodes = List.of(
			new LoadRouteTimetablePort.PathwayNode("entry-outside-primary", "station-a", null, "ENTRANCE"),
			new LoadRouteTimetablePort.PathwayNode("entry-outside-alt", "station-a", null, "ENTRANCE"),
			new LoadRouteTimetablePort.PathwayNode("entry-platform", "station-a", "line-a", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("exit-platform", "station-b", "line-a", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("exit-outside", "station-b", null, "EXIT"));
		var entryPrimary = edge("entry-primary", "entry-outside-primary", "entry-platform", 100, "VERIFIED");
		var entryAlt = edge("entry-alt", "entry-outside-alt", "entry-platform", 250, "VERIFIED");
		var exit = edge("exit", "exit-platform", "exit-outside", 180, "VERIFIED");
		var evidence = List.of(
			evidence("entry-p-e", "station-a", "line-a", "entry-primary", "ENTRY"),
			evidence("entry-a-e", "station-a", "line-a", "entry-alt", "ENTRY"),
			evidence("exit-e", "station-b", "line-a", "exit", "EXIT"));
		return timetable(
			List.of(trip("direct", "route-direct")),
			List.of(stop("direct", 1, "station-a", "line-a", 32_400, 0, 0),
				stop("direct", 2, "station-b", "line-a", 33_000, 0, 0)),
			new LoadRouteTimetablePort.RouteAccessData(nodes, List.of(entryPrimary, entryAlt, exit), List.of(), evidence));
	}

	private static RouteTimetable transferTimetableWithAlternativeTransfer() {
		var nodes = List.of(
			new LoadRouteTimetablePort.PathwayNode("entry-outside", "station-a", null, "ENTRANCE"),
			new LoadRouteTimetablePort.PathwayNode("entry-platform", "station-a", "line-a", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("exit-platform", "station-b", "line-b", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("exit-outside", "station-b", null, "EXIT"),
			new LoadRouteTimetablePort.PathwayNode("transfer-1", "station-transfer", "line-a", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("transfer-2", "station-transfer", "line-b", "PLATFORM"));
		var entry = edge("entry", "entry-outside", "entry-platform", 300, "VERIFIED");
		var exit = edge("exit", "exit-platform", "exit-outside", 180, "VERIFIED");
		var transferPrimary = edge("transfer-primary", "transfer-1", "transfer-2", 240, "VERIFIED");
		var transferAlt = edge("transfer-alt", "transfer-1", "transfer-2", 400, "VERIFIED");
		var evidence = List.of(
			evidence("entry-e", "station-a", "line-a", "entry", "ENTRY"),
			evidence("exit-e", "station-b", "line-b", "exit", "EXIT"),
			evidence("transfer-p-e", "station-transfer", "line-b", "transfer-primary", "TRANSFER"),
			evidence("transfer-a-e", "station-transfer", "line-b", "transfer-alt", "TRANSFER"));
		var rules = List.of(
			new LoadRouteTimetablePort.TransferRule(
				"transfer-rule-1", "station-transfer", "line-a", "station-transfer", "line-b", "IN_STATION", 240,
				"transfer-primary", null, "VERIFIED"),
			new LoadRouteTimetablePort.TransferRule(
				"transfer-rule-2", "station-transfer", "line-a", "station-transfer", "line-b", "IN_STATION", 400,
				"transfer-alt", null, "VERIFIED"));
		return timetable(
			List.of(trip("first", "route-a"), trip("second", "route-b")),
			List.of(stop("first", 1, "station-a", "line-a", 32_400, 0, 0),
				stop("first", 2, "station-transfer", "line-a", 33_000, 0, 0),
				stop("second", 1, "station-transfer", "line-b", 34_200, 0, 0),
				stop("second", 2, "station-b", "line-b", 34_800, 0, 0)),
			new LoadRouteTimetablePort.RouteAccessData(nodes, List.of(entry, exit, transferPrimary, transferAlt), rules, evidence));
	}

	private static RouteTimetable timetableWithStairsAndElevator() {
		var nodes = List.of(
			new LoadRouteTimetablePort.PathwayNode("entry-outside", "station-a", null, "ENTRANCE"),
			new LoadRouteTimetablePort.PathwayNode("entry-platform", "station-a", "line-a", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("exit-platform", "station-b", "line-a", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("exit-outside", "station-b", null, "EXIT"));
		var entryStairs = edge("entry-stairs", "entry-outside", "entry-platform", 300, true, "VERIFIED");
		var entryElevator = edge("entry-elevator", "entry-outside", "entry-platform", 150, false, "VERIFIED");
		var exitStairs = edge("exit-stairs", "exit-platform", "exit-outside", 180, true, "VERIFIED");
		var exitElevator = edge("exit-elevator", "exit-platform", "exit-outside", 120, false, "VERIFIED");
		var evidence = List.of(
			evidence("entry-s-e", "station-a", "line-a", "entry-stairs", "ENTRY"),
			evidence("entry-e-e", "station-a", "line-a", "entry-elevator", "ENTRY"),
			evidence("exit-s-e", "station-b", "line-a", "exit-stairs", "EXIT"),
			evidence("exit-e-e", "station-b", "line-a", "exit-elevator", "EXIT"));
		return timetable(
			List.of(trip("direct", "route-direct")),
			List.of(stop("direct", 1, "station-a", "line-a", 32_400, 0, 0),
				stop("direct", 2, "station-b", "line-a", 33_000, 0, 0)),
			new LoadRouteTimetablePort.RouteAccessData(nodes, List.of(entryStairs, entryElevator, exitStairs, exitElevator), List.of(), evidence));
	}
}
