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
@FunctionalInterface
public interface JourneyProfileRaptorPort {

	TemporalPlan plan(
		JourneyRaptorQuery query,
		ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot,
		JourneyRealtimePort.RealtimeObservation realtimeOrNull
	);

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
			NO_VERIFIED_EXIT,
			DEADLINE_MISS,
			NO_OD_CONNECTION,
			CANCELLED
		}

		record Found(
			Instant latestReadyAt,
			Instant arrivalAtDestination,
			int transfersUsed,
			Itinerary itinerary
		) implements ReversePlan {
			public Found {
				latestReadyAt = Objects.requireNonNull(latestReadyAt, "latestReadyAt");
				arrivalAtDestination = Objects.requireNonNull(arrivalAtDestination, "arrivalAtDestination");
				if (arrivalAtDestination.isBefore(latestReadyAt)) {
					throw new IllegalArgumentException("arrivalAtDestination must not precede latestReadyAt");
				}
				if (transfersUsed < 0) throw new IllegalArgumentException("transfersUsed must not be negative");
				itinerary = Objects.requireNonNull(itinerary, "itinerary");
				if (itinerary.metrics().transfersUsed() != transfersUsed) {
					throw new IllegalArgumentException("reverse transfer count must match itinerary metrics");
				}
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

	enum AccessKind {
		ENTRY,
		TRANSFER,
		EXIT
	}

	record AccessLeg(
		AccessKind kind,
		String fromStationId,
		String toStationId,
		int durationSeconds,
		int distanceMeters,
		boolean includesStairs,
		boolean verified,
		String verificationStatus
	) implements Leg {
		public AccessLeg {
			kind = Objects.requireNonNull(kind, "kind");
			fromStationId = requireText(fromStationId, "fromStationId");
			toStationId = requireText(toStationId, "toStationId");
			if (durationSeconds < 0 || distanceMeters < 0) {
				throw new IllegalArgumentException("access duration and distance must not be negative");
			}
			verificationStatus = requireText(verificationStatus, "verificationStatus");
		}
	}

	record RideLeg(
		String lineId,
		String tripId,
		String directionStationId,
		String fromStationId,
		String toStationId,
		Instant plannedDepartureTime,
		Instant plannedArrivalTime,
		Instant realtimeDepartureTime,
		Instant realtimeArrivalTime
	) implements Leg {
		public RideLeg {
			lineId = requireText(lineId, "lineId");
			tripId = requireText(tripId, "tripId");
			directionStationId = requireText(directionStationId, "directionStationId");
			fromStationId = requireText(fromStationId, "fromStationId");
			toStationId = requireText(toStationId, "toStationId");
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
