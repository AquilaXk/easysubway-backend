package com.easysubway.journey.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class JourneyProfileCandidateProjectionV1Test {

	private static final Instant START = Instant.parse("2026-09-02T08:00:00Z");
	private static final BooleanSupplier NOT_CANCELLED = () -> false;

	@Test
	void projectsStableReadinessQualifiedCandidatesAndResolvesEveryPublishedReference() {
		var query = query(START, START.plusSeconds(20), 3);
		var firstItinerary = itinerary(START, 600, "trip-a", 120, 150, 1);
		var secondItinerary = itinerary(START.plusSeconds(10), 600, "trip-a", 120, 150, 1);
		var result = JourneyProfileCandidateProjectionV1.projectDepartureWindow(query,
			plan((JourneyRaptorQuery.DepartBetween) query.temporalQuery(),
				point(START, firstItinerary), point(START.plusSeconds(10), secondItinerary)), 3);

		assertThat(result).isInstanceOf(JourneyProfileCandidateProjectionV1.Projected.class);
		var projection = ((JourneyProfileCandidateProjectionV1.Projected) result).projection();
		assertThat(projection.candidates()).hasSize(2);
		var first = projection.candidates().stream()
			.filter(candidate -> candidate.readyAt().equals(START)).findFirst().orElseThrow();
		var second = projection.candidates().stream()
			.filter(candidate -> candidate.readyAt().equals(START.plusSeconds(10))).findFirst().orElseThrow();
		String physicalId = JourneyProfileCandidateIdentity.physicalItineraryId(
			"origin", "destination", firstItinerary);
		assertThat(JourneyProfileCandidateIdentity.physicalItineraryId(
			"origin", "destination", secondItinerary)).isEqualTo(physicalId);
		assertThat(first.physicalItineraryId()).isEqualTo(physicalId);
		assertThat(second.physicalItineraryId()).isEqualTo(physicalId);
		assertThat(first.candidateId()).isEqualTo(JourneyProfileCandidateIdentity.candidateId(physicalId, START));
		assertThat(second.candidateId()).isEqualTo(JourneyProfileCandidateIdentity.candidateId(
			physicalId, START.plusSeconds(10)));
		assertThat(first.candidateId()).isNotEqualTo(second.candidateId());
		assertThat(first.readyAt()).isEqualTo(START);
		assertThat(first.journeyStartTime()).isEqualTo(first.readyAt());
		assertThat(first.firstBoardingTime()).isEqualTo(START.plusSeconds(60));
		assertThat(first.finalPlatformArrivalTime()).isEqualTo(START.plusSeconds(420));
		assertThat(first.arrivalAtDestination()).isEqualTo(START.plusSeconds(600));
		assertThat(first.objectiveTags()).isNotEmpty();

		var inventoryIds = projection.candidates().stream()
			.map(JourneyProfileCandidateProjectionV1.Candidate::candidateId).toList();
		assertThat(projection.segments()).allSatisfy(segment ->
			assertThat(inventoryIds).containsAll(segment.journeyIds()));
		assertThat(inventoryIds).contains(projection.summary().earliestArrivalJourneyId(),
			projection.summary().latestDepartureJourneyId());
		assertThat(inventoryIds).containsAll(projection.summary().recommendedJourneyIds());
	}

	@Test
	void retainsPartialNoServiceIntervalsInsteadOfDroppingTheRequestedRange() {
		var query = query(START, START.plusSeconds(20), 1);
		var result = JourneyProfileCandidateProjectionV1.projectDepartureWindow(query,
			plan((JourneyRaptorQuery.DepartBetween) query.temporalQuery(),
				point(START, itinerary(START, 600, "trip-a", 120, 150, 1)),
				point(START.plusSeconds(10))), 3);

		var projection = ((JourneyProfileCandidateProjectionV1.Projected) result).projection();
		assertThat(projection.segments()).hasSize(2);
		assertThat(projection.segments().getLast().journeyIds()).isEmpty();
		assertThat(projection.segments().getLast().readyUntilExclusive()).isEqualTo(START.plusSeconds(21));
	}

	@Test
	void returnsTypedNoServiceWhenTheEntireDepartureWindowIsEmpty() {
		var query = query(START, START.plusSeconds(20), 1);
		var result = JourneyProfileCandidateProjectionV1.projectDepartureWindow(query,
			plan((JourneyRaptorQuery.DepartBetween) query.temporalQuery(), point(START), point(START.plusSeconds(10))), 3);

		assertThat(result).isInstanceOf(JourneyProfileCandidateProjectionV1.NoService.class);
		assertThat(((JourneyProfileCandidateProjectionV1.NoService) result).segments()).singleElement()
			.satisfies(segment -> {
				assertThat(segment.readyFromInclusive()).isEqualTo(START);
				assertThat(segment.readyUntilExclusive()).isEqualTo(START.plusSeconds(21));
				assertThat(segment.journeyIds()).isEmpty();
			});
	}

	@Test
	void failsClosedWithoutProjectionWhenRequiredRepresentativesExceedTheCallerCapacity() {
		var query = query(START, START.plusSeconds(20), 1);
		var result = JourneyProfileCandidateProjectionV1.projectDepartureWindow(query,
			plan((JourneyRaptorQuery.DepartBetween) query.temporalQuery(), point(START,
				itinerary(START, 500, "trip-fast", 120, 500, 2),
				itinerary(START, 600, "trip-late", 40, 20, 0))), 1);

		assertThat(result).isInstanceOf(JourneyProfileCandidateProjectionV1.CapacityExceeded.class);
		var exceeded = (JourneyProfileCandidateProjectionV1.CapacityExceeded) result;
		assertThat(exceeded.observed()).isGreaterThan(exceeded.max());
		assertThat(exceeded.metrics().capacityState())
			.isEqualTo(JourneyFrontierPolicyV1.CapacityState.EXCEEDED);
	}

	@Test
	void rejectsMismatchedTemporalFactsAndConflictingDuplicateCandidateIds() {
		var query = query(START, START.plusSeconds(20), 1);
		assertThatIllegalArgumentException().isThrownBy(() ->
			JourneyProfileCandidateProjectionV1.projectDepartureWindow(query,
				plan(new JourneyRaptorQuery.DepartBetween(START.plusSeconds(1), START.plusSeconds(20)),
					point(START, itinerary(START, 600, "trip-a", 120, 150, 1))), 3));

		var first = itinerary(START, 600, "trip-a", 120, 150, 1);
		var conflicting = itinerary(START, 600, "trip-a", 121, 150, 1);
		assertThatIllegalArgumentException().isThrownBy(() ->
			JourneyProfileCandidateProjectionV1.projectDepartureWindow(query,
				plan((JourneyRaptorQuery.DepartBetween) query.temporalQuery(), point(START, first, conflicting)), 3))
			.hasMessageContaining("conflicting duplicate candidateId");
	}

	@Test
	void rejectsAReadinessQualifiedCandidateWhoseJourneyStartsAtAnotherInstant() {
		var query = query(START, START.plusSeconds(20), 1);

		assertThatIllegalArgumentException().isThrownBy(() ->
			JourneyProfileCandidateProjectionV1.projectDepartureWindow(query,
				plan((JourneyRaptorQuery.DepartBetween) query.temporalQuery(),
					point(START, itinerary(START.minusSeconds(1), 600, "trip-a", 120, 150, 1))), 3))
			.hasMessageContaining("readyAt must equal journeyStartTime");
	}

	@Test
	void rejectsAProfilePointWhoseServiceDayDoesNotOwnItsReadyInstantAndItinerary() {
		var query = query(START, START.plusSeconds(20), 1);
		var wrongDay = new JourneyProfileRaptorPort.DeparturePoint(LocalDate.of(2026, 9, 3), START,
			List.of(itinerary(START, 600, "trip-a", 120, 150, 1)), new JourneyRaptorPort.ScanMetrics(1, 1, 1));

		assertThatIllegalArgumentException().isThrownBy(() ->
			JourneyProfileCandidateProjectionV1.projectDepartureWindow(query,
				plan((JourneyRaptorQuery.DepartBetween) query.temporalQuery(), wrongDay), 3))
			.hasMessageContaining("service day");
	}

	@Test
	void projectsArriveByFromNativeReverseFactsWithoutSegments() {
		var temporal = new JourneyRaptorQuery.ArriveBy(START, START.plusSeconds(700));
		var query = query(temporal, 2);
		var nativeItinerary = itinerary(START.plusSeconds(30), 600, "trip-a", 120, 150, 1);

		var result = JourneyProfileCandidateProjectionV1.projectArriveBy(query,
			new JourneyProfileRaptorPort.ArriveByPlan(temporal,
				new JourneyProfileRaptorPort.ReversePlan.Found(List.of(nativeItinerary))), 3);

		assertThat(result).isInstanceOf(JourneyProfileCandidateProjectionV1.ArriveByProjected.class);
		var projection = ((JourneyProfileCandidateProjectionV1.ArriveByProjected) result).projection();
		assertThat(projection.temporalQuery()).isEqualTo(temporal);
		assertThat(projection.candidates()).singleElement().satisfies(candidate -> {
			assertThat(candidate.readyAt()).isEqualTo(nativeItinerary.plannedReadyAt());
			assertThat(candidate.journeyStartTime()).isEqualTo(nativeItinerary.plannedReadyAt());
		});
		var inventoryIds = projection.candidates().stream()
			.map(JourneyProfileCandidateProjectionV1.Candidate::candidateId).toList();
		assertThat(inventoryIds).contains(projection.summary().primaryJourneyId());
		assertThat(inventoryIds).containsAll(projection.summary().recommendedJourneyIds());
	}

	@Test
	void rejectsReverseNotFoundAndArrivalFactsOutsideTheRequestedDeadline() {
		var temporal = new JourneyRaptorQuery.ArriveBy(START, START.plusSeconds(600));
		var query = query(temporal, 1);

		assertThatIllegalArgumentException().isThrownBy(() ->
			JourneyProfileCandidateProjectionV1.projectArriveBy(query,
				new JourneyProfileRaptorPort.ArriveByPlan(temporal,
					new JourneyProfileRaptorPort.ReversePlan.NotFound(
						JourneyProfileRaptorPort.ReversePlan.Outcome.DEADLINE_MISS)), 3))
			.hasMessageContaining("ReversePlan.Found");
		assertThatIllegalArgumentException().isThrownBy(() ->
			JourneyProfileCandidateProjectionV1.projectArriveBy(query,
				new JourneyProfileRaptorPort.ArriveByPlan(temporal,
					new JourneyProfileRaptorPort.ReversePlan.Found(List.of(
						itinerary(START, 601, "trip-a", 120, 150, 1)))), 3))
			.hasMessageContaining("arrival deadline");
	}

	@Test
	void projectsLastConnectionAndFailsClosedWhenItsCandidateInventoryExceedsCapacity() {
		var temporal = new JourneyRaptorQuery.LastConnection(LocalDate.of(2026, 9, 2));
		var query = query(temporal, 2);
		var first = itinerary(START, 500, "trip-fast", 120, 500, 2);
		var second = itinerary(START.plusSeconds(10), 600, "trip-late", 40, 20, 0);
		var plan = new JourneyProfileRaptorPort.LastConnectionPlan(temporal,
			new JourneyProfileRaptorPort.ReversePlan.Found(List.of(first, second)), START.plusSeconds(600));

		var result = JourneyProfileCandidateProjectionV1.projectLastConnection(query, plan, 3);

		assertThat(result).isInstanceOf(JourneyProfileCandidateProjectionV1.LastConnectionProjected.class);
		var projection = ((JourneyProfileCandidateProjectionV1.LastConnectionProjected) result).projection();
		assertThat(projection.terminalArrivalAtDestination()).isEqualTo(START.plusSeconds(600));
		var inventoryIds = projection.candidates().stream()
			.map(JourneyProfileCandidateProjectionV1.Candidate::candidateId).toList();
		assertThat(inventoryIds).contains(projection.summary().lastConnectionJourneyId());
		assertThat(inventoryIds).containsAll(projection.summary().saferAlternativeJourneyIds());
		assertThat(inventoryIds).containsAll(projection.summary().recommendedJourneyIds());

		assertThat(JourneyProfileCandidateProjectionV1.projectLastConnection(query, plan, 1))
			.isInstanceOf(JourneyProfileCandidateProjectionV1.CapacityExceeded.class);
	}

	@Test
	void preservesCrossCutoffArrivalAndTheActualLastConnectionTerminal() {
		Instant beforeCutoff = Instant.parse("2026-09-01T17:50:00Z");
		Instant afterCutoff = Instant.parse("2026-09-01T18:20:00Z");
		var arriveBy = new JourneyRaptorQuery.ArriveBy(beforeCutoff, afterCutoff);
		var nextServiceDayItinerary = itinerary(LocalDate.of(2026, 9, 2),
			Instant.parse("2026-09-01T18:05:00Z"), Instant.parse("2026-09-01T18:06:00Z"),
			Instant.parse("2026-09-01T18:15:00Z"), Instant.parse("2026-09-01T18:16:00Z"), "trip-next");

		assertThat(JourneyProfileCandidateProjectionV1.projectArriveBy(query(arriveBy, 1),
			new JourneyProfileRaptorPort.ArriveByPlan(arriveBy,
				new JourneyProfileRaptorPort.ReversePlan.Found(List.of(nextServiceDayItinerary))), 3))
			.isInstanceOf(JourneyProfileCandidateProjectionV1.ArriveByProjected.class);

		var lastConnection = new JourneyRaptorQuery.LastConnection(LocalDate.of(2026, 9, 1));
		var serviceDayItinerary = itinerary(LocalDate.of(2026, 9, 1), beforeCutoff,
			Instant.parse("2026-09-01T17:55:00Z"), Instant.parse("2026-09-01T18:10:00Z"),
			Instant.parse("2026-09-01T18:15:00Z"), "trip-last");
		Instant terminal = Instant.parse("2026-09-01T18:30:00Z");

		assertThat(JourneyProfileCandidateProjectionV1.projectLastConnection(query(lastConnection, 1),
			new JourneyProfileRaptorPort.LastConnectionPlan(lastConnection,
				new JourneyProfileRaptorPort.ReversePlan.Found(List.of(serviceDayItinerary)), terminal), 3))
			.isInstanceOf(JourneyProfileCandidateProjectionV1.LastConnectionProjected.class);
	}

	private static JourneyRaptorQuery query(Instant earliest, Instant latest, int alternativeCount) {
		return query(new JourneyRaptorQuery.DepartBetween(earliest, latest), alternativeCount);
	}

	private static JourneyRaptorQuery query(
		JourneyRaptorQuery.TemporalQuery temporalQuery,
		int alternativeCount
	) {
		return new JourneyRaptorQuery("01K1Y000000000000000000000", "origin", "destination",
			temporalQuery,
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 3,
			alternativeCount, NOT_CANCELLED);
	}

	private static JourneyProfileRaptorPort.DepartureWindowPlan plan(
		JourneyRaptorQuery.DepartBetween temporalQuery,
		JourneyProfileRaptorPort.DeparturePoint... points
	) {
		return new JourneyProfileRaptorPort.DepartureWindowPlan(temporalQuery, List.of(points));
	}

	private static JourneyProfileRaptorPort.DeparturePoint point(
		Instant readyAt,
		JourneyProfileRaptorPort.Itinerary... itineraries
	) {
		return new JourneyProfileRaptorPort.DeparturePoint(LocalDate.of(2026, 9, 2), readyAt,
			List.of(itineraries), new JourneyRaptorPort.ScanMetrics(1, 1, 1));
	}

	private static JourneyProfileRaptorPort.Itinerary itinerary(
		Instant plannedReadyAt,
		long destinationArrivalOffset,
		String tripId,
		long walkingSeconds,
		long walkingDistanceMeters,
		long accessibilityBurden
	) {
		Instant rideDeparture = START.plusSeconds(60);
		Instant rideArrival = START.plusSeconds(420);
		return new JourneyProfileRaptorPort.Itinerary(LocalDate.of(2026, 9, 2), plannedReadyAt,
			START.plusSeconds(destinationArrivalOffset), null, null,
			new JourneyProfileRaptorPort.ItineraryMetrics(0, walkingSeconds, walkingDistanceMeters,
				accessibilityBurden, new JourneyProfileRaptorPort.NoTransfer()),
			List.of(
				new JourneyProfileRaptorPort.AccessLeg(JourneyProfileRaptorPort.AccessKind.ENTRY,
					"origin", "origin", 30, 20, accessibilityBurden > 0, true, "VERIFIED"),
				new JourneyProfileRaptorPort.RideLeg("line-a", tripId, "terminal", "origin", "destination",
					rideDeparture, rideArrival, null, null),
				new JourneyProfileRaptorPort.AccessLeg(JourneyProfileRaptorPort.AccessKind.EXIT,
					"destination", "destination", 30, 20, false, true, "VERIFIED")));
	}

	private static JourneyProfileRaptorPort.Itinerary itinerary(
		LocalDate serviceDate,
		Instant readyAt,
		Instant rideDeparture,
		Instant rideArrival,
		Instant destinationArrival,
		String tripId
	) {
		return new JourneyProfileRaptorPort.Itinerary(serviceDate, readyAt, destinationArrival, null, null,
			new JourneyProfileRaptorPort.ItineraryMetrics(0, 120, 150, 1,
				new JourneyProfileRaptorPort.NoTransfer()),
			List.of(
				new JourneyProfileRaptorPort.AccessLeg(JourneyProfileRaptorPort.AccessKind.ENTRY,
					"origin", "origin", 30, 20, false, true, "VERIFIED"),
				new JourneyProfileRaptorPort.RideLeg("line-a", tripId, "terminal", "origin", "destination",
					rideDeparture, rideArrival, null, null),
				new JourneyProfileRaptorPort.AccessLeg(JourneyProfileRaptorPort.AccessKind.EXIT,
					"destination", "destination", 30, 20, false, true, "VERIFIED")));
	}
}
