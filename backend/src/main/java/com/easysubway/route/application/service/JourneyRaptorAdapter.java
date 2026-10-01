package com.easysubway.route.application.service;

import com.easysubway.journey.application.ActiveJourneySnapshotPort;
import com.easysubway.journey.application.ActiveJourneySnapshotPort.ActiveJourneySnapshot;
import com.easysubway.journey.application.JourneyCandidate;
import com.easysubway.journey.application.JourneyExecutionResult;
import com.easysubway.journey.application.JourneyRaptorPort;
import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRealtimePort.RealtimeObservation;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.application.JourneyRequestMeasurement;
import com.easysubway.journey.application.ServiceDayResolver;
import com.easysubway.journey.application.JourneyProfileRaptorPort;
import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.ScanWorkspacePool;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import com.easysubway.journey.application.FacilityAvailabilityPort;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class JourneyRaptorAdapter implements JourneyRaptorPort {

	private final RouteTimetableRaptorPlanner planner;
	private final JourneyFacilityBlockOverlay facilityBlocks;

	public JourneyRaptorAdapter() {
		this(ScanWorkspacePool.shared(), FacilityAvailabilityPort.unavailable(), false, Clock.systemUTC());
	}

	public JourneyRaptorAdapter(ScanWorkspacePool workspacePool) {
		this(workspacePool, FacilityAvailabilityPort.unavailable(), false, Clock.systemUTC());
	}

	public JourneyRaptorAdapter(
		ScanWorkspacePool workspacePool,
		FacilityAvailabilityPort facilityAvailabilityPort,
		boolean facilityStatusRequired,
		Clock clock
	) {
		this.planner = new RouteTimetableRaptorPlanner(Objects.requireNonNull(workspacePool, "workspacePool"));
		this.facilityBlocks = new JourneyFacilityBlockOverlay(facilityAvailabilityPort, facilityStatusRequired, clock);
	}

	public JourneyRaptorAdapter(FacilityAvailabilityPort facilityAvailabilityPort, boolean facilityStatusRequired, Clock clock) {
		this(ScanWorkspacePool.shared(), facilityAvailabilityPort, facilityStatusRequired, clock);
	}

	@Override
	public PlanResult plan(
		JourneyRequest request,
		ActiveJourneySnapshot snapshot,
		Instant effectiveInstant,
		RealtimeObservation realtimeOrNull,
		JourneyRequestMeasurement requestMeasurement
	) {
		JourneyRequest requiredRequest = Objects.requireNonNull(request, "request");
		ActiveJourneySnapshot requiredSnapshot = Objects.requireNonNull(snapshot, "snapshot");
		Instant requiredEffectiveInstant = Objects.requireNonNull(effectiveInstant, "effectiveInstant");
		JourneyRequestMeasurement requiredMeasurement = Objects.requireNonNull(requestMeasurement, "requestMeasurement");
		if (requiredRequest.isCancelled()) throw new IllegalStateException("Journey planning was cancelled");

		RaptorRouteBundleRuntimeView routeRuntime = requireRouteRuntime(requiredSnapshot);
		RouteTimetableRaptorPlanner.RealtimeOverlay facilityOverlay = facilityBlocks.capture(
			requiredRequest.constraintMode(), routeRuntime.compiledTimetable());

		if (requiredRequest.viaStationId() != null) {
			return planChainedVia(
				requiredRequest,
				requiredSnapshot,
				requiredEffectiveInstant,
				realtimeOrNull,
				requiredMeasurement,
				routeRuntime,
				facilityOverlay
			);
		}

		JourneyRaptorQuery query = JourneyRaptorQuery.from(requiredRequest, requiredEffectiveInstant);

		RouteTimetableRaptorPlanner.RealtimeOverlay realtimeOverlay = requireRealtimeOverlay(
			requiredRequest, requiredSnapshot, routeRuntime, realtimeOrNull, query, facilityOverlay);
		RouteTimetableRaptorPlanner.JourneyPlan planned = planner.journeyItineraries(
			query,
			routeRuntime.compiledTimetable(),
			realtimeOverlay,
			requiredMeasurement,
			requiredRequest.requestId(),
			requiredSnapshot.routeBundleSha256(),
			requiredSnapshot.generation()
		);
		List<RouteTimetableRaptorPlanner.JourneyItinerary> itineraries = planned.itineraries();
		if (requiredRequest.isCancelled()) throw new IllegalStateException("Journey planning was cancelled");
		if (itineraries.isEmpty()) {
			return new PlanResult(requiredRequest.requestId(), List.of(), planned.scanMetrics(),
				JourneyRaptorPort.RouteBoundaryReceipt.observed(0),
				measurementReceipt(requiredRequest, requiredSnapshot, requiredMeasurement,
					planned.measurementObservation()));
		}

		List<JourneyCandidate> candidates = itineraries.stream()
			.map(itinerary -> toCandidate(requiredRequest, requiredEffectiveInstant, itinerary, routeRuntime.officialFareQuotes()))
			.toList();
		if (new HashSet<>(candidates.stream().map(JourneyCandidate::journeyId).toList()).size()
			!= candidates.size()) {
			throw new IllegalArgumentException("RAPTOR returned duplicate Journey paths");
		}
		return new PlanResult(requiredRequest.requestId(), candidates, planned.scanMetrics(),
			JourneyRaptorPort.RouteBoundaryReceipt.observed(0),
			measurementReceipt(requiredRequest, requiredSnapshot, requiredMeasurement,
				planned.measurementObservation()));
	}

	private PlanResult planChainedVia(
		JourneyRequest requiredRequest,
		ActiveJourneySnapshot requiredSnapshot,
		Instant requiredEffectiveInstant,
		RealtimeObservation realtimeOrNull,
		JourneyRequestMeasurement requiredMeasurement,
		RaptorRouteBundleRuntimeView routeRuntime,
		RouteTimetableRaptorPlanner.RealtimeOverlay facilityOverlay
	) {
		String viaStationId = requiredRequest.viaStationId();
		var timetable = routeRuntime.compiledTimetable();

		JourneyRequest leg1Request = new JourneyRequest(
			requiredRequest.requestId(),
			requiredRequest.originStationId(),
			viaStationId,
			null,
			requiredRequest.departure(),
			requiredRequest.timePolicy(),
			requiredRequest.walkingPace(),
			requiredRequest.mobilityProfile(),
			requiredRequest.constraintMode(),
			Math.min(requiredRequest.maxTransfers(), 2),
			Math.min(requiredRequest.alternativeCount(), 2),
			requiredRequest.cancellationSignal()
		);
		JourneyRaptorQuery leg1Query = JourneyRaptorQuery.from(leg1Request, requiredEffectiveInstant);
		RouteTimetableRaptorPlanner.RealtimeOverlay realtimeOverlay1 = requireRealtimeOverlay(
			leg1Request, requiredSnapshot, routeRuntime, realtimeOrNull, leg1Query, facilityOverlay);

		RouteTimetableRaptorPlanner.JourneyPlan planned1 = planner.journeyItineraries(
			leg1Query,
			timetable,
			realtimeOverlay1,
			requiredMeasurement,
			requiredRequest.requestId(),
			requiredSnapshot.routeBundleSha256(),
			requiredSnapshot.generation()
		);

		List<RouteTimetableRaptorPlanner.JourneyItinerary> itineraries1 = planned1.itineraries();
		if (itineraries1.isEmpty()) {
			return new PlanResult(requiredRequest.requestId(), List.of(), planned1.scanMetrics(),
				JourneyRaptorPort.RouteBoundaryReceipt.observed(0),
				measurementReceipt(requiredRequest, requiredSnapshot, requiredMeasurement,
					planned1.measurementObservation()));
		}

		List<RouteTimetableRaptorPlanner.JourneyItinerary> selectedLeg1 = itineraries1.stream().limit(2).toList();

		int totalRoutes = planned1.scanMetrics().expandedRoutes();
		int totalTrips = planned1.scanMetrics().expandedTrips();
		int totalTransfers = planned1.scanMetrics().expandedTransfers();

		List<RouteTimetableRaptorPlanner.JourneyItinerary> chainedItineraries = new ArrayList<>();

		for (RouteTimetableRaptorPlanner.JourneyItinerary leg1 : selectedLeg1) {
			RouteTimetableRaptorPlanner.JourneyRideProjection lastRide1 = findLastRide(leg1);
			int leg1RideCount = countRides(leg1);
			int maxTransfers2 = requiredRequest.maxTransfers() - leg1RideCount;
			if (maxTransfers2 < 0) {
				continue;
			}

			Instant leg2ReadyAt = lastRide1.plannedArrivalTime().plusSeconds(180);

			JourneyRequest leg2Request = new JourneyRequest(
				requiredRequest.requestId(),
				viaStationId,
				requiredRequest.destinationStationId(),
				null,
				new JourneyRequest.Departure.Scheduled(leg2ReadyAt),
				requiredRequest.timePolicy(),
				requiredRequest.walkingPace(),
				requiredRequest.mobilityProfile(),
				requiredRequest.constraintMode(),
				maxTransfers2,
				requiredRequest.alternativeCount(),
				requiredRequest.cancellationSignal()
			);
			JourneyRaptorQuery leg2Query = JourneyRaptorQuery.from(leg2Request, leg2ReadyAt);
			RouteTimetableRaptorPlanner.RealtimeOverlay realtimeOverlay2 = requireRealtimeOverlay(
				leg2Request, requiredSnapshot, routeRuntime, realtimeOrNull, leg2Query, facilityOverlay);

			RouteTimetableRaptorPlanner.JourneyPlan planned2 = planner.journeyItineraries(
				leg2Query,
				timetable,
				realtimeOverlay2,
				requiredMeasurement,
				requiredRequest.requestId(),
				requiredSnapshot.routeBundleSha256(),
				requiredSnapshot.generation()
			);

			totalRoutes += planned2.scanMetrics().expandedRoutes();
			totalTrips += planned2.scanMetrics().expandedTrips();
			totalTransfers += planned2.scanMetrics().expandedTransfers();

			for (RouteTimetableRaptorPlanner.JourneyItinerary leg2 : planned2.itineraries()) {
				RouteTimetableRaptorPlanner.JourneyRideProjection firstRide2 = findFirstRide(leg2);
				RouteTimetableRaptorPlanner.JourneyItinerary chained = chainLegs(
					requiredRequest,
					timetable,
					leg1,
					leg2,
					lastRide1,
					firstRide2
				);
				if (chained != null) {
					chainedItineraries.add(chained);
				}
			}
		}

		JourneyRaptorPort.ScanMetrics combinedScanMetrics = new JourneyRaptorPort.ScanMetrics(
			totalRoutes, totalTrips, totalTransfers);

		if (chainedItineraries.isEmpty()) {
			return new PlanResult(requiredRequest.requestId(), List.of(), combinedScanMetrics,
				JourneyRaptorPort.RouteBoundaryReceipt.observed(0),
				measurementReceipt(requiredRequest, requiredSnapshot, requiredMeasurement,
					planned1.measurementObservation()));
		}

		List<JourneyCandidate> candidates = new ArrayList<>();
		for (RouteTimetableRaptorPlanner.JourneyItinerary itinerary : chainedItineraries) {
			candidates.add(toCandidate(requiredRequest, requiredEffectiveInstant, itinerary, routeRuntime.officialFareQuotes()));
		}

		candidates.sort(Comparator
			.comparing(JourneyCandidate::plannedArrivalTime)
			.thenComparingLong(JourneyCandidate::durationSeconds)
			.thenComparingInt(JourneyCandidate::transferCount));

		Map<String, JourneyCandidate> unique = new LinkedHashMap<>();
		for (JourneyCandidate c : candidates) {
			unique.putIfAbsent(c.journeyId(), c);
		}
		List<JourneyCandidate> finalCandidates = unique.values().stream()
			.limit(requiredRequest.alternativeCount())
			.toList();

		return new PlanResult(requiredRequest.requestId(), finalCandidates, combinedScanMetrics,
			JourneyRaptorPort.RouteBoundaryReceipt.observed(0),
			measurementReceipt(requiredRequest, requiredSnapshot, requiredMeasurement,
				planned1.measurementObservation()));
	}

	private static RouteTimetableRaptorPlanner.JourneyItinerary chainLegs(
		JourneyRequest request,
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		RouteTimetableRaptorPlanner.JourneyItinerary leg1,
		RouteTimetableRaptorPlanner.JourneyItinerary leg2,
		RouteTimetableRaptorPlanner.JourneyRideProjection lastRide1,
		RouteTimetableRaptorPlanner.JourneyRideProjection firstRide2
	) {
		String via = request.viaStationId();
		int station = timetable.stationIndex(via);
		long availableSlack = Duration.between(lastRide1.plannedArrivalTime(), firstRide2.plannedDepartureTime()).getSeconds();

		int duration = 0;
		int distance = 0;
		boolean includesStairs = false;
		boolean verified = true;
		String status = "VERIFIED";
		String transferType = null;
		Boolean farePenaltyApplies = null;
		Integer transferLimitMinutes = null;

		int fromLine = timetable.lineIndex(lastRide1.lineId());
		int toLine = timetable.lineIndex(firstRide2.lineId());
		if (fromLine != toLine) {
			int profileBit = accessProfileBit(request.mobilityProfile(), request.constraintMode());
			int transition = timetable.transferTransition(station, fromLine, toLine, profileBit, false);
			if (transition < 0 || !timetable.transitionVerified(transition)) {
				transition = resolveFootpathTransition(timetable.footpathsFromStation(station), fromLine, toLine, profileBit, timetable);
			}

			if (transition < 0) {
				return null;
			}

			duration = timetable.transitionDurationSeconds(transition);
			distance = timetable.transitionDistanceMeters(transition);
			includesStairs = timetable.transitionIncludesStairs(transition);
			verified = timetable.transitionVerified(transition);
			status = timetable.transitionVerificationStatus(transition);

			if (timetable.isOutOfStationTransition(transition)) {
				transferType = "OUT_OF_STATION";
				int alightSeconds = (int) Duration.between(
					lastRide1.plannedArrivalTime().atZone(JourneyExecutionResult.SERVICE_ZONE).toLocalDate().atStartOfDay(JourneyExecutionResult.SERVICE_ZONE).toInstant(),
					lastRide1.plannedArrivalTime()).getSeconds();
				int boardSeconds = (int) Duration.between(
					firstRide2.plannedDepartureTime().atZone(JourneyExecutionResult.SERVICE_ZONE).toLocalDate().atStartOfDay(JourneyExecutionResult.SERVICE_ZONE).toInstant(),
					firstRide2.plannedDepartureTime()).getSeconds();
				int elapsed = (int) availableSlack;
				int limit = RouteTimetableRaptorPlanner.getTransferLimitSeconds(alightSeconds, boardSeconds);
				boolean timeout = elapsed > limit;
				farePenaltyApplies = timeout;
				transferLimitMinutes = limit / 60;
			}
		}

		if (availableSlack < Math.max(duration, 180)) {
			return null;
		}

		RouteTimetableRaptorPlanner.JourneyAccessProjection junctionTransfer =
			new RouteTimetableRaptorPlanner.JourneyAccessProjection(
				RouteTimetableRaptorPlanner.JourneyAccessKind.TRANSFER,
				via,
				via,
				duration,
				distance,
				includesStairs,
				verified,
				status,
				transferType,
				farePenaltyApplies,
				transferLimitMinutes
			);

		List<RouteTimetableRaptorPlanner.JourneyLegProjection> combinedLegs = new ArrayList<>();
		// #454: 각 구간 여정은 승차로 시작해 승차로 끝난다. 앞 구간의 마지막 승차는 환승 안내를 붙여 다시 만든다.
		List<RouteTimetableRaptorPlanner.JourneyLegProjection> legs1 = leg1.legs();
		for (int i = 0; i < legs1.size() - 1; i++) {
			combinedLegs.add(legs1.get(i));
		}
		boolean stepFree = (request.constraintMode() == JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE);
		List<RouteTimetableRaptorPlanner.AlightingCarDoor> transferHints = timetable.selectAlightingCarDoors(
			lastRide1.toStationId(),
			lastRide1.lineId(),
			timetable.tripDirection(lastRide1.tripId()),
			true,
			stepFree
		);
		combinedLegs.add(new RouteTimetableRaptorPlanner.JourneyRideProjection(
			lastRide1.lineId(),
			lastRide1.tripId(),
			lastRide1.directionStationId(),
			lastRide1.fromStationId(),
			lastRide1.toStationId(),
			lastRide1.servicePattern(),
			lastRide1.plannedDepartureTime(),
			lastRide1.plannedArrivalTime(),
			lastRide1.realtimeDepartureTime(),
			lastRide1.realtimeArrivalTime(),
			lastRide1.stops(),
			transferHints,
			lastRide1.boardingPlatformGaps(),
			lastRide1.alightingPlatformGaps()
		));
		combinedLegs.add(junctionTransfer);
		combinedLegs.addAll(leg2.legs());

		JourneyProfileRaptorPort.ItineraryMetrics metrics =
			RouteTimetableRaptorPlanner.itineraryMetrics(combinedLegs, 0);

		boolean realtime = request.timePolicy() == JourneyRequest.TimePolicy.REALTIME_REQUIRED;
		return new RouteTimetableRaptorPlanner.JourneyItinerary(
			leg1.serviceDate(),
			leg1.plannedDepartureTime(),
			leg2.plannedArrivalTime(),
			realtime ? leg1.realtimeDepartureTime() : null,
			realtime ? leg2.realtimeArrivalTime() : null,
			metrics,
			combinedLegs
		);
	}

	static int resolveFootpathTransition(
		RouteTimetableRaptorPlanner.OutOfStationFootpath[] footpaths,
		int fromLine,
		int toLine,
		int profileBit,
		RouteTimetableRaptorPlanner.CompiledTimetable timetable
	) {
		if (footpaths == null) {
			return -1;
		}
		for (RouteTimetableRaptorPlanner.OutOfStationFootpath fp : footpaths) {
			if (fp.fromLine() == fromLine && fp.toLine() == toLine) {
				int t = timetable.selectTransition(fp.candidateTransitions(), profileBit, false, true);
				if (t >= 0) {
					return t;
				}
			}
		}
		return -1;
	}

	private static RouteTimetableRaptorPlanner.JourneyRideProjection findLastRide(
		RouteTimetableRaptorPlanner.JourneyItinerary itinerary
	) {
		return itinerary.legs().reversed().stream()
			.filter(RouteTimetableRaptorPlanner.JourneyRideProjection.class::isInstance)
			.map(RouteTimetableRaptorPlanner.JourneyRideProjection.class::cast)
			.findFirst()
			.orElseThrow();
	}

	private static RouteTimetableRaptorPlanner.JourneyRideProjection findFirstRide(
		RouteTimetableRaptorPlanner.JourneyItinerary itinerary
	) {
		return itinerary.legs().stream()
			.filter(RouteTimetableRaptorPlanner.JourneyRideProjection.class::isInstance)
			.map(RouteTimetableRaptorPlanner.JourneyRideProjection.class::cast)
			.findFirst()
			.orElseThrow();
	}

	private static int countRides(RouteTimetableRaptorPlanner.JourneyItinerary itinerary) {
		int count = 0;
		for (RouteTimetableRaptorPlanner.JourneyLegProjection leg : itinerary.legs()) {
			if (leg instanceof RouteTimetableRaptorPlanner.JourneyRideProjection) {
				count++;
			}
		}
		return count;
	}

	private static int accessProfileBit(
		JourneyRequest.MobilityProfile mobilityProfile,
		JourneyRequest.ConstraintMode constraintMode
	) {
		int index = switch (mobilityProfile) {
			case STANDARD, NO_STAIRS -> 5;
			case SLOW -> 0;
			case STEP_FREE -> 2;
		};
		int constraint = constraintMode == JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE
			? 0
			: (mobilityProfile == JourneyRequest.MobilityProfile.STEP_FREE ? 1 : 2);
		return 1 << (index * 3 + constraint);
	}

	private static JourneyRaptorPort.RouteMeasurementReceipt measurementReceipt(
		JourneyRequest request, ActiveJourneySnapshot snapshot, JourneyRequestMeasurement requestMeasurement,
		JourneyRequestMeasurement.RouteObservation observation) {
		var measurement = snapshot.measurementReceipt();
		if (measurement.status() != ActiveJourneySnapshotPort.SnapshotMeasurementReceipt.Status.OBSERVED) {
			requestMeasurement.markUnobservable();
			return JourneyRaptorPort.RouteMeasurementReceipt.unobservable();
		}
		var identity = measurement.identity();
		if (!request.requestId().equals(identity.requestId())
			|| !snapshot.routeBundleSha256().equals(identity.routeBundleSha256())
			|| snapshot.generation() != identity.generation()) {
			requestMeasurement.markUnobservable();
			return JourneyRaptorPort.RouteMeasurementReceipt.unobservable();
		}
		return observation == null ? JourneyRaptorPort.RouteMeasurementReceipt.unobservable()
			: JourneyRaptorPort.RouteMeasurementReceipt.observed(observation);
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
		RealtimeObservation realtimeOrNull,
		JourneyRaptorQuery query,
		RouteTimetableRaptorPlanner.RealtimeOverlay facilityOverlay
	) {
		if (request.timePolicy() == JourneyRequest.TimePolicy.TIMETABLE_REQUIRED) {
			if (realtimeOrNull != null) {
				throw new IllegalArgumentException("timetable Journey request must not receive realtime");
			}
			return facilityOverlay;
		}
		if (realtimeOrNull == null
			|| !(realtimeOrNull.runtimeView() instanceof RaptorRealtimeRuntimeView realtimeRuntime)
			|| realtimeRuntime.routeRuntimeView() != routeRuntime
			|| !realtimeOrNull.identity().equals(realtimeRuntime.identity())
			|| !snapshot.routeBundleSha256().equals(realtimeRuntime.routeBundleSha256())
			|| snapshot.generation() != realtimeRuntime.generation()) {
			throw new IllegalArgumentException("realtime runtime view does not match captured Journey generation");
		}
		RouteTimetableRaptorPlanner.RealtimeOverlay trainOverlay = realtimeRuntime.realtimeOverlay(serviceDate(query));
		return RouteTimetableRaptorPlanner.RealtimeOverlay.combine(trainOverlay, facilityOverlay);
	}

	private static java.time.LocalDate serviceDate(JourneyRaptorQuery query) {
		if (!(query.temporalQuery() instanceof JourneyRaptorQuery.DepartAt departure)) {
			throw new IllegalArgumentException("Journey realtime requires a point query");
		}
		return ServiceDayResolver.resolve(departure.readyAt()).serviceDate();
	}

	private static JourneyCandidate toCandidate(
		JourneyRequest request,
		Instant effectiveInstant,
		RouteTimetableRaptorPlanner.JourneyItinerary itinerary,
		Map<String, OfficialFareQuote> fareQuotes
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
				// #454: 이동 구간은 승차 사이 환승뿐이다. 진입·하차(Entry/Exit) 구간은 만들지 않는다.
				transferCount = Math.addExact(transferCount, 1);
				legs.add(new JourneyCandidate.Transfer(
					access.fromStationId(),
					access.toStationId(),
					access.durationSeconds(),
					access.transferType(),
					access.farePenaltyApplies(),
					access.transferLimitMinutes()
				));
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
				ride.servicePattern(),
				ride.plannedDepartureTime(),
				ride.plannedArrivalTime(),
				ride.realtimeDepartureTime(),
				ride.realtimeArrivalTime(),
				ride.stops().stream().map(stop -> new JourneyCandidate.Stop(
					stop.stationId(),
					stop.plannedArrivalTime(),
					stop.plannedDepartureTime(),
					stop.realtimeArrivalTime(),
					stop.realtimeDepartureTime()
				)).toList(),
				ride.alightingCarDoors().stream()
					.map(d -> new JourneyCandidate.AlightingCarDoor(d.carNumber(), d.doorNumber(), d.targetFacilityType()))
					.toList(),
				ride.boardingPlatformGaps(),
				ride.alightingPlatformGaps()
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
		JourneyCandidate.Fare fare = calculateFare(itinerary, fareQuotes);
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
			fare,
			legs
		);
	}

	/**
	 * 재승차가 없으면 첫 승차역 -> 최종 하차역 공식 O-D 운임 한 건만 조회한다(#430).
	 * 역 밖 환승 제한 시간을 넘긴 환승(farePenaltyApplies == true)이 있으면 그 지점에서 여정을 승차 구간으로 나누고,
	 * 구간마다 공식 O-D 운임을 조회해 합한다(#444). 어느 구간이라도 표에 없으면 여정 전체가 UNAVAILABLE이다.
	 * point 검색과 profile 계획이 같은 planner 투영으로 이 함수를 함께 쓴다.
	 */
	static JourneyCandidate.Fare calculateFare(
		RouteTimetableRaptorPlanner.JourneyItinerary itinerary,
		Map<String, OfficialFareQuote> fareQuotes
	) {
		Objects.requireNonNull(fareQuotes, "fareQuotes");
		List<String> sectionKeys = new ArrayList<>();
		RouteTimetableRaptorPlanner.JourneyRideProjection sectionFirstRide = null;
		RouteTimetableRaptorPlanner.JourneyRideProjection sectionLastRide = null;
		for (RouteTimetableRaptorPlanner.JourneyLegProjection leg : itinerary.legs()) {
			if (leg instanceof RouteTimetableRaptorPlanner.JourneyRideProjection ride) {
				if (sectionFirstRide == null) sectionFirstRide = ride;
				sectionLastRide = ride;
			} else if (Boolean.TRUE.equals(
				((RouteTimetableRaptorPlanner.JourneyAccessProjection) leg).farePenaltyApplies())) {
				// planner는 탑승 사이 TRANSFER 구간에만 재승차 여부를 싣는다.
				if (sectionFirstRide == null) {
					throw new IllegalArgumentException("Journey re-boarding transfer must follow a ride");
				}
				sectionKeys.add(OfficialFareQuote.fareKey(sectionFirstRide.fromStationId(), sectionLastRide.toStationId()));
				sectionFirstRide = null;
			}
		}
		if (sectionFirstRide == null) {
			throw new IllegalArgumentException("Journey itinerary must contain a ride to quote a fare");
		}
		sectionKeys.add(OfficialFareQuote.fareKey(sectionFirstRide.fromStationId(), sectionLastRide.toStationId()));

		int adultCard = 0;
		int adultCash = 0;
		int youthCard = 0;
		int youthCash = 0;
		int childCard = 0;
		int childCash = 0;
		Set<String> snapshotIds = new LinkedHashSet<>();
		for (String key : sectionKeys) {
			OfficialFareQuote quote = fareQuotes.get(key);
			if (quote == null) {
				return JourneyCandidate.Fare.unavailable();
			}
			adultCard = Math.addExact(adultCard, quote.gnrlCardFare());
			adultCash = Math.addExact(adultCash, quote.gnrlCashFare());
			youthCard = Math.addExact(youthCard, quote.yungCardFare());
			youthCash = Math.addExact(youthCash, quote.yungCashFare());
			childCard = Math.addExact(childCard, quote.childCardFare());
			childCash = Math.addExact(childCash, quote.childCashFare());
			snapshotIds.add(quote.snapshotId());
		}
		return JourneyCandidate.Fare.available(
			adultCard, adultCash, youthCard, youthCash, childCard, childCash, List.copyOf(snapshotIds));
	}

	private static void requireLegOrder(RouteTimetableRaptorPlanner.JourneyItinerary itinerary) {
		// #454: 승차(출발역 승강장) → [환승 → 승차]* (도착역 승강장). 진입·하차 구간은 없다.
		List<RouteTimetableRaptorPlanner.JourneyLegProjection> projections = itinerary.legs();
		boolean expectRide = true;
		for (var projection : projections) {
			if (expectRide && projection instanceof RouteTimetableRaptorPlanner.JourneyRideProjection) {
				expectRide = false;
			} else if (!expectRide && projection instanceof RouteTimetableRaptorPlanner.JourneyAccessProjection) {
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
