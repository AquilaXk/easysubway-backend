package com.easysubway.route.application.service;

import com.easysubway.journey.application.ActiveJourneySnapshotPort.ActiveJourneySnapshot;
import com.easysubway.journey.application.JourneyProfileRaptorPort;
import com.easysubway.journey.application.JourneyProfileResourcePolicy;
import com.easysubway.journey.application.JourneyRealtimePort.RealtimeObservation;
import com.easysubway.journey.application.JourneyRaptorPruningInventoryV1;
import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.application.ServiceDayResolver;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Native temporal-profile adapter over one immutable route runtime generation.
 *
 * <p>Realtime profile acquisition is intentionally not implemented here: the current realtime
 * query collector accepts only {@code DEPART_AT}. A REALTIME_REQUIRED profile is rejected rather
 * than silently using a timetable overlay.</p>
 */
public final class JourneyProfileRaptorAdapter implements JourneyProfileRaptorPort {

	private final RouteTimetableRaptorPlanner forward = new RouteTimetableRaptorPlanner();
	private final ReverseTimetableRaptorPlanner reverse = new ReverseTimetableRaptorPlanner();

	@Override
	public PlanningResult plan(
		JourneyRaptorQuery query,
		ActiveJourneySnapshot snapshot,
		RealtimeObservation realtimeOrNull,
		JourneyProfileResourcePolicy.ProfilePlanningLimits limits
	) {
		JourneyRaptorQuery requiredQuery = Objects.requireNonNull(query, "query");
		JourneyProfileResourcePolicy.ProfilePlanningLimits requiredLimits = Objects.requireNonNull(limits, "limits");
		if (requiredQuery.isCancelled()) throw new IllegalStateException("Journey profile planning was cancelled");
		if (requiredQuery.timePolicy() != JourneyRequest.TimePolicy.TIMETABLE_REQUIRED || realtimeOrNull != null) {
			throw new IllegalArgumentException("Journey profile adapter currently requires TIMETABLE_REQUIRED without realtime");
		}
		RaptorRouteBundleRuntimeView runtime = requireRouteRuntime(snapshot);
		RouteTimetableRaptorPlanner.CompiledTimetable timetable = runtime.compiledTimetable();
		RouteTimetableRaptorPlanner.RealtimeOverlay overlay = RouteTimetableRaptorPlanner.RealtimeOverlay.empty();
		JourneyProfilePruningObservationAccumulator observations = new JourneyProfilePruningObservationAccumulator(
			requiredQuery.requestId(), algorithmIdentity(requiredQuery));

		try {
			TemporalPlan plan = switch (requiredQuery.temporalQuery()) {
				case JourneyRaptorQuery.DepartBetween range -> new DepartureWindowPlan(range,
					forward.departureProfile(requiredQuery, timetable, overlay, requiredLimits, observations).stream()
						.map(JourneyProfileRaptorAdapter::departurePoint)
						.toList());
				case JourneyRaptorQuery.ArriveBy arriveBy -> new ArriveByPlan(arriveBy,
					reversePlan(requiredQuery, timetable, overlay, arriveBy, requiredLimits, observations));
				case JourneyRaptorQuery.LastConnection lastConnection -> lastConnectionPlan(
					requiredQuery, timetable, overlay, lastConnection, requiredLimits, observations);
				case JourneyRaptorQuery.DepartAt ignored -> throw new IllegalArgumentException(
					"Journey profile adapter does not accept DEPART_AT");
			};
			return new PlanningResult.Planned(plan, observations.snapshot(), observations.planningMetrics());
		} catch (RouteTimetableRaptorPlanner.ProfilePlanningLimitException exceeded) {
			return switch (exceeded.limit()) {
				case MAX_ESTIMATED_WORK -> new PlanningResult.AdmissionRejected(
					exceeded.observed(), exceeded.max(), observations.snapshot(), observations.planningMetrics());
				case MAX_LABELS_PER_STATE, MAX_DESTINATION_PROFILE_LABELS, MAX_PROFILE_BREAKPOINTS ->
					new PlanningResult.CapacityExceeded(
						PlanningCapacity.valueOf(exceeded.limit().name()), exceeded.observed(), exceeded.max(),
						observations.snapshot(), observations.planningMetrics());
			};
		} catch (ReverseTimetableRaptorPlanner.ReversePlanningLimitException exceeded) {
			return switch (exceeded.limit()) {
				case MAX_ESTIMATED_WORK -> new PlanningResult.AdmissionRejected(
					exceeded.observed(), exceeded.max(), observations.snapshot(), observations.planningMetrics());
				case MAX_LABELS_PER_STATE, MAX_DESTINATION_PROFILE_LABELS -> new PlanningResult.CapacityExceeded(
					PlanningCapacity.valueOf(exceeded.limit().name()), exceeded.observed(), exceeded.max(),
					observations.snapshot(), observations.planningMetrics());
			};
		}
	}

	private JourneyProfileRaptorPort.ReversePlan reversePlan(
		JourneyRaptorQuery query,
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		RouteTimetableRaptorPlanner.RealtimeOverlay overlay,
		JourneyRaptorQuery.ArriveBy arriveBy,
		JourneyProfileResourcePolicy.ProfilePlanningLimits limits,
		JourneyProfilePruningObservationAccumulator observations
	) {
		LocalDate anchorServiceDate = arriveBy.earliestReadyAt().atZone(ServiceDayResolver.ZONE).toLocalDate();
		Instant anchorMidnight = anchorServiceDate.atStartOfDay(ServiceDayResolver.ZONE).toInstant();
		int earliestSeconds = Math.toIntExact(Duration.between(anchorMidnight, arriveBy.earliestReadyAt()).toSeconds());
		int deadlineSeconds = Math.toIntExact(Duration.between(anchorMidnight, arriveBy.arrivalDeadline()).toSeconds());
		LocalDate firstPotentialServiceDate = arriveBy.earliestReadyAt()
			.minusSeconds(LoadRouteTimetablePort.SERVICE_DAY_SECONDS_LIMIT_EXCLUSIVE - 1L)
			.atZone(ServiceDayResolver.ZONE).toLocalDate();
		LocalDate lastPotentialServiceDate = arriveBy.arrivalDeadline().atZone(ServiceDayResolver.ZONE).toLocalDate();
		ReverseTimetableRaptorPlanner.Result result = reverse.arriveBy(
			forward.reverseArriveByQuery(query, anchorServiceDate, earliestSeconds, deadlineSeconds),
			timetable, firstPotentialServiceDate, lastPotentialServiceDate, overlay, limits, observations);
		return reversePlan(result);
	}

	private JourneyProfileRaptorPort.LastConnectionPlan lastConnectionPlan(
		JourneyRaptorQuery query,
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		RouteTimetableRaptorPlanner.RealtimeOverlay overlay,
		JourneyRaptorQuery.LastConnection lastConnection,
		JourneyProfileResourcePolicy.ProfilePlanningLimits limits,
		JourneyProfilePruningObservationAccumulator observations
	) {
		LocalDate serviceDate = lastConnection.serviceDate();
		ReverseTimetableRaptorPlanner.LastConnectionResult result = reverse.lastConnection(
			forward.reverseLastConnectionQuery(query, serviceDate),
			timetable, timetable.activeServiceDay(serviceDate), overlay, limits, observations);
		Instant terminal = result.terminalArrivalAtDestinationSeconds() == null ? null
			: serviceInstant(serviceDate, result.terminalArrivalAtDestinationSeconds());
		return new JourneyProfileRaptorPort.LastConnectionPlan(lastConnection, reversePlan(result.result()), terminal);
	}

	private static JourneyProfileRaptorPort.DeparturePoint departurePoint(
		RouteTimetableRaptorPlanner.JourneyDepartureProfilePoint point
	) {
		return new JourneyProfileRaptorPort.DeparturePoint(
			point.serviceDate(), serviceInstant(point.serviceDate(), point.readyAtSeconds()),
			point.itineraries().stream().map(JourneyProfileRaptorAdapter::itinerary).toList(), point.scanMetrics());
	}

	private static JourneyProfileRaptorPort.ReversePlan reversePlan(ReverseTimetableRaptorPlanner.Result result) {
		return switch (result.outcome()) {
			case FOUND -> new JourneyProfileRaptorPort.ReversePlan.Found(
				result.itineraries().stream().map(JourneyProfileRaptorAdapter::itinerary).toList());
			case NO_ACTIVE_SERVICE, NO_VERIFIED_EXIT, DEADLINE_MISS, NO_OD_CONNECTION, CANCELLED ->
				new JourneyProfileRaptorPort.ReversePlan.NotFound(
					JourneyProfileRaptorPort.ReversePlan.Outcome.valueOf(result.outcome().name()));
		};
	}

	private static JourneyProfileRaptorPort.Itinerary itinerary(
		RouteTimetableRaptorPlanner.JourneyItinerary itinerary
	) {
		List<JourneyProfileRaptorPort.Leg> legs = new ArrayList<>(itinerary.legs().size());
		for (RouteTimetableRaptorPlanner.JourneyLegProjection projection : itinerary.legs()) {
			if (projection instanceof RouteTimetableRaptorPlanner.JourneyAccessProjection access) {
				if (!access.verified()) {
					throw new IllegalArgumentException("Journey profile contains an unverified access transition");
				}
				legs.add(new JourneyProfileRaptorPort.AccessLeg(
					JourneyProfileRaptorPort.AccessKind.valueOf(access.kind().name()),
					access.fromStationId(), access.toStationId(), access.durationSeconds(), access.distanceMeters(),
					access.includesStairs(), access.verified(), access.verificationStatus()));
			} else {
				RouteTimetableRaptorPlanner.JourneyRideProjection ride =
					(RouteTimetableRaptorPlanner.JourneyRideProjection) projection;
				legs.add(new JourneyProfileRaptorPort.RideLeg(ride.lineId(), ride.tripId(), ride.directionStationId(),
					ride.fromStationId(), ride.toStationId(), ride.plannedDepartureTime(), ride.plannedArrivalTime(),
					ride.realtimeDepartureTime(), ride.realtimeArrivalTime()));
			}
		}
		return new JourneyProfileRaptorPort.Itinerary(
			itinerary.serviceDate(), itinerary.plannedDepartureTime(), itinerary.plannedArrivalTime(),
			itinerary.realtimeDepartureTime(), itinerary.realtimeArrivalTime(), itinerary.metrics(), legs);
	}

	private static Instant serviceInstant(LocalDate serviceDate, int secondsFromServiceDayStart) {
		return serviceDate.atStartOfDay(ServiceDayResolver.ZONE).plusSeconds(secondsFromServiceDayStart).toInstant();
	}

	private static RaptorRouteBundleRuntimeView requireRouteRuntime(ActiveJourneySnapshot snapshot) {
		ActiveJourneySnapshot requiredSnapshot = Objects.requireNonNull(snapshot, "snapshot");
		if (!(requiredSnapshot.runtimeView() instanceof RaptorRouteBundleRuntimeView runtime)
			|| !requiredSnapshot.routeBundleSha256().equals(runtime.routeBundleSha256())
			|| requiredSnapshot.generation() != runtime.generation()) {
			throw new IllegalArgumentException("Journey profile runtime view does not match snapshot");
		}
		return runtime;
	}

	private static JourneyRaptorPruningInventoryV1.AlgorithmSemanticIdentity algorithmIdentity(
		JourneyRaptorQuery query
	) {
		return switch (query.temporalQuery()) {
			case JourneyRaptorQuery.DepartBetween ignored -> JourneyRaptorPruningInventoryV1.FORWARD_RANGE_RAPTOR;
			case JourneyRaptorQuery.ArriveBy ignored ->
				JourneyRaptorPruningInventoryV1.REVERSE_RANGE_RAPTOR;
			case JourneyRaptorQuery.LastConnection ignored ->
				JourneyRaptorPruningInventoryV1.REVERSE_RANGE_RAPTOR;
			case JourneyRaptorQuery.DepartAt ignored -> throw new IllegalArgumentException(
				"Journey profile adapter does not accept DEPART_AT");
		};
	}
}
