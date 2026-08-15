package com.easysubway.route.application.service;

import com.easysubway.journey.application.ActiveJourneySnapshotPort.ActiveJourneySnapshot;
import com.easysubway.journey.application.JourneyCandidate;
import com.easysubway.journey.application.JourneyExecutionResult;
import com.easysubway.journey.application.JourneyRaptorPort;
import com.easysubway.journey.application.JourneyRealtimePort.RealtimeObservation;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.SearchRouteV2Command;
import com.easysubway.route.domain.ConstraintMode;
import com.easysubway.route.domain.ProfileWalkTimeCalculator.MobilityPreset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public final class JourneyRaptorAdapter implements JourneyRaptorPort {

	private final RouteTimetableRaptorPlanner planner = new RouteTimetableRaptorPlanner();

	@Override
	public PlanResult plan(
		JourneyRequest request,
		ActiveJourneySnapshot snapshot,
		Instant effectiveInstant,
		RealtimeObservation realtimeOrNull
	) {
		JourneyRequest requiredRequest = Objects.requireNonNull(request, "request");
		ActiveJourneySnapshot requiredSnapshot = Objects.requireNonNull(snapshot, "snapshot");
		Instant requiredEffectiveInstant = Objects.requireNonNull(effectiveInstant, "effectiveInstant");
		if (requiredRequest.isCancelled()) throw new IllegalStateException("Journey planning was cancelled");

		RaptorRouteBundleRuntimeView routeRuntime = requireRouteRuntime(requiredSnapshot);
		RouteTimetableRaptorPlanner.RealtimeOverlay realtimeOverlay = requireRealtimeOverlay(
			requiredRequest, requiredSnapshot, routeRuntime, realtimeOrNull);
		List<RouteTimetableRaptorPlanner.JourneyItinerary> itineraries = planner.journeyItineraries(
			toCommand(requiredRequest, requiredEffectiveInstant),
			routeRuntime.compiledTimetable(),
			realtimeOverlay
		);
		if (requiredRequest.isCancelled()) throw new IllegalStateException("Journey planning was cancelled");
		List<RouteTimetableRaptorPlanner.JourneyItinerary> accessibleItineraries = itineraries.stream()
			.filter(itinerary -> hasVerifiedAccessibility(requiredRequest, itinerary))
			.toList();
		if (accessibleItineraries.isEmpty()) {
			return new PlanResult(requiredRequest.requestId(), List.of());
		}

		List<JourneyCandidate> candidates = accessibleItineraries.stream()
			.map(itinerary -> toCandidate(requiredRequest, requiredEffectiveInstant, itinerary))
			.toList();
		if (new HashSet<>(candidates.stream().map(JourneyCandidate::journeyId).toList()).size()
			!= candidates.size()) {
			throw new IllegalArgumentException("RAPTOR returned duplicate Journey paths");
		}
		return new PlanResult(requiredRequest.requestId(), candidates);
	}

	static SearchRouteV2Command toCommand(JourneyRequest request, Instant effectiveInstant) {
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(effectiveInstant, "effectiveInstant");
		MobilityType mobilityType = switch (request.mobilityProfile()) {
			case STANDARD, NO_STAIRS -> MobilityType.LUGGAGE;
			case SLOW -> MobilityType.SENIOR;
			case STEP_FREE -> MobilityType.WHEELCHAIR;
		};
		MobilityPreset mobilityPreset = switch (request.mobilityProfile()) {
			case STANDARD -> MobilityPreset.STANDARD;
			case SLOW -> MobilityPreset.SLOW;
			case NO_STAIRS -> MobilityPreset.NO_STAIRS;
			case STEP_FREE -> MobilityPreset.STEP_FREE;
		};
		ConstraintMode constraintMode = request.constraintMode() == JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE
			? ConstraintMode.STRICT_STEP_FREE
			: request.mobilityProfile() == JourneyRequest.MobilityProfile.STEP_FREE
				? ConstraintMode.PREFER_STEP_FREE
				: ConstraintMode.ALLOW_WITH_WARNINGS;
		return new SearchRouteV2Command(
			request.originStationId(),
			request.destinationStationId(),
			effectiveInstant.atOffset(ZoneOffset.UTC),
			mobilityType,
			mobilityPreset,
			constraintMode,
			request.timePolicy() == JourneyRequest.TimePolicy.REALTIME_REQUIRED,
			request.maxTransfers(),
			request.alternativeCount(),
			request.walkingPace().speedMetersPerHour()
		);
	}

	private static RaptorRouteBundleRuntimeView requireRouteRuntime(ActiveJourneySnapshot snapshot) {
		if (!(snapshot.runtimeView() instanceof RaptorRouteBundleRuntimeView runtime)) {
			throw new IllegalArgumentException("unsupported Journey RAPTOR runtime view");
		}
		if (!snapshot.routeBundleSha256().equals(runtime.routeBundleSha256())
			|| snapshot.generation() != runtime.generation()) {
			throw new IllegalArgumentException("Journey RAPTOR runtime view does not match snapshot");
		}
		return runtime;
	}

	private static RouteTimetableRaptorPlanner.RealtimeOverlay requireRealtimeOverlay(
		JourneyRequest request,
		ActiveJourneySnapshot snapshot,
		RaptorRouteBundleRuntimeView routeRuntime,
		RealtimeObservation realtimeOrNull
	) {
		if (request.timePolicy() == JourneyRequest.TimePolicy.TIMETABLE_REQUIRED) {
			if (realtimeOrNull != null) {
				throw new IllegalArgumentException("timetable Journey request must not receive realtime");
			}
			return RouteTimetableRaptorPlanner.RealtimeOverlay.empty();
		}
		if (realtimeOrNull == null
			|| !(realtimeOrNull.runtimeView() instanceof RaptorRealtimeRuntimeView realtimeRuntime)
			|| realtimeRuntime.routeRuntimeView() != routeRuntime
			|| !realtimeOrNull.identity().equals(realtimeRuntime.identity())
			|| !snapshot.routeBundleSha256().equals(realtimeRuntime.routeBundleSha256())
			|| snapshot.generation() != realtimeRuntime.generation()) {
			throw new IllegalArgumentException("realtime runtime view does not match captured Journey generation");
		}
		return realtimeRuntime.realtimeOverlay();
	}

	private static boolean hasVerifiedAccessibility(
		JourneyRequest request,
		RouteTimetableRaptorPlanner.JourneyItinerary itinerary
	) {
		for (RouteTimetableRaptorPlanner.JourneyLegProjection projection : itinerary.legs()) {
			if (projection instanceof RouteTimetableRaptorPlanner.JourneyAccessProjection access
				&& (!access.verified()
					|| request.constraintMode() == JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE
						&& access.includesStairs())) {
				return false;
			}
		}
		return true;
	}

	private static JourneyCandidate toCandidate(
		JourneyRequest request,
		Instant effectiveInstant,
		RouteTimetableRaptorPlanner.JourneyItinerary itinerary
	) {
		requireLegOrder(itinerary);
		boolean realtime = request.timePolicy() == JourneyRequest.TimePolicy.REALTIME_REQUIRED;
		List<JourneyCandidate.Leg> legs = new ArrayList<>(itinerary.legs().size());
		long walkingDistanceMeters = 0;
		int transferCount = 0;
		boolean stairFree = true;
		int rideCount = 0;
		for (RouteTimetableRaptorPlanner.JourneyLegProjection projection : itinerary.legs()) {
			if (projection instanceof RouteTimetableRaptorPlanner.JourneyAccessProjection access) {
				if (!access.verified()) {
					throw new IllegalArgumentException("Journey accessibility transition is not verified");
				}
				if (request.constraintMode() == JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE
					&& access.includesStairs()) {
					throw new IllegalArgumentException("Journey accessibility transition includes stairs");
				}
				stairFree &= !access.includesStairs();
				walkingDistanceMeters = Math.addExact(walkingDistanceMeters, access.distanceMeters());
				switch (access.kind()) {
					case ENTRY -> legs.add(new JourneyCandidate.Entry(
						access.fromStationId(), access.durationSeconds()));
					case TRANSFER -> {
						transferCount = Math.addExact(transferCount, 1);
						legs.add(new JourneyCandidate.Transfer(
							access.fromStationId(), access.toStationId(), access.durationSeconds()));
					}
					case EXIT -> legs.add(new JourneyCandidate.Exit(
						access.fromStationId(), access.durationSeconds()));
				}
				continue;
			}
			RouteTimetableRaptorPlanner.JourneyRideProjection ride =
				(RouteTimetableRaptorPlanner.JourneyRideProjection) projection;
			rideCount = Math.addExact(rideCount, 1);
			boolean completeRealtimePair = ride.realtimeDepartureTime() != null
				&& ride.realtimeArrivalTime() != null;
			if (completeRealtimePair != realtime) {
				throw new IllegalArgumentException("Journey realtime overlay is incomplete for selected ride");
			}
			legs.add(new JourneyCandidate.Ride(
				ride.lineId(),
				ride.tripId(),
				ride.directionStationId(),
				ride.fromStationId(),
				ride.toStationId(),
				ride.plannedDepartureTime(),
				ride.plannedArrivalTime(),
				ride.realtimeDepartureTime(),
				ride.realtimeArrivalTime()
			));
		}
		if (rideCount == 0 || transferCount != rideCount - 1) {
			throw new IllegalArgumentException("Journey RAPTOR leg order is invalid");
		}
		if ((itinerary.realtimeDepartureTime() != null) != realtime
			|| (itinerary.realtimeArrivalTime() != null) != realtime) {
			throw new IllegalArgumentException("Journey realtime interval is incomplete");
		}
		Instant plannedDeparture = effectiveInstant;
		Instant realtimeDeparture = realtime ? effectiveInstant : null;
		return new JourneyCandidate(
			journeyId(request, plannedDeparture, itinerary),
			plannedDeparture,
			itinerary.plannedArrivalTime(),
			realtimeDeparture,
			itinerary.realtimeArrivalTime(),
			Duration.between(plannedDeparture, itinerary.plannedArrivalTime()).toSeconds(),
			transferCount,
			walkingDistanceMeters,
			realtime ? JourneyCandidate.TimeSource.REALTIME : JourneyCandidate.TimeSource.TIMETABLE,
			new JourneyCandidate.Accessibility(stairFree, List.of("ACCESSIBILITY_VERIFIED")),
			legs
		);
	}

	private static void requireLegOrder(RouteTimetableRaptorPlanner.JourneyItinerary itinerary) {
		List<RouteTimetableRaptorPlanner.JourneyLegProjection> projections = itinerary.legs();
		if (projections.size() < 3
			|| !(projections.getFirst() instanceof RouteTimetableRaptorPlanner.JourneyAccessProjection entry)
			|| entry.kind() != RouteTimetableRaptorPlanner.JourneyAccessKind.ENTRY
			|| !(projections.getLast() instanceof RouteTimetableRaptorPlanner.JourneyAccessProjection exit)
			|| exit.kind() != RouteTimetableRaptorPlanner.JourneyAccessKind.EXIT) {
			throw new IllegalArgumentException("Journey RAPTOR leg order is invalid");
		}
		boolean expectRide = true;
		for (int index = 1; index < projections.size() - 1; index += 1) {
			var projection = projections.get(index);
			if (expectRide && projection instanceof RouteTimetableRaptorPlanner.JourneyRideProjection) {
				expectRide = false;
			} else if (!expectRide
				&& projection instanceof RouteTimetableRaptorPlanner.JourneyAccessProjection transfer
				&& transfer.kind() == RouteTimetableRaptorPlanner.JourneyAccessKind.TRANSFER) {
				expectRide = true;
			} else {
				throw new IllegalArgumentException("Journey RAPTOR leg order is invalid");
			}
		}
		if (expectRide) throw new IllegalArgumentException("Journey RAPTOR leg order is invalid");
	}

	private static String journeyId(
		JourneyRequest request,
		Instant plannedDeparture,
		RouteTimetableRaptorPlanner.JourneyItinerary itinerary
	) {
		StringBuilder canonical = new StringBuilder();
		appendCanonical(canonical,
			plannedDeparture.atZone(JourneyExecutionResult.SERVICE_ZONE).toLocalDate().toString());
		appendCanonical(canonical, request.originStationId());
		appendCanonical(canonical, request.destinationStationId());
		appendCanonical(canonical, plannedDeparture.toString());
		appendCanonical(canonical, itinerary.plannedArrivalTime().toString());
		for (RouteTimetableRaptorPlanner.JourneyLegProjection projection : itinerary.legs()) {
			if (projection instanceof RouteTimetableRaptorPlanner.JourneyAccessProjection access) {
				appendCanonical(canonical, access.kind().name());
				appendCanonical(canonical, access.fromStationId());
				appendCanonical(canonical, access.toStationId());
				appendCanonical(canonical, Integer.toString(access.durationSeconds()));
				appendCanonical(canonical, Integer.toString(access.distanceMeters()));
			} else {
				var ride = (RouteTimetableRaptorPlanner.JourneyRideProjection) projection;
				appendCanonical(canonical, "RIDE");
				appendCanonical(canonical, ride.tripId());
				appendCanonical(canonical, ride.lineId());
				appendCanonical(canonical, ride.directionStationId());
				appendCanonical(canonical, ride.fromStationId());
				appendCanonical(canonical, ride.toStationId());
				appendCanonical(canonical, ride.plannedDepartureTime().toString());
				appendCanonical(canonical, ride.plannedArrivalTime().toString());
			}
		}
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static void appendCanonical(StringBuilder target, String value) {
		Objects.requireNonNull(value, "canonical value");
		target.append(value.length()).append(':').append(value);
	}
}
