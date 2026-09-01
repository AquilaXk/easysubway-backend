package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.journey.application.JourneyProfileResourcePolicy;
import com.easysubway.journey.application.JourneyRaptorPruningInventoryV1;
import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.application.JourneyRequestMeasurement;
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
	private static final String ORACLE_ROUTE_BUNDLE_SHA = "a".repeat(64);
	private static final long ORACLE_GENERATION = 1L;
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
	@DisplayName("matches an independent forward-point oracle for arrive-by directional access and tradeoffs")
	void arriveByMatchesIndependentForwardPointOracle() {
		var compiled = forward.compile(sharedUpstreamSuffixTimetable());
		int deadline = deadlineAt(34_800, 180);

		var result = arriveBy(compiled, "station-a", "station-b", deadline,
			RouteTimetableRaptorPlanner.RealtimeOverlay.empty());
		var oracle = forwardPointOracle(compiled, "station-a", "station-b", 0, deadline, 1,
			RouteTimetableRaptorPlanner.RealtimeOverlay.empty());

		assertThat(oracle).extracting(this::tripIds).containsExactlyInAnyOrder(
			List.of("shared-first", "fast-second"),
			List.of("shared-first", "safer-second"));
		assertThat(oracle).allSatisfy(this::assertVerifiedDirectionalJourney);
		assertThat(result.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
		assertThat(result.itineraries()).containsExactlyInAnyOrderElementsOf(oracle);
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
	@DisplayName("matches forward exhaustive feasible-departure selection for the O/D last connection")
	void lastConnectionMatchesIndependentForwardPointOracle() {
		var compiled = forward.compile(lastConnectionTransferTimetable());
		var result = lastConnection(compiled, MobilityPreset.SLOW, RouteTimetableRaptorPlanner.RealtimeOverlay.empty());
		var oracle = forwardLastFeasibleDepartureOracle(compiled, "station-a", "station-b", 1,
			RouteTimetableRaptorPlanner.RealtimeOverlay.empty());

		assertThat(oracle).extracting(this::tripIds)
			.containsExactly(List.of("feasible-first", "feasible-second"));
		assertThat(oracle).allSatisfy(this::assertVerifiedDirectionalJourney);
		assertThat(result.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
		assertThat(result.latestReadyAtSeconds())
			.isEqualTo(serviceSeconds(oracle.getFirst().plannedDepartureTime()));
		assertThat(result.itineraries()).containsExactlyInAnyOrderElementsOf(oracle);
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

		var normal = lastConnection(compiled, MobilityPreset.NORMAL, RouteTimetableRaptorPlanner.RealtimeOverlay.empty());
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

	/**
	 * Test-only fixture-exact oracle: enumerate actual origin departure events, calculate each
	 * event's verified ENTRY-ready instant, and union the forward point planner outcomes. These
	 * fixtures have at most two feasible candidates, below the point contract's public cap of three.
	 * The oracle has no dependency on reverse trace, dominance, frontier, or terminal-horizon code.
	 */
	private List<RouteTimetableRaptorPlanner.JourneyItinerary> forwardPointOracle(
		RouteTimetableRaptorPlanner.CompiledTimetable compiled,
		String origin,
		String destination,
		int earliestReadyAtSeconds,
		int arrivalDeadlineSeconds,
		int maxTransfers,
		RouteTimetableRaptorPlanner.RealtimeOverlay overlay
	) {
		var activeServiceDay = compiled.activeServiceDay(SERVICE_DATE);
		int originIndex = compiled.stationIndex(origin);
		return forward.departureEvents(
			activeServiceDay, origin, 0, LoadRouteTimetablePort.SERVICE_DAY_SECONDS_LIMIT_EXCLUSIVE - 1, overlay)
			.stream()
			.map(event -> forwardPointReadyAt(compiled, originIndex, event))
			.filter(ForwardPointEvent::hasVerifiedEntry)
			.filter(event -> event.readyAtSeconds() >= earliestReadyAtSeconds)
			.flatMap(event -> forward.journeyItineraries(
				pointQuery(origin, destination, event.readyAtSeconds(), maxTransfers), compiled, overlay,
				new JourneyRequestMeasurement(ORACLE_REQUEST_ID), ORACLE_REQUEST_ID,
				ORACLE_ROUTE_BUNDLE_SHA, ORACLE_GENERATION).itineraries().stream())
			.filter(itinerary -> serviceSeconds(itinerary.plannedDepartureTime()) >= earliestReadyAtSeconds)
			.filter(itinerary -> serviceSeconds(effectiveArrival(itinerary, overlay)) <= arrivalDeadlineSeconds)
			.distinct()
			.toList();
	}

	private List<RouteTimetableRaptorPlanner.JourneyItinerary> forwardLastFeasibleDepartureOracle(
		RouteTimetableRaptorPlanner.CompiledTimetable compiled,
		String origin,
		String destination,
		int maxTransfers,
		RouteTimetableRaptorPlanner.RealtimeOverlay overlay
	) {
		var feasible = forwardPointOracle(compiled, origin, destination, 0, Integer.MAX_VALUE, maxTransfers, overlay);
		int latestReadyAt = feasible.stream()
			.mapToInt(itinerary -> serviceSeconds(itinerary.plannedDepartureTime()))
			.max().orElseThrow();
		return feasible.stream()
			.filter(itinerary -> serviceSeconds(itinerary.plannedDepartureTime()) == latestReadyAt)
			.toList();
	}

	private static ForwardPointEvent forwardPointReadyAt(
		RouteTimetableRaptorPlanner.CompiledTimetable compiled,
		int originIndex,
		RouteTimetableRaptorPlanner.DepartureEvent event
	) {
		int lineIndex = compiled.lineIndex(event.scheduledTrip().lineId(event.stopIndex()));
		int entryTransition = lineIndex < 0 ? -1
			: compiled.entryTransition(originIndex, lineIndex, PROFILE_BIT, false, false);
		if (entryTransition < 0 || !compiled.transitionVerified(entryTransition)) {
			return ForwardPointEvent.noVerifiedEntry();
		}
		int entrySeconds = ProfileWalkTimeCalculator.estimateSeconds(
			compiled.transitionDurationSeconds(entryTransition), MobilityPreset.SLOW,
			WalkTimeSource.OFFICIAL_BASELINE, false).seconds();
		return new ForwardPointEvent(event.effectiveDepartureSeconds() - entrySeconds - SLACK_SECONDS, true);
	}

	private static JourneyRaptorQuery pointQuery(String origin, String destination, int readyAtSeconds, int maxTransfers) {
		return new JourneyRaptorQuery(
			ORACLE_REQUEST_ID, origin, destination, new JourneyRaptorQuery.DepartAt(serviceInstant(readyAtSeconds)),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.SLOW,
			JourneyRequest.MobilityProfile.SLOW, JourneyRequest.ConstraintMode.NONE,
			maxTransfers, 3, () -> false);
	}

	private static Instant effectiveArrival(
		RouteTimetableRaptorPlanner.JourneyItinerary itinerary,
		RouteTimetableRaptorPlanner.RealtimeOverlay overlay
	) {
		return overlay.available() ? itinerary.realtimeArrivalTime() : itinerary.plannedArrivalTime();
	}

	private static Instant serviceInstant(int serviceSeconds) {
		return SERVICE_DATE.atStartOfDay(ServiceDayResolver.ZONE).plusSeconds(serviceSeconds).toInstant();
	}

	private static int serviceSeconds(Instant instant) {
		return Math.toIntExact(Duration.between(
			SERVICE_DATE.atStartOfDay(ServiceDayResolver.ZONE).toInstant(), instant).toSeconds());
	}

	private List<String> tripIds(RouteTimetableRaptorPlanner.JourneyItinerary itinerary) {
		return itinerary.legs().stream()
			.filter(RouteTimetableRaptorPlanner.JourneyRideProjection.class::isInstance)
			.map(RouteTimetableRaptorPlanner.JourneyRideProjection.class::cast)
			.map(RouteTimetableRaptorPlanner.JourneyRideProjection::tripId)
			.toList();
	}

	private void assertVerifiedDirectionalJourney(RouteTimetableRaptorPlanner.JourneyItinerary itinerary) {
		assertThat(itinerary.legs()).filteredOn(RouteTimetableRaptorPlanner.JourneyAccessProjection.class::isInstance)
			.extracting(RouteTimetableRaptorPlanner.JourneyAccessProjection.class::cast)
			.allSatisfy(access -> assertThat(access.verified()).isTrue());
		assertThat(itinerary.legs()).filteredOn(RouteTimetableRaptorPlanner.JourneyAccessProjection.class::isInstance)
			.extracting(RouteTimetableRaptorPlanner.JourneyAccessProjection.class::cast)
			.extracting(RouteTimetableRaptorPlanner.JourneyAccessProjection::kind)
			.containsExactly(
				RouteTimetableRaptorPlanner.JourneyAccessKind.ENTRY,
				RouteTimetableRaptorPlanner.JourneyAccessKind.TRANSFER,
				RouteTimetableRaptorPlanner.JourneyAccessKind.EXIT);
	}

	private record ForwardPointEvent(int readyAtSeconds, boolean hasVerifiedEntry) {
		private static ForwardPointEvent noVerifiedEntry() {
			return new ForwardPointEvent(0, false);
		}
	}

	private static ReverseTimetableRaptorPlanner.Query query(String origin, String destination, int deadlineSeconds) {
		return new ReverseTimetableRaptorPlanner.Query(
			origin, destination, SERVICE_DATE, 0, deadlineSeconds, 1, PROFILE_BIT, SLACK_SECONDS,
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
