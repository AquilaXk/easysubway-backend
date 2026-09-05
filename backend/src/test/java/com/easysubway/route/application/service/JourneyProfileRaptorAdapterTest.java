package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.journey.application.ActiveJourneySnapshotPort;
import com.easysubway.journey.application.JourneyFrontierPolicyV1.ObjectiveTag;
import com.easysubway.journey.application.JourneyProfileCandidateProjectionV1;
import com.easysubway.journey.application.JourneyProfileRaptorPort;
import com.easysubway.journey.application.JourneyProfileResourcePolicy;
import com.easysubway.journey.application.JourneyRaptorPruningInventoryV1;
import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JourneyProfileRaptorAdapterTest {

	private static final String REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
	private static final String ROUTE_BUNDLE_SHA = "a".repeat(64);
	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 7, 1);
	private static final int STANDARD_BOARDING_SLACK_SECONDS = 60;
	private final JourneyProfileRaptorAdapter adapter = new JourneyProfileRaptorAdapter();

	@Test
	void measuresOnePointPlannerExecutionWithoutServingObservation() {
		var runtime = (RaptorRouteBundleRuntimeView) snapshot().runtimeView();
		var request = query(new JourneyRaptorQuery.DepartAt(instantAt(30_000)));
		var clockIndex = new java.util.concurrent.atomic.AtomicInteger();
		var allocationIndex = new java.util.concurrent.atomic.AtomicInteger();
		long[] clock = {100, 117};
		long[] allocations = {200, 232};

		var measurement = JourneyProfileMeasuredExecution.capturePoint(request, runtime,
			() -> clock[clockIndex.getAndIncrement()], () -> allocations[allocationIndex.getAndIncrement()]);

		assertThat(measurement.requestId()).isEqualTo(REQUEST_ID);
		assertThat(measurement.routeBundleSha256()).isEqualTo(runtime.routeBundleSha256());
		assertThat(measurement.generation()).isEqualTo(runtime.generation());
		assertThat(measurement.durationNanos()).isEqualTo(17);
		assertThat(measurement.allocatedBytes()).isEqualTo(32);
		assertThat(measurement.result().itineraries()).isNotEmpty();
		assertThat(measurement.result().scanMetrics().expandedRoutes()).isPositive();
		assertThat(measurement.result().measurementObservation()).isNull();
		var expected = new JourneyProfileExactOracle().solvePoint(new JourneyProfileExactOracle.Query(
			request.originStationId(), request.destinationStationId(), instantAt(30_000), instantAt(37_000),
			request.maxTransfers(), STANDARD_BOARDING_SLACK_SECONDS, 10_000, () -> false),
			JourneyProfileScheduledOracleInputs.rides(timetable(), SERVICE_DATE, 10),
			JourneyProfileOracleAccessInputs.normalize(accessData(), request.mobilityProfile(), request.constraintMode(),
				request.walkingPace().speedMetersPerHour(), 10));
		assertThat(expected).hasSize(1);
		assertThat(expected.getFirst().readyAt()).isEqualTo(instantAt(30_000));
		assertThat(expected.getFirst().arrivalAtDestination()).isEqualTo(instantAt(36_720));
		assertThat(JourneyProfileOracleComparison.matchesObservableTimetableFrontier(expected,
			measurement.result().itineraries().stream().map(JourneyProfileRaptorAdapter::itinerary).toList())).isTrue();
		assertThatThrownBy(() -> JourneyProfileMeasuredExecution.capturePoint(
			query(new JourneyRaptorQuery.ArriveBy(instantAt(30_000), instantAt(37_000))), runtime,
			() -> 0, () -> { throw new AssertionError("profile mode must be rejected before scan"); }))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DepartAt");
	}

	@Test
	void measurementCapturesOneCalculationAndRejectsUnavailableCounters() {
		var captured = snapshot();
		var runtime = (RaptorRouteBundleRuntimeView) captured.runtimeView();
		var request = query(new JourneyRaptorQuery.ArriveBy(instantAt(30_000), instantAt(37_000)));
		var calls = new java.util.concurrent.atomic.AtomicInteger();
		var clockIndex = new java.util.concurrent.atomic.AtomicInteger();
		var allocationIndex = new java.util.concurrent.atomic.AtomicInteger();
		long[] clock = {100, 117};
		long[] allocations = {200, 232};
		var measurement = JourneyProfileMeasuredExecution.capture(request, runtime, () -> {
			calls.incrementAndGet();
			return adapter.planRuntime(request, runtime, null, policy().profilePlanningLimits());
		}, () -> clock[clockIndex.getAndIncrement()], () -> allocations[allocationIndex.getAndIncrement()]);
		assertThat(calls.get()).isEqualTo(1);
		assertThat(measurement.requestId()).isEqualTo(REQUEST_ID);
		assertThat(measurement.routeBundleSha256()).isEqualTo(runtime.routeBundleSha256());
		assertThat(measurement.generation()).isEqualTo(runtime.generation());
		assertThat(measurement.durationNanos()).isEqualTo(17);
		assertThat(measurement.allocatedBytes()).isEqualTo(32);
		assertThat(measurement.result().planningMetrics().workConsumed()).isPositive();
		assertThatThrownBy(() -> JourneyProfileMeasuredExecution.capture(request, runtime,
			() -> { throw new AssertionError("calculation must not start without allocation observation"); },
			() -> 0, () -> -1))
			.isInstanceOf(JourneyProfileMeasuredExecution.Unobservable.class);
		allocationIndex.set(0);
		long[] decreasing = {200, 199};
		assertThatThrownBy(() -> JourneyProfileMeasuredExecution.capture(request, runtime,
			measurement::result, () -> 0, () -> decreasing[allocationIndex.getAndIncrement()]))
			.isInstanceOf(JourneyProfileMeasuredExecution.Unobservable.class);
	}

	@Test
	void measurementRuntimeAndServingEntryUseTheSameProfileCalculation() {
		var captured = snapshot();
		var runtime = (RaptorRouteBundleRuntimeView) captured.runtimeView();
		List<JourneyRaptorQuery.TemporalQuery> modes = List.of(
			new JourneyRaptorQuery.DepartBetween(instantAt(30_000), instantAt(37_000)),
			new JourneyRaptorQuery.ArriveBy(instantAt(30_000), instantAt(37_000)),
			new JourneyRaptorQuery.LastConnection(SERVICE_DATE));
		for (var mode : modes) {
			var request = query(mode);
			assertThat(adapter.planRuntime(request, runtime, null, policy().profilePlanningLimits()))
				.isEqualTo(adapter.plan(request, captured, null, policy().profilePlanningLimits()));
		}
	}

	@Test
	void matchesRawExactFrequencyOracleForArriveBy() {
		var source = timetable(List.of(new LoadRouteTimetablePort.TransitFrequency(
			"direct", 36_000, 36_600, 300, true)));
		var request = query(new JourneyRaptorQuery.ArriveBy(instantAt(30_000), instantAt(37_050)));
		var expected = new JourneyProfileExactOracle().solve(new JourneyProfileExactOracle.Query(
			request.originStationId(), request.destinationStationId(), instantAt(30_000), instantAt(37_050),
			0, STANDARD_BOARDING_SLACK_SECONDS, 10_000, () -> false),
			JourneyProfileScheduledOracleInputs.rides(source, SERVICE_DATE, 10),
			JourneyProfileOracleAccessInputs.normalize(accessData(), request.mobilityProfile(), request.constraintMode(),
				request.walkingPace().speedMetersPerHour(), 10));
		assertThat(expected).hasSize(2);
		assertThat(expected).extracting(JourneyProfileExactOracle.Candidate::arrivalAtDestination)
			.containsExactlyInAnyOrder(instantAt(36_720), instantAt(37_020));
		var result = (JourneyProfileRaptorPort.PlanningResult.Planned) adapter.plan(
			request, snapshot(source), null, policy().profilePlanningLimits());
		var plan = (JourneyProfileRaptorPort.ArriveByPlan) result.temporalPlan();
		var found = (JourneyProfileRaptorPort.ReversePlan.Found) plan.result();
		assertThat(JourneyProfileOracleComparison.matchesObservableTimetableFrontier(expected, found.itineraries())).isTrue();
	}

	@Test
	void retainsWaitingForTheFirstTrainAfterTheDepartureWindow() {
		var request = query(new JourneyRaptorQuery.DepartBetween(instantAt(30_000), instantAt(31_000)));
		var expected = new JourneyProfileExactOracle().solveDepartureWindow(new JourneyProfileExactOracle.Query(
			request.originStationId(), request.destinationStationId(), instantAt(30_000), instantAt(37_000),
			0, STANDARD_BOARDING_SLACK_SECONDS, 10_000, () -> false), instantAt(31_000),
			JourneyProfileScheduledOracleInputs.rides(timetable(), SERVICE_DATE, 10),
			JourneyProfileOracleAccessInputs.normalize(accessData(), request.mobilityProfile(), request.constraintMode(),
				request.walkingPace().speedMetersPerHour(), 10));
		assertThat(expected).hasSize(1);
		assertThat(expected.getFirst().readyAt()).isEqualTo(instantAt(31_000));
		assertThat(expected.getFirst().arrivalAtDestination()).isEqualTo(instantAt(36_720));
		var result = (JourneyProfileRaptorPort.PlanningResult.Planned) adapter.plan(
			request, snapshot(), null, policy().profilePlanningLimits());
		var plan = (JourneyProfileRaptorPort.DepartureWindowPlan) result.temporalPlan();
		assertThat(plan.points()).singleElement().satisfies(point -> {
			assertThat(point.readyAt()).isEqualTo(instantAt(31_000));
			assertThat(JourneyProfileOracleComparison.matchesObservableTimetableFrontier(expected, point.itineraries())).isTrue();
		});
		var exhausted = (JourneyProfileRaptorPort.PlanningResult.Planned) adapter.plan(
			query(new JourneyRaptorQuery.DepartBetween(instantAt(37_000), instantAt(38_000))),
			snapshot(), null, policy().profilePlanningLimits());
		assertThat(((JourneyProfileRaptorPort.DepartureWindowPlan) exhausted.temporalPlan()).points()).isEmpty();
	}

	@Test
	void dispatchesDepartureWindowAgainstTheCapturedRuntimeWithoutPointFallback() {
		var result = adapter.plan(query(new JourneyRaptorQuery.DepartBetween(instantAt(30_000), instantAt(37_000))),
			snapshot(), null, policy().profilePlanningLimits());
		var planResult = (JourneyProfileRaptorPort.PlanningResult.Planned) result;
		assertThat(planResult.countSnapshot().requestId()).isEqualTo(REQUEST_ID);
		assertThat(planResult.countSnapshot().algorithmIdentity())
			.isEqualTo(JourneyRaptorPruningInventoryV1.FORWARD_RANGE_RAPTOR);
		assertThat(planResult.countSnapshot().countsByRuleId().keySet())
			.containsExactlyInAnyOrderElementsOf(
				JourneyRaptorPruningInventoryV1.activeRuleIds(JourneyRaptorPruningInventoryV1.FORWARD_RANGE_RAPTOR));
		assertThat(planResult.planningMetrics().workConsumed()).isPositive();
		assertThat(planResult.planningMetrics().peakStateLabels()).isPositive();
		assertThat(planResult.planningMetrics().reservedProfileBreakpoints()).isPositive();

		assertThat(planResult.temporalPlan()).isInstanceOfSatisfying(JourneyProfileRaptorPort.DepartureWindowPlan.class, plan -> {
			assertThat(plan.points()).singleElement().satisfies(point -> {
				assertThat(point.serviceDate()).isEqualTo(SERVICE_DATE);
				assertThat(point.itineraries()).singleElement().satisfies(itinerary -> {
					assertThat(itinerary.metrics()).isEqualTo(new JourneyProfileRaptorPort.ItineraryMetrics(
						0, 420, 100, 0, new JourneyProfileRaptorPort.NoTransfer()));
					assertThat(itinerary.legs()).anySatisfy(leg -> assertThat(leg)
						.isInstanceOfSatisfying(JourneyProfileRaptorPort.RideLeg.class,
							ride -> assertThat(ride.tripId()).isEqualTo("direct")));
					assertThat(itinerary.legs()).anySatisfy(leg -> assertThat(leg)
						.isInstanceOfSatisfying(JourneyProfileRaptorPort.AccessLeg.class, access -> {
							assertThat(access.verified()).isTrue();
							assertThat(access.distanceMeters()).isEqualTo(50);
							assertThat(access.verificationStatus()).isEqualTo("VERIFIED");
						}));
				});
			});
		});
	}

	@Test
	void dispatchesArriveByToTheNativeReversePrimitive() {
		var result = adapter.plan(query(new JourneyRaptorQuery.ArriveBy(instantAt(30_000), instantAt(37_000))),
			snapshot(), null, policy().profilePlanningLimits());
		var planResult = (JourneyProfileRaptorPort.PlanningResult.Planned) result;
		assertThat(planResult.countSnapshot().requestId()).isEqualTo(REQUEST_ID);
		assertThat(planResult.countSnapshot().algorithmIdentity())
			.isEqualTo(JourneyRaptorPruningInventoryV1.REVERSE_RANGE_RAPTOR);
		assertThat(planResult.planningMetrics().workConsumed()).isPositive();
		assertThat(planResult.planningMetrics().reservedProfileBreakpoints()).isZero();

		assertThat(planResult.temporalPlan()).isInstanceOfSatisfying(JourneyProfileRaptorPort.ArriveByPlan.class, plan ->
			assertThat(plan.result()).isInstanceOfSatisfying(JourneyProfileRaptorPort.ReversePlan.Found.class,
				found -> {
					assertThat(found.itineraries()).singleElement().satisfies(itinerary -> {
						assertThat(itinerary.metrics()).isEqualTo(new JourneyProfileRaptorPort.ItineraryMetrics(
							0, 420, 100, 0, new JourneyProfileRaptorPort.NoTransfer()));
						assertThat(itinerary.legs()).anySatisfy(leg -> assertThat(leg)
							.isInstanceOfSatisfying(JourneyProfileRaptorPort.RideLeg.class,
								ride -> assertThat(ride.tripId()).isEqualTo("direct")));
					});
				}));
	}

	@Test
	void dispatchesArriveByAcrossTheServiceDayCutoffWithoutDroppingA27HourTrip() {
		var result = adapter.plan(query(new JourneyRaptorQuery.ArriveBy(instantAt(96_000), instantAt(98_043))),
			snapshot(crossCutoffTimetable()), null, policy().profilePlanningLimits());

		assertThat(result).isInstanceOfSatisfying(JourneyProfileRaptorPort.PlanningResult.Planned.class, planned ->
			assertThat(planned.temporalPlan()).isInstanceOfSatisfying(JourneyProfileRaptorPort.ArriveByPlan.class, plan ->
				assertThat(plan.result()).isInstanceOfSatisfying(JourneyProfileRaptorPort.ReversePlan.Found.class, found ->
					assertThat(found.itineraries()).singleElement().satisfies(itinerary -> {
						assertThat(itinerary.plannedReadyAt()).isEqualTo(Instant.parse("2026-07-01T17:54:00Z"));
						assertThat(itinerary.plannedArrivalAtDestination()).isEqualTo(Instant.parse("2026-07-01T18:12:00Z"));
					}))));
	}

	@Test
	void mapsReverseWorkAdmissionWithoutReturningAPartialFrontier() {
		var result = adapter.plan(query(new JourneyRaptorQuery.ArriveBy(instantAt(30_000), instantAt(37_000))),
			snapshot(), null, new JourneyProfileResourcePolicy.ProfilePlanningLimits(1, 32, 32, 32));

		assertThat(result).isInstanceOfSatisfying(JourneyProfileRaptorPort.PlanningResult.AdmissionRejected.class,
			rejected -> {
				assertThat(rejected.observed()).isEqualTo(2);
				assertThat(rejected.max()).isEqualTo(1);
				assertThat(rejected.planningMetrics().workConsumed()).isEqualTo(rejected.observed());
				assertThat(rejected.planningMetrics().reservedProfileBreakpoints()).isZero();
			});
	}

	@Test
	void keepsPlanningMetricsIsolatedBetweenRequestsOnTheSameAdapter() {
		var captured = snapshot();
		var first = adapter.plan(
			query(new JourneyRaptorQuery.DepartBetween(instantAt(30_000), instantAt(37_000))),
			captured, null, policy().profilePlanningLimits());
		var firstMetrics = first.planningMetrics();
		assertThat(firstMetrics.reservedProfileBreakpoints()).isPositive();
		var secondQuery = new JourneyRaptorQuery(
			"01ARZ3NDEKTSV4RRFFQ69G5FAW", "station-a", "station-b",
			new JourneyRaptorQuery.ArriveBy(instantAt(30_000), instantAt(37_000)),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 0, 1, () -> false);

		var second = adapter.plan(secondQuery, captured, null,
			new JourneyProfileResourcePolicy.ProfilePlanningLimits(1, 32, 32, 32));

		assertThat(second).isInstanceOf(JourneyProfileRaptorPort.PlanningResult.AdmissionRejected.class);
		assertThat(second.countSnapshot().requestId()).isEqualTo(secondQuery.requestId());
		assertThat(second.planningMetrics().workConsumed()).isEqualTo(2);
		assertThat(second.planningMetrics().reservedProfileBreakpoints()).isZero();
		assertThat(first.countSnapshot().requestId()).isEqualTo(REQUEST_ID);
		assertThat(first.planningMetrics()).isEqualTo(firstMetrics);
	}

	@Test
	void preservesTheRealForwardCapacityObservationWithoutReturningAPartialSuccess() {
		var result = adapter.plan(
			profileQuery(new JourneyRaptorQuery.DepartBetween(instantAt(35_000), instantAt(35_001)), 3),
			snapshot(threePathTimetable()), null,
			new JourneyProfileResourcePolicy.ProfilePlanningLimits(100_000L, 32, 1, 32));

		assertThat(result).isInstanceOfSatisfying(JourneyProfileRaptorPort.PlanningResult.CapacityExceeded.class,
			exceeded -> {
				assertThat(exceeded.dimension())
					.isEqualTo(JourneyProfileRaptorPort.PlanningCapacity.MAX_DESTINATION_PROFILE_LABELS);
				assertThat(exceeded.observed()).isEqualTo(threePathFacts().size());
				assertThat(exceeded.max()).isEqualTo(1);
				assertThat(exceeded.countSnapshot().requestId()).isEqualTo(REQUEST_ID);
				assertThat(exceeded.countSnapshot().algorithmIdentity())
					.isEqualTo(JourneyRaptorPruningInventoryV1.FORWARD_RANGE_RAPTOR);
				assertThat(exceeded.countSnapshot().countsByRuleId()
					.get("FAIL_CLOSED_FRONTIER_CAPACITY_V1")).isEqualTo(1L);
				assertThat(exceeded.planningMetrics().workConsumed()).isPositive();
				assertThat(exceeded.planningMetrics().peakDestinationLabels()).isEqualTo(threePathFacts().size());
			});
	}

	@Test
	void preservesThreeIndependentDirectFactsAndEveryRequiredDepartureRepresentative() {
		var temporal = new JourneyRaptorQuery.DepartBetween(instantAt(35_000), instantAt(35_001));
		var result = adapter.plan(profileQuery(temporal, 3), snapshot(threePathTimetable()), null,
			new JourneyProfileResourcePolicy.ProfilePlanningLimits(100_000L, 32, 3, 32));
		var plan = (JourneyProfileRaptorPort.DepartureWindowPlan) ((JourneyProfileRaptorPort.PlanningResult.Planned) result)
			.temporalPlan();
		assertThat(plan.points()).singleElement().satisfies(point -> assertThat(point.readyAt()).isEqualTo(instantAt(35_000)));
		var itineraries = plan.points().getFirst().itineraries();
		List<ExpectedFact> actualFacts = normalize(itineraries);
		assertThat(actualFacts).containsExactlyInAnyOrderElementsOf(threePathFacts());
		assertReadinessAlignment(threePathFacts());

		var projected = JourneyProfileCandidateProjectionV1.projectDepartureWindow(profileQuery(temporal, 3), plan, 3);
		assertThat(projected).isInstanceOf(JourneyProfileCandidateProjectionV1.Projected.class);
		var projection = ((JourneyProfileCandidateProjectionV1.Projected) projected).projection();
		Map<String, String> candidateIdsByPath = candidateIdsByPath(projection.candidates());
		assertThat(candidateIdsByPath.keySet()).containsExactlyInAnyOrder("fast", "walk", "accessible");
		var oracle = independentFrontier(threePathFacts(), candidateIdsByPath);
		var actualOracle = independentFrontier(actualFacts, candidateIdsByPath);
		assertThat(oracle.frontier()).containsExactlyInAnyOrderElementsOf(threePathFacts());
		assertThat(actualOracle).isEqualTo(oracle);
		assertThat(oracle.representatives().keySet()).containsExactlyInAnyOrder(ObjectiveTag.values());
		assertThat(Set.copyOf(oracle.representatives().values())).hasSize(3);
		Map<String, String> pathsByCandidateId = candidateIdsByPath.entrySet().stream()
			.collect(java.util.stream.Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
		var actualRepresentatives = new EnumMap<ObjectiveTag, String>(ObjectiveTag.class);
		for (JourneyProfileCandidateProjectionV1.Candidate candidate : projection.candidates()) {
			for (ObjectiveTag tag : candidate.objectiveTags()) {
				actualRepresentatives.put(tag, pathsByCandidateId.get(candidate.candidateId()));
			}
		}
		assertThat(actualRepresentatives).isEqualTo(oracle.representatives());
		assertThat(projection.summary().earliestArrivalJourneyId())
			.isEqualTo(candidateIdsByPath.get(oracle.representatives().get(ObjectiveTag.FASTEST_ARRIVAL)));
		assertThat(projection.summary().latestDepartureJourneyId())
			.isEqualTo(candidateIdsByPath.get(oracle.representatives().get(ObjectiveTag.LATEST_DEPARTURE)));

		var exceeded = JourneyProfileCandidateProjectionV1.projectDepartureWindow(profileQuery(temporal, 3), plan, 2);
		assertThat(exceeded).isInstanceOf(JourneyProfileCandidateProjectionV1.CapacityExceeded.class);
		assertThat(((JourneyProfileCandidateProjectionV1.CapacityExceeded) exceeded).observed()).isEqualTo(3);
		assertThat(((JourneyProfileCandidateProjectionV1.CapacityExceeded) exceeded).max()).isEqualTo(2);
	}

	@Test
	void preservesThreeIndependentReverseFactsAndFailsClosedAtTheDestinationCapacity() {
		var temporal = new JourneyRaptorQuery.ArriveBy(instantAt(35_000), instantAt(36_300));
		var result = adapter.plan(profileQuery(temporal, 3), snapshot(threePathTimetable()), null,
			new JourneyProfileResourcePolicy.ProfilePlanningLimits(100_000L, 32, 3, 32));
		var plan = (JourneyProfileRaptorPort.ArriveByPlan) ((JourneyProfileRaptorPort.PlanningResult.Planned) result)
			.temporalPlan();
		var itineraries = ((JourneyProfileRaptorPort.ReversePlan.Found) plan.result()).itineraries();
		List<ExpectedFact> actualFacts = normalize(itineraries);
		assertThat(actualFacts).containsExactlyInAnyOrderElementsOf(threePathFacts());
		assertReadinessAlignment(threePathFacts());
		Map<String, String> canonicalPathIds = threePathFacts().stream()
			.collect(java.util.stream.Collectors.toMap(ExpectedFact::pathId, ExpectedFact::pathId));
		var oracle = independentFrontier(threePathFacts(), canonicalPathIds);
		var actualOracle = independentFrontier(actualFacts, canonicalPathIds);
		assertThat(oracle.frontier()).containsExactlyInAnyOrderElementsOf(threePathFacts());
		assertThat(actualOracle.representatives()).isEqualTo(oracle.representatives());
		assertThat(actualOracle.representatives().keySet()).containsExactlyInAnyOrder(ObjectiveTag.values());

		var exceeded = adapter.plan(profileQuery(temporal, 3), snapshot(threePathTimetable()), null,
			new JourneyProfileResourcePolicy.ProfilePlanningLimits(100_000L, 32, 2, 32));
		assertThat(exceeded).isInstanceOfSatisfying(JourneyProfileRaptorPort.PlanningResult.CapacityExceeded.class,
			failure -> {
				assertThat(failure.dimension()).isEqualTo(JourneyProfileRaptorPort.PlanningCapacity.MAX_DESTINATION_PROFILE_LABELS);
				assertThat(failure.observed()).isEqualTo(3);
				assertThat(failure.max()).isEqualTo(2);
				assertThat(failure.countSnapshot().countsByRuleId().get("FAIL_CLOSED_FRONTIER_CAPACITY_V1")).isEqualTo(1L);
			});
	}

	@Test
	void derivesTransferSlackAndAccessBurdenFromThePlannerNativeLegs() {
		Instant base = instantAt(0);
		var legs = List.<RouteTimetableRaptorPlanner.JourneyLegProjection>of(
			access(RouteTimetableRaptorPlanner.JourneyAccessKind.ENTRY, 100, 20, false),
			ride("first", base.plusSeconds(500), base.plusSeconds(1_000)),
			access(RouteTimetableRaptorPlanner.JourneyAccessKind.TRANSFER, 180, 30, true),
			ride("second", base.plusSeconds(1_300), base.plusSeconds(1_600)),
			access(RouteTimetableRaptorPlanner.JourneyAccessKind.EXIT, 50, 10, false));

		assertThat(RouteTimetableRaptorPlanner.itineraryMetrics(legs, 60))
			.isEqualTo(new JourneyProfileRaptorPort.ItineraryMetrics(
				1, 330, 60, 1, new JourneyProfileRaptorPort.MinimumTransferSeconds(60)));
	}

	@Test
	void dispatchesLastConnectionToTheNativeTerminalEventPrimitive() {
		var result = adapter.plan(query(new JourneyRaptorQuery.LastConnection(SERVICE_DATE)), snapshot(), null,
			policy().profilePlanningLimits());
		var planResult = (JourneyProfileRaptorPort.PlanningResult.Planned) result;

		assertThat(planResult.temporalPlan()).isInstanceOfSatisfying(JourneyProfileRaptorPort.LastConnectionPlan.class, plan ->
			assertThat(plan.result()).isInstanceOf(JourneyProfileRaptorPort.ReversePlan.Found.class));
	}

	@Test
	void rejectsRealtimeProfileInsteadOfUsingTimetableAsFallback() {
		var query = new JourneyRaptorQuery(
			REQUEST_ID, "station-a", "station-b",
			new JourneyRaptorQuery.LastConnection(SERVICE_DATE), JourneyRequest.TimePolicy.REALTIME_REQUIRED,
			JourneyRequest.WalkingPace.STANDARD, JourneyRequest.MobilityProfile.STANDARD,
			JourneyRequest.ConstraintMode.NONE, 0, 1, () -> false);

		assertThatThrownBy(() -> adapter.plan(query, snapshot(), null, policy().profilePlanningLimits()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("TIMETABLE_REQUIRED");
	}

	private static JourneyRaptorQuery query(JourneyRaptorQuery.TemporalQuery temporalQuery) {
		return profileQuery(temporalQuery, 1);
	}

	private static JourneyRaptorQuery profileQuery(JourneyRaptorQuery.TemporalQuery temporalQuery, int alternativeCount) {
		return new JourneyRaptorQuery(
			REQUEST_ID, "station-a", "station-b", temporalQuery, JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.STANDARD, JourneyRequest.MobilityProfile.STANDARD,
			JourneyRequest.ConstraintMode.NONE, 0, alternativeCount, () -> false);
	}

	private static ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot() {
		return snapshot(timetable());
	}

	private static ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot(RouteTimetable timetable) {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, 1, timetable);
		return new ActiveJourneySnapshotPort.ActiveJourneySnapshot(
			"snapshot", "bundle", ROUTE_BUNDLE_SHA, "timetable", "accessibility", 1, runtime,
			Instant.parse("2026-07-03T00:00:00Z"), true,
			ActiveJourneySnapshotPort.ActiveServingEvidence.unobservable(),
			ActiveJourneySnapshotPort.SnapshotBoundaryReceipt.observed(0, 0));
	}

	private static Instant instantAt(int seconds) {
		return SERVICE_DATE.atStartOfDay().plusSeconds(seconds).atOffset(ZoneOffset.ofHours(9)).toInstant();
	}

	private static JourneyProfileResourcePolicy policy() {
		return new JourneyProfileResourcePolicy(
			new JourneyProfileResourcePolicy.Identity("test-profile", "1.0.0", "b".repeat(64)),
			Duration.ofHours(2), 2, 100_000L, 32, 32, 32,
			Duration.ofMinutes(5), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1),
			1, 1, 1, 1, 4);
	}

	private static RouteTimetable timetable() {
		return timetable(List.of());
	}

	private static RouteTimetable timetable(List<LoadRouteTimetablePort.TransitFrequency> frequencies) {
		var calendar = new LoadRouteTimetablePort.ServiceCalendar(
			"daily", true, true, true, true, true, true, true,
			SERVICE_DATE, SERVICE_DATE, "Asia/Seoul");
		var route = new LoadRouteTimetablePort.TransitRoute(
			"route", "line", "L", "Line", "Terminal", "Asia/Seoul");
		var trip = new LoadRouteTimetablePort.TransitTrip("direct", "route", "daily", "Terminal", "0", "LOCAL", 0);
		return new RouteTimetable(
			List.of(calendar), List.of(), List.of(route), List.of(trip),
			List.of(stop("direct", 1, "station-a", 36_000), stop("direct", 2, "station-b", 36_600)),
			frequencies, List.of(), null, accessData());
	}

	private static RouteTimetable crossCutoffTimetable() {
		var calendar = new LoadRouteTimetablePort.ServiceCalendar(
			"daily", true, true, true, true, true, true, true,
			SERVICE_DATE, SERVICE_DATE.plusDays(1), "Asia/Seoul");
		var route = new LoadRouteTimetablePort.TransitRoute(
			"route", "line", "L", "Line", "Terminal", "Asia/Seoul");
		var trip = new LoadRouteTimetablePort.TransitTrip("late", "route", "daily", "Terminal", "0", "LOCAL", 0);
		return new RouteTimetable(
			List.of(calendar), List.of(), List.of(route), List.of(trip),
			List.of(stop("late", 1, "station-a", 97_200), stop("late", 2, "station-b", 97_800)),
			List.of(), List.of(), null, accessData());
	}

	private static RouteTimetable threePathTimetable() {
		var calendar = new LoadRouteTimetablePort.ServiceCalendar(
			"daily", true, true, true, true, true, true, true, SERVICE_DATE, SERVICE_DATE, "Asia/Seoul");
		var fastRoute = new LoadRouteTimetablePort.TransitRoute("route-fast", "line-fast", "F", "Fast", "Terminal", "Asia/Seoul");
		var walkRoute = new LoadRouteTimetablePort.TransitRoute("route-walk", "line-walk", "W", "Walk", "Terminal", "Asia/Seoul");
		var accessibleRoute = new LoadRouteTimetablePort.TransitRoute("route-accessible", "line-accessible", "A", "Accessible", "Terminal", "Asia/Seoul");
		return new RouteTimetable(
			List.of(calendar), List.of(), List.of(fastRoute, walkRoute, accessibleRoute), List.of(
				new LoadRouteTimetablePort.TransitTrip("fast", "route-fast", "daily", "Terminal", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip("walk", "route-walk", "daily", "Terminal", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip("accessible", "route-accessible", "daily", "Terminal", "0", "LOCAL", 0)),
			List.of(
				stop("fast", 1, "station-a", "line-fast", 35_360), stop("fast", 2, "station-b", "line-fast", 36_000),
				stop("walk", 1, "station-a", "line-walk", 35_180), stop("walk", 2, "station-b", "line-walk", 36_100),
				stop("accessible", 1, "station-a", "line-accessible", 35_260), stop("accessible", 2, "station-b", "line-accessible", 36_200)),
			List.of(), List.of(), null, threePathAccessData());
	}

	private static LoadRouteTimetablePort.RouteAccessData threePathAccessData() {
		return new LoadRouteTimetablePort.RouteAccessData(
			List.of(
				new LoadRouteTimetablePort.PathwayNode("entry", "station-a", null, "ENTRANCE"),
				new LoadRouteTimetablePort.PathwayNode("fast-platform-a", "station-a", "line-fast", "PLATFORM"),
				new LoadRouteTimetablePort.PathwayNode("walk-platform-a", "station-a", "line-walk", "PLATFORM"),
				new LoadRouteTimetablePort.PathwayNode("accessible-platform-a", "station-a", "line-accessible", "PLATFORM"),
				new LoadRouteTimetablePort.PathwayNode("fast-platform-b", "station-b", "line-fast", "PLATFORM"),
				new LoadRouteTimetablePort.PathwayNode("walk-platform-b", "station-b", "line-walk", "PLATFORM"),
				new LoadRouteTimetablePort.PathwayNode("accessible-platform-b", "station-b", "line-accessible", "PLATFORM"),
				new LoadRouteTimetablePort.PathwayNode("exit", "station-b", null, "EXIT")),
			List.of(
				edge("fast-entry", "entry", "fast-platform-a", 300, 100, true), edge("fast-exit", "fast-platform-b", "exit", 120, 50, true),
				edge("walk-entry", "entry", "walk-platform-a", 120, 30, true), edge("walk-exit", "walk-platform-b", "exit", 80, 20, false),
				edge("accessible-entry", "entry", "accessible-platform-a", 200, 80, false), edge("accessible-exit", "accessible-platform-b", "exit", 100, 20, false)),
			List.of(), List.of(
				evidence("fast-entry-evidence", "station-a", "line-fast", "fast-entry", "ENTRY"), evidence("fast-exit-evidence", "station-b", "line-fast", "fast-exit", "EXIT"),
				evidence("walk-entry-evidence", "station-a", "line-walk", "walk-entry", "ENTRY"), evidence("walk-exit-evidence", "station-b", "line-walk", "walk-exit", "EXIT"),
				evidence("accessible-entry-evidence", "station-a", "line-accessible", "accessible-entry", "ENTRY"), evidence("accessible-exit-evidence", "station-b", "line-accessible", "accessible-exit", "EXIT")));
	}

	private static LoadRouteTimetablePort.TransitStopTime stop(String tripId, int sequence, String station, int seconds) {
		return stop(tripId, sequence, station, "line", seconds);
	}

	private static LoadRouteTimetablePort.TransitStopTime stop(
		String tripId, int sequence, String station, String lineId, int seconds
	) {
		return new LoadRouteTimetablePort.TransitStopTime(
			tripId, sequence, station, lineId, seconds, seconds, 0, 0);
	}

	private static List<ExpectedFact> threePathFacts() {
		return List.of(
			new ExpectedFact("fast", 35_000, 300, 35_300, 35_360, 36_000, 120, 36_120, 0, 420, 150, 2, true),
			new ExpectedFact("walk", 35_000, 120, 35_120, 35_180, 36_100, 80, 36_180, 0, 200, 50, 1, true),
			new ExpectedFact("accessible", 35_000, 200, 35_200, 35_260, 36_200, 100, 36_300, 0, 300, 100, 0, true));
	}

	private static void assertReadinessAlignment(List<ExpectedFact> facts) {
		assertThat(facts).allSatisfy(fact -> {
			assertThat(fact.entryCompleteSeconds()).isEqualTo(fact.readySeconds() + fact.entrySeconds());
			assertThat(fact.firstBoardingSeconds()).isEqualTo(
				fact.entryCompleteSeconds() + STANDARD_BOARDING_SLACK_SECONDS);
			assertThat(fact.destinationArrivalSeconds()).isEqualTo(
				fact.finalPlatformArrivalSeconds() + fact.exitSeconds());
		});
	}

	private static List<ExpectedFact> normalize(List<JourneyProfileRaptorPort.Itinerary> itineraries) {
		return itineraries.stream().map(itinerary -> {
			List<JourneyProfileRaptorPort.RideLeg> rides = itinerary.legs().stream()
				.filter(JourneyProfileRaptorPort.RideLeg.class::isInstance).map(JourneyProfileRaptorPort.RideLeg.class::cast).toList();
			String tripId = rides.getFirst().tripId();
			int entrySeconds = itinerary.legs().stream().filter(JourneyProfileRaptorPort.AccessLeg.class::isInstance)
				.map(JourneyProfileRaptorPort.AccessLeg.class::cast)
				.filter(access -> access.kind() == JourneyProfileRaptorPort.AccessKind.ENTRY)
				.mapToInt(JourneyProfileRaptorPort.AccessLeg::durationSeconds).findFirst().orElseThrow();
			int exitSeconds = itinerary.legs().stream().filter(JourneyProfileRaptorPort.AccessLeg.class::isInstance)
				.map(JourneyProfileRaptorPort.AccessLeg.class::cast)
				.filter(access -> access.kind() == JourneyProfileRaptorPort.AccessKind.EXIT)
				.mapToInt(JourneyProfileRaptorPort.AccessLeg::durationSeconds).findFirst().orElseThrow();
			var metrics = itinerary.metrics();
			int readySeconds = secondsAt(itinerary.plannedReadyAt());
			return new ExpectedFact(tripId, readySeconds, entrySeconds, readySeconds + entrySeconds,
				secondsAt(rides.getFirst().plannedDepartureTime()), secondsAt(rides.getLast().plannedArrivalTime()), exitSeconds,
				secondsAt(itinerary.plannedArrivalAtDestination()),
				metrics.transfersUsed(), metrics.accessMovementSeconds(), metrics.accessDistanceMeters(), metrics.accessibilityBurden(),
				metrics.connectionSlack() instanceof JourneyProfileRaptorPort.NoTransfer);
		}).sorted(Comparator.comparing(ExpectedFact::pathId)).toList();
	}

	private static Map<String, String> candidateIdsByPath(
		List<JourneyProfileCandidateProjectionV1.Candidate> candidates
	) {
		Map<String, String> ids = new LinkedHashMap<>();
		for (JourneyProfileCandidateProjectionV1.Candidate candidate : candidates) {
			String pathId = candidate.itinerary().legs().stream().filter(JourneyProfileRaptorPort.RideLeg.class::isInstance)
				.map(JourneyProfileRaptorPort.RideLeg.class::cast).map(JourneyProfileRaptorPort.RideLeg::tripId)
				.findFirst().orElseThrow();
			if (ids.putIfAbsent(pathId, candidate.candidateId()) != null) {
				throw new IllegalArgumentException("duplicate fixture path: " + pathId);
			}
		}
		return Map.copyOf(ids);
	}

	private static IndependentFrontier independentFrontier(List<ExpectedFact> facts, Map<String, String> canonicalKeys) {
		List<ExpectedFact> frontier = facts.stream().filter(candidate -> facts.stream()
			.noneMatch(other -> other != candidate && dominates(other, candidate))).toList();
		var representatives = new EnumMap<ObjectiveTag, String>(ObjectiveTag.class);
		for (ObjectiveTag tag : ObjectiveTag.values()) {
			ExpectedFact selected = frontier.stream().min((left, right) -> compareFor(tag, left, right, canonicalKeys)).orElseThrow();
			representatives.put(tag, selected.pathId());
		}
		return new IndependentFrontier(frontier, Map.copyOf(representatives));
	}

	private static boolean dominates(ExpectedFact left, ExpectedFact right) {
		return left.readySeconds() >= right.readySeconds()
			&& left.destinationArrivalSeconds() <= right.destinationArrivalSeconds()
			&& left.transfers() <= right.transfers()
			&& left.accessSeconds() <= right.accessSeconds()
			&& left.accessMeters() <= right.accessMeters()
			&& left.stairBurden() <= right.stairBurden()
			&& (left.noTransfer() || !right.noTransfer())
			&& (left.readySeconds() > right.readySeconds()
				|| left.destinationArrivalSeconds() < right.destinationArrivalSeconds()
				|| left.transfers() < right.transfers()
				|| left.accessSeconds() < right.accessSeconds()
				|| left.accessMeters() < right.accessMeters()
				|| left.stairBurden() < right.stairBurden()
				|| left.noTransfer() && !right.noTransfer());
	}

	private static int compareFor(
		ObjectiveTag tag, ExpectedFact left, ExpectedFact right, Map<String, String> canonicalKeys
	) {
		int comparison = switch (tag) {
			case FASTEST_ARRIVAL -> Integer.compare(left.destinationArrivalSeconds(), right.destinationArrivalSeconds());
			case LATEST_DEPARTURE -> Integer.compare(right.readySeconds(), left.readySeconds());
			case FEWEST_TRANSFERS -> Integer.compare(left.transfers(), right.transfers());
			case LOWEST_WALKING_BURDEN -> left.compareWalking(right);
			case BEST_ACCESSIBILITY -> Long.compare(left.stairBurden(), right.stairBurden());
			case SAFEST_CONNECTION -> Boolean.compare(right.noTransfer(), left.noTransfer());
		};
		return comparison != 0 ? comparison : canonicalKeys.get(left.pathId()).compareTo(canonicalKeys.get(right.pathId()));
	}

	private static int secondsAt(Instant instant) {
		return Math.toIntExact(Duration.between(SERVICE_DATE.atStartOfDay().atOffset(ZoneOffset.ofHours(9)).toInstant(), instant).toSeconds());
	}

	private record ExpectedFact(
		String pathId,
		int readySeconds,
		int entrySeconds,
		int entryCompleteSeconds,
		int firstBoardingSeconds,
		int finalPlatformArrivalSeconds,
		int exitSeconds,
		int destinationArrivalSeconds,
		int transfers,
		long accessSeconds,
		long accessMeters,
		long stairBurden,
		boolean noTransfer
	) {
		private int compareWalking(ExpectedFact other) {
			int comparison = Long.compare(accessSeconds, other.accessSeconds);
			return comparison != 0 ? comparison : Long.compare(accessMeters, other.accessMeters);
		}
	}

	private record IndependentFrontier(List<ExpectedFact> frontier, Map<ObjectiveTag, String> representatives) {
	}

	private static LoadRouteTimetablePort.RouteAccessData accessData() {
		return new LoadRouteTimetablePort.RouteAccessData(
			List.of(
				new LoadRouteTimetablePort.PathwayNode("entry-from", "station-a", null, "ENTRANCE"),
				new LoadRouteTimetablePort.PathwayNode("entry-to", "station-a", "line", "PLATFORM"),
				new LoadRouteTimetablePort.PathwayNode("exit-from", "station-b", "line", "PLATFORM"),
				new LoadRouteTimetablePort.PathwayNode("exit-to", "station-b", null, "EXIT")),
			List.of(edge("entry", "entry-from", "entry-to", 300), edge("exit", "exit-from", "exit-to", 120)),
			List.of(),
			List.of(
				evidence("entry-evidence", "station-a", "entry", "ENTRY"),
				evidence("exit-evidence", "station-b", "exit", "EXIT")));
	}

	private static LoadRouteTimetablePort.PathwayEdge edge(String id, String from, String to, int seconds) {
		return new LoadRouteTimetablePort.PathwayEdge(
			id, from, to, seconds, 50, false, false, 100,
			"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED");
	}

	private static LoadRouteTimetablePort.PathwayEdge edge(
		String id, String from, String to, int seconds, int distance, boolean stairs
	) {
		return new LoadRouteTimetablePort.PathwayEdge(
			id, from, to, seconds, distance, false, stairs, 100,
			"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED");
	}

	private static RouteTimetableRaptorPlanner.JourneyAccessProjection access(
		RouteTimetableRaptorPlanner.JourneyAccessKind kind, int duration, int distance, boolean stairs
	) {
		return new RouteTimetableRaptorPlanner.JourneyAccessProjection(
			kind, "station", "station", duration, distance, stairs, true, "VERIFIED");
	}

	private static RouteTimetableRaptorPlanner.JourneyRideProjection ride(
		String tripId, Instant departure, Instant arrival
	) {
		return new RouteTimetableRaptorPlanner.JourneyRideProjection(
			"line", tripId, "terminal", "station", "station",
			departure, arrival, null, null);
	}

	private static LoadRouteTimetablePort.RouteEdgeEvidence evidence(
		String id, String stationId, String edgeId, String edgeType
	) {
		return evidence(id, stationId, "line", edgeId, edgeType);
	}

	private static LoadRouteTimetablePort.RouteEdgeEvidence evidence(
		String id, String stationId, String lineId, String edgeId, String edgeType
	) {
		return new LoadRouteTimetablePort.RouteEdgeEvidence(
			id, stationId, lineId, edgeId, edgeType, "OFFICIAL_SOURCE", "VERIFIED", true, null);
	}
}
