package com.easysubway.journey.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Non-wire Journey temporal-profile boundary over one captured route-bundle and realtime snapshot.
 *
 * <p>This boundary deliberately returns native temporal facts only. Candidate identifiers,
 * compression, summaries, resource-policy projection, and HTTP serialization belong to later
 * contract-owned layers.</p>
 */
public interface JourneyProfileRaptorPort {

	PlanningResult plan(
		JourneyRaptorQuery query,
		ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot,
		JourneyRealtimePort.RealtimeObservation realtimeOrNull,
		JourneyProfileResourcePolicy.ProfilePlanningLimits limits
	);

	/**
	 * Resolves only the verified native terminal event used to decide whether a realtime
	 * last-connection request is within the configured future horizon. This does not plan a route.
	 */
	LastConnectionPreparation prepareLastConnection(
		JourneyRaptorQuery query,
		ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot,
		JourneyProfileResourcePolicy.ProfilePlanningLimits limits
	);

	sealed interface LastConnectionPreparation permits LastConnectionPreparation.Prepared,
		LastConnectionPreparation.AdmissionRejected, LastConnectionPreparation.CapacityExceeded {
		JourneyRaptorPruningInventoryV1.CountSnapshot countSnapshot();
		PlanningMetrics planningMetrics();

		record Prepared(
			Terminal terminal,
			Instant terminalArrivalAtDestination,
			JourneyRaptorPruningInventoryV1.CountSnapshot countSnapshot,
			PlanningMetrics planningMetrics
		) implements LastConnectionPreparation {
			public Prepared {
				terminal = Objects.requireNonNull(terminal, "terminal");
				if (terminal instanceof Terminal.Found && terminalArrivalAtDestination == null
					|| terminal instanceof Terminal.NotFound && terminalArrivalAtDestination != null) {
					throw new IllegalArgumentException("terminal arrival must match terminal outcome");
				}
				countSnapshot = Objects.requireNonNull(countSnapshot, "countSnapshot");
				planningMetrics = Objects.requireNonNull(planningMetrics, "planningMetrics");
			}
		}

		record AdmissionRejected(
			long observed,
			long max,
			JourneyRaptorPruningInventoryV1.CountSnapshot countSnapshot,
			PlanningMetrics planningMetrics
		) implements LastConnectionPreparation {
			public AdmissionRejected {
				if (observed < 0 || max < 1 || observed <= max) {
					throw new IllegalArgumentException("admission rejection must exceed a positive maximum");
				}
				countSnapshot = Objects.requireNonNull(countSnapshot, "countSnapshot");
				planningMetrics = Objects.requireNonNull(planningMetrics, "planningMetrics");
			}
		}

		record CapacityExceeded(
			PlanningCapacity dimension,
			long observed,
			long max,
			JourneyRaptorPruningInventoryV1.CountSnapshot countSnapshot,
			PlanningMetrics planningMetrics
		) implements LastConnectionPreparation {
			public CapacityExceeded {
				dimension = Objects.requireNonNull(dimension, "dimension");
				if (observed < 0 || max < 1 || observed <= max) {
					throw new IllegalArgumentException("capacity exceedance must exceed a positive maximum");
				}
				countSnapshot = Objects.requireNonNull(countSnapshot, "countSnapshot");
				planningMetrics = Objects.requireNonNull(planningMetrics, "planningMetrics");
			}
		}
	}

	sealed interface Terminal permits Terminal.Found, Terminal.NotFound {
		record Found() implements Terminal {
		}

		record NotFound(ReversePlan.Outcome outcome) implements Terminal {
			public NotFound {
				outcome = Objects.requireNonNull(outcome, "outcome");
			}
		}
	}

	sealed interface PlanningResult permits PlanningResult.Planned, PlanningResult.AdmissionRejected,
		PlanningResult.CapacityExceeded {
		JourneyRaptorPruningInventoryV1.CountSnapshot countSnapshot();
		PlanningMetrics planningMetrics();

		record Planned(
			TemporalPlan temporalPlan,
			JourneyRaptorPruningInventoryV1.CountSnapshot countSnapshot,
			PlanningMetrics planningMetrics
		) implements PlanningResult {
			public Planned {
				temporalPlan = Objects.requireNonNull(temporalPlan, "temporalPlan");
				countSnapshot = Objects.requireNonNull(countSnapshot, "countSnapshot");
				planningMetrics = Objects.requireNonNull(planningMetrics, "planningMetrics");
			}
		}

		record AdmissionRejected(
			long observed,
			long max,
			JourneyRaptorPruningInventoryV1.CountSnapshot countSnapshot,
			PlanningMetrics planningMetrics
		) implements PlanningResult {
			public AdmissionRejected {
				if (observed < 0 || max < 1 || observed <= max) {
					throw new IllegalArgumentException("admission rejection must exceed a positive maximum");
				}
				countSnapshot = Objects.requireNonNull(countSnapshot, "countSnapshot");
				planningMetrics = Objects.requireNonNull(planningMetrics, "planningMetrics");
			}
		}

		record CapacityExceeded(PlanningCapacity dimension, long observed, long max,
			JourneyRaptorPruningInventoryV1.CountSnapshot countSnapshot,
			PlanningMetrics planningMetrics) implements PlanningResult {
			public CapacityExceeded {
				dimension = Objects.requireNonNull(dimension, "dimension");
				if (observed < 0 || max < 1 || observed <= max) {
					throw new IllegalArgumentException("capacity exceedance must exceed a positive maximum");
				}
				countSnapshot = Objects.requireNonNull(countSnapshot, "countSnapshot");
				planningMetrics = Objects.requireNonNull(planningMetrics, "planningMetrics");
			}
		}
	}

	record PlanningMetrics(
		long workConsumed,
		long peakStateLabels,
		long peakDestinationLabels,
		long reservedProfileBreakpoints
	) {
		public PlanningMetrics {
			if (workConsumed < 0 || peakStateLabels < 0 || peakDestinationLabels < 0
				|| reservedProfileBreakpoints < 0) {
				throw new IllegalArgumentException("planning metrics must not be negative");
			}
		}
	}

	enum PlanningCapacity {
		MAX_LABELS_PER_STATE,
		MAX_DESTINATION_PROFILE_LABELS,
		MAX_PROFILE_BREAKPOINTS
	}

	sealed interface TemporalPlan permits DepartureWindowPlan, ArriveByPlan, LastConnectionPlan {
		JourneyRaptorQuery.TemporalQuery temporalQuery();
	}

	record DepartureWindowPlan(
		JourneyRaptorQuery.DepartBetween temporalQuery,
		List<DeparturePoint> points
	) implements TemporalPlan {
		public DepartureWindowPlan {
			temporalQuery = Objects.requireNonNull(temporalQuery, "temporalQuery");
			points = List.copyOf(Objects.requireNonNull(points, "points"));
		}
	}

	record ArriveByPlan(
		JourneyRaptorQuery.ArriveBy temporalQuery,
		ReversePlan result
	) implements TemporalPlan {
		public ArriveByPlan {
			temporalQuery = Objects.requireNonNull(temporalQuery, "temporalQuery");
			result = Objects.requireNonNull(result, "result");
		}
	}

	record LastConnectionPlan(
		JourneyRaptorQuery.LastConnection temporalQuery,
		ReversePlan result,
		Instant terminalArrivalAtDestination
	) implements TemporalPlan {
		public LastConnectionPlan {
			temporalQuery = Objects.requireNonNull(temporalQuery, "temporalQuery");
			result = Objects.requireNonNull(result, "result");
		}
	}

	record DeparturePoint(
		LocalDate serviceDate,
		Instant readyAt,
		List<Itinerary> itineraries,
		JourneyRaptorPort.ScanMetrics scanMetrics
	) {
		public DeparturePoint {
			serviceDate = Objects.requireNonNull(serviceDate, "serviceDate");
			readyAt = Objects.requireNonNull(readyAt, "readyAt");
			itineraries = List.copyOf(Objects.requireNonNull(itineraries, "itineraries"));
			scanMetrics = Objects.requireNonNull(scanMetrics, "scanMetrics");
		}
	}

	sealed interface ReversePlan permits ReversePlan.Found, ReversePlan.NotFound {
		enum Outcome {
			NO_ACTIVE_SERVICE,
			DEADLINE_MISS,
			NO_OD_CONNECTION,
			CANCELLED
		}

		record Found(
			List<Itinerary> itineraries
		) implements ReversePlan {
			public Found {
				itineraries = List.copyOf(Objects.requireNonNull(itineraries, "itineraries"));
				if (itineraries.isEmpty()) throw new IllegalArgumentException("reverse frontier must not be empty");
			}
		}

		record NotFound(Outcome outcome) implements ReversePlan {
			public NotFound {
				outcome = Objects.requireNonNull(outcome, "outcome");
			}
		}
	}

	record Itinerary(
		LocalDate serviceDate,
		Instant plannedReadyAt,
		Instant plannedArrivalAtDestination,
		Instant realtimeReadyAt,
		Instant realtimeArrivalAtDestination,
		ItineraryMetrics metrics,
		JourneyCandidate.Fare fare,
		List<Leg> legs
	) {
		public Itinerary {
			serviceDate = Objects.requireNonNull(serviceDate, "serviceDate");
			plannedReadyAt = Objects.requireNonNull(plannedReadyAt, "plannedReadyAt");
			plannedArrivalAtDestination = Objects.requireNonNull(
				plannedArrivalAtDestination, "plannedArrivalAtDestination");
			if (plannedArrivalAtDestination.isBefore(plannedReadyAt)) {
				throw new IllegalArgumentException("planned itinerary times must be ordered");
			}
			if ((realtimeReadyAt == null) != (realtimeArrivalAtDestination == null)) {
				throw new IllegalArgumentException("realtime itinerary times must be a pair");
			}
			if (realtimeReadyAt != null && realtimeArrivalAtDestination.isBefore(realtimeReadyAt)) {
				throw new IllegalArgumentException("realtime itinerary times must be ordered");
			}
			metrics = Objects.requireNonNull(metrics, "metrics");
			fare = Objects.requireNonNull(fare, "fare");
			legs = List.copyOf(Objects.requireNonNull(legs, "legs"));
			if (legs.isEmpty()) throw new IllegalArgumentException("itinerary legs must not be empty");
		}
	}

	record ItineraryMetrics(
		int transfersUsed,
		long accessMovementSeconds,
		long accessDistanceMeters,
		long accessibilityBurden,
		ConnectionSlack connectionSlack
	) {
		public ItineraryMetrics {
			if (transfersUsed < 0 || accessMovementSeconds < 0 || accessDistanceMeters < 0
				|| accessibilityBurden < 0) {
				throw new IllegalArgumentException("itinerary metrics must not be negative");
			}
			connectionSlack = Objects.requireNonNull(connectionSlack, "connectionSlack");
			if (transfersUsed == 0 && !(connectionSlack instanceof NoTransfer)
				|| transfersUsed > 0 && !(connectionSlack instanceof MinimumTransferSeconds)) {
				throw new IllegalArgumentException("connection slack must match transfer count");
			}
		}
	}

	sealed interface ConnectionSlack permits NoTransfer, MinimumTransferSeconds {
		/** Returns a positive value when {@code left} is safer than {@code right}. */
		static int compareSafety(ConnectionSlack left, ConnectionSlack right) {
			ConnectionSlack requiredLeft = Objects.requireNonNull(left, "left");
			ConnectionSlack requiredRight = Objects.requireNonNull(right, "right");
			if (requiredLeft instanceof NoTransfer) return requiredRight instanceof NoTransfer ? 0 : 1;
			if (requiredRight instanceof NoTransfer) return -1;
			return Long.compare(((MinimumTransferSeconds) requiredLeft).seconds(),
				((MinimumTransferSeconds) requiredRight).seconds());
		}
	}

	record NoTransfer() implements ConnectionSlack {
	}

	record MinimumTransferSeconds(long seconds) implements ConnectionSlack {
		public MinimumTransferSeconds {
			if (seconds < 0) throw new IllegalArgumentException("minimum transfer slack must not be negative");
		}
	}

	sealed interface Leg permits AccessLeg, RideLeg {
	}

	/** #454: 승강장 기준 여정의 이동 구간은 승차 사이의 환승뿐이다. 진입·하차 구간은 없다. */
	enum AccessKind {
		TRANSFER
	}

	record AccessLeg(
		AccessKind kind,
		String fromStationId,
		String toStationId,
		int durationSeconds,
		int distanceMeters,
		boolean includesStairs,
		boolean verified,
		String verificationStatus,
		String transferType,
		Boolean farePenaltyApplies,
		Integer transferLimitMinutes,
		boolean stairAccessUnconfirmed
	) implements Leg {
		public AccessLeg {
			kind = Objects.requireNonNull(kind, "kind");
			if (stairAccessUnconfirmed && !includesStairs) {
				throw new IllegalArgumentException("unconfirmed stair access must be reported as includesStairs");
			}
			fromStationId = requireText(fromStationId, "fromStationId");
			toStationId = requireText(toStationId, "toStationId");
			if (durationSeconds < 0 || distanceMeters < 0) {
				throw new IllegalArgumentException("access duration and distance must not be negative");
			}
			verificationStatus = requireText(verificationStatus, "verificationStatus");
		}

		/** 계단 접근 상태를 확정으로 다루는 생성자(계단 없음 확정 또는 계단 확정). 미확정 동선은 마지막 인자로 명시한다. */
		public AccessLeg(
			AccessKind kind,
			String fromStationId,
			String toStationId,
			int durationSeconds,
			int distanceMeters,
			boolean includesStairs,
			boolean verified,
			String verificationStatus,
			String transferType,
			Boolean farePenaltyApplies,
			Integer transferLimitMinutes
		) {
			this(kind, fromStationId, toStationId, durationSeconds, distanceMeters, includesStairs, verified,
				verificationStatus, transferType, farePenaltyApplies, transferLimitMinutes, false);
		}

		public AccessLeg(
			AccessKind kind,
			String fromStationId,
			String toStationId,
			int durationSeconds,
			int distanceMeters,
			boolean includesStairs,
			boolean verified,
			String verificationStatus
		) {
			this(kind, fromStationId, toStationId, durationSeconds, distanceMeters, includesStairs, verified,
				verificationStatus, null, null, null, false);
		}
	}

	record RideLeg(
		String lineId,
		String tripId,
		String directionStationId,
		String fromStationId,
		String toStationId,
		String servicePattern,
		Instant plannedDepartureTime,
		Instant plannedArrivalTime,
		Instant realtimeDepartureTime,
		Instant realtimeArrivalTime,
		List<JourneyCandidate.Stop> stops
	) implements Leg {
		public RideLeg {
			lineId = requireText(lineId, "lineId");
			tripId = requireText(tripId, "tripId");
			directionStationId = requireText(directionStationId, "directionStationId");
			fromStationId = requireText(fromStationId, "fromStationId");
			toStationId = requireText(toStationId, "toStationId");
			servicePattern = requireText(servicePattern, "servicePattern");
			stops = List.copyOf(Objects.requireNonNull(stops, "stops"));
			plannedDepartureTime = Objects.requireNonNull(plannedDepartureTime, "plannedDepartureTime");
			plannedArrivalTime = Objects.requireNonNull(plannedArrivalTime, "plannedArrivalTime");
			if (plannedArrivalTime.isBefore(plannedDepartureTime)) {
				throw new IllegalArgumentException("planned ride times must be ordered");
			}
			if ((realtimeDepartureTime == null) != (realtimeArrivalTime == null)) {
				throw new IllegalArgumentException("realtime ride times must be a pair");
			}
			if (realtimeDepartureTime != null && realtimeArrivalTime.isBefore(realtimeDepartureTime)) {
				throw new IllegalArgumentException("realtime ride times must be ordered");
			}
		}
	}

	private static String requireText(String value, String name) {
		Objects.requireNonNull(value, name);
		if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}
}
