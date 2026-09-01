package com.easysubway.route.application.service;

import com.easysubway.journey.application.ActiveJourneySnapshotPort.ActiveJourneySnapshot;
import com.easysubway.journey.application.JourneyProfileRaptorPort;
import com.easysubway.journey.application.JourneyRealtimePort.RealtimeObservation;
import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.application.ServiceDayResolver;
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
	public TemporalPlan plan(
		JourneyRaptorQuery query,
		ActiveJourneySnapshot snapshot,
		RealtimeObservation realtimeOrNull
	) {
		JourneyRaptorQuery requiredQuery = Objects.requireNonNull(query, "query");
		if (requiredQuery.isCancelled()) throw new IllegalStateException("Journey profile planning was cancelled");
		if (requiredQuery.timePolicy() != JourneyRequest.TimePolicy.TIMETABLE_REQUIRED || realtimeOrNull != null) {
			throw new IllegalArgumentException("Journey profile adapter currently requires TIMETABLE_REQUIRED without realtime");
		}
		RaptorRouteBundleRuntimeView runtime = requireRouteRuntime(snapshot);
		RouteTimetableRaptorPlanner.CompiledTimetable timetable = runtime.compiledTimetable();
		RouteTimetableRaptorPlanner.RealtimeOverlay overlay = RouteTimetableRaptorPlanner.RealtimeOverlay.empty();

		return switch (requiredQuery.temporalQuery()) {
			case JourneyRaptorQuery.DepartBetween range -> new DepartureWindowPlan(range,
				forward.departureProfile(requiredQuery, timetable, overlay).stream()
					.map(JourneyProfileRaptorAdapter::departurePoint)
					.toList());
			case JourneyRaptorQuery.ArriveBy arriveBy -> new ArriveByPlan(arriveBy,
				reversePlan(requiredQuery, timetable, overlay, arriveBy));
			case JourneyRaptorQuery.LastConnection lastConnection -> new LastConnectionPlan(lastConnection,
				lastConnectionPlan(requiredQuery, timetable, overlay, lastConnection));
			case JourneyRaptorQuery.DepartAt ignored -> throw new IllegalArgumentException(
				"Journey profile adapter does not accept DEPART_AT");
		};
	}

	private JourneyProfileRaptorPort.ReversePlan reversePlan(
		JourneyRaptorQuery query,
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		RouteTimetableRaptorPlanner.RealtimeOverlay overlay,
		JourneyRaptorQuery.ArriveBy arriveBy
	) {
		var earliest = ServiceDayResolver.resolve(arriveBy.earliestReadyAt());
		var deadline = ServiceDayResolver.resolve(arriveBy.arrivalDeadline());
		if (!earliest.serviceDate().equals(deadline.serviceDate())) {
			throw new IllegalArgumentException("ARRIVE_BY must resolve to one active service day");
		}
		ReverseTimetableRaptorPlanner.Result result = reverse.arriveBy(
			forward.reverseArriveByQuery(query, earliest.serviceDate(), earliest.secondsFromServiceDayStart(),
				deadline.secondsFromServiceDayStart()),
			timetable, timetable.activeServiceDay(earliest.serviceDate()), overlay);
		return reversePlan(result);
	}

	private JourneyProfileRaptorPort.ReversePlan lastConnectionPlan(
		JourneyRaptorQuery query,
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		RouteTimetableRaptorPlanner.RealtimeOverlay overlay,
		JourneyRaptorQuery.LastConnection lastConnection
	) {
		LocalDate serviceDate = lastConnection.serviceDate();
		ReverseTimetableRaptorPlanner.Result result = reverse.lastConnection(
			forward.reverseLastConnectionQuery(query, serviceDate),
			timetable, timetable.activeServiceDay(serviceDate), overlay);
		return reversePlan(result);
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
				serviceInstant(result.itinerary().serviceDate(), result.latestReadyAtSeconds()),
				serviceInstant(result.itinerary().serviceDate(), result.arrivalAtDestinationSeconds()),
				result.transfersUsed(), itinerary(result.itinerary()));
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
			itinerary.realtimeDepartureTime(), itinerary.realtimeArrivalTime(), legs);
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
}
