package com.easysubway.journey.adapter.in.web;

import com.easysubway.journey.application.JourneyCandidate;
import com.easysubway.journey.application.JourneyExecutionResult;
import com.easysubway.journey.application.JourneyRequest;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

final class JourneySearchResponseMapper {

	private JourneySearchResponseMapper() {
	}

	static JourneySearchResponse map(JourneyExecutionResult.Success success) {
		Objects.requireNonNull(success, "success");
		return new JourneySearchResponse(
			success.contractVersion(),
			success.requestId(),
			success.queryId(),
			success.calculatedAt().toString(),
			success.validUntil().toString(),
			success.effectiveDepartureTime().toString(),
			success.serviceDate().toString(),
			success.serviceDayIdentity().timezone(),
			success.serviceDayIdentity().cutoffLocalTime(),
			mapSourceIdentity(success.sourceIdentity()),
			mapRequestPolicy(success.requestPolicy()),
			success.journeys().stream().map(JourneySearchResponseMapper::mapJourney).toList()
		);
	}

	private static SourceIdentityResponse mapSourceIdentity(JourneyExecutionResult.SourceIdentity source) {
		return new SourceIdentityResponse(
			source.routeBundleId(),
			source.routeBundleSha256(),
			source.timetableSnapshotId(),
			source.accessibilitySnapshotId(),
			source.realtimeSnapshotId()
		);
	}

	private static RequestPolicyResponse mapRequestPolicy(JourneyExecutionResult.RequestPolicy policy) {
		return new RequestPolicyResponse(
			wire(policy.timePolicy()),
			wire(policy.walkingPace()),
			wire(policy.mobilityProfile()),
			wire(policy.constraintMode()),
			policy.maxTransfers(),
			policy.alternativeCount()
		);
	}

	private static JourneyResponse mapJourney(JourneyCandidate journey) {
		return new JourneyResponse(
			journey.journeyId(),
			wire(journey.status()),
			wire(journey.planSource()),
			journey.plannedDepartureTime().toString(),
			journey.plannedArrivalTime().toString(),
			wire(journey.realtimeDepartureTime()),
			wire(journey.realtimeArrivalTime()),
			journey.durationSeconds(),
			journey.transferCount(),
			journey.walkingDistanceMeters(),
			wire(journey.timeSource()),
			new AccessibilityResponse(
				wire(journey.accessibility().result()),
				journey.accessibility().stairFree(),
				List.copyOf(journey.accessibility().reasonCodes())
			),
			journey.legs().stream().map(JourneySearchResponseMapper::mapLeg).toList()
		);
	}

	private static LegResponse mapLeg(JourneyCandidate.Leg leg) {
		return switch (leg) {
			case JourneyCandidate.Entry entry -> new EntryLegResponse(
				"ENTRY",
				entry.fromStationId(),
				entry.durationSeconds()
			);
			case JourneyCandidate.Ride ride -> new RideLegResponse(
				"RIDE",
				ride.lineId(),
				ride.tripId(),
				ride.directionStationId(),
				ride.fromStationId(),
				ride.toStationId(),
				ride.plannedDepartureTime().toString(),
				ride.plannedArrivalTime().toString(),
				wire(ride.realtimeDepartureTime()),
				wire(ride.realtimeArrivalTime())
			);
			case JourneyCandidate.Transfer transfer -> new TransferLegResponse(
				"TRANSFER",
				transfer.fromStationId(),
				transfer.toStationId(),
				transfer.durationSeconds()
			);
			case JourneyCandidate.Exit exit -> new ExitLegResponse(
				"EXIT",
				exit.fromStationId(),
				exit.durationSeconds()
			);
		};
	}

	private static String wire(Instant value) {
		return value == null ? null : value.toString();
	}

	private static String wire(JourneyRequest.TimePolicy value) {
		return switch (value) {
			case TIMETABLE_REQUIRED -> "TIMETABLE_REQUIRED";
			case REALTIME_REQUIRED -> "REALTIME_REQUIRED";
		};
	}

	private static String wire(JourneyRequest.MobilityProfile value) {
		return switch (value) {
			case STANDARD -> "STANDARD";
			case SLOW -> "SLOW";
			case NO_STAIRS -> "NO_STAIRS";
			case STEP_FREE -> "STEP_FREE";
		};
	}

	private static String wire(JourneyRequest.WalkingPace value) {
		return switch (value) {
			case SLOW -> "SLOW";
			case STANDARD -> "STANDARD";
			case FAST -> "FAST";
		};
	}

	private static String wire(JourneyRequest.ConstraintMode value) {
		return switch (value) {
			case NONE -> "NONE";
			case REQUIRE_STEP_FREE -> "REQUIRE_STEP_FREE";
		};
	}

	private static String wire(JourneyCandidate.Status value) {
		return switch (value) {
			case FOUND -> "FOUND";
		};
	}

	private static String wire(JourneyCandidate.PlanSource value) {
		return switch (value) {
			case SERVER_TIMETABLE_RAPTOR -> "SERVER_TIMETABLE_RAPTOR";
		};
	}

	private static String wire(JourneyCandidate.TimeSource value) {
		return switch (value) {
			case TIMETABLE -> "TIMETABLE";
			case REALTIME -> "REALTIME";
		};
	}

	private static String wire(JourneyCandidate.AccessibilityResult value) {
		return switch (value) {
			case VERIFIED -> "VERIFIED";
		};
	}

	record JourneySearchResponse(
		String contractVersion,
		String requestId,
		String queryId,
		String calculatedAt,
		String validUntil,
		String effectiveDepartureTime,
		String serviceDate,
		String serviceTimezone,
		String serviceDayCutoff,
		SourceIdentityResponse sourceIdentity,
		RequestPolicyResponse requestPolicy,
		List<JourneyResponse> journeys
	) {
	}

	record SourceIdentityResponse(
		String routeBundleId,
		String routeBundleSha256,
		String timetableSnapshotId,
		String accessibilitySnapshotId,
		String realtimeSnapshotId
	) {
	}

	record RequestPolicyResponse(
		String timePolicy,
		String walkingPace,
		String mobilityProfile,
		String constraintMode,
		int maxTransfers,
		int alternativeCount
	) {
	}

	record JourneyResponse(
		String journeyId,
		String status,
		String planSource,
		String plannedDepartureTime,
		String plannedArrivalTime,
		String realtimeDepartureTime,
		String realtimeArrivalTime,
		long durationSeconds,
		int transferCount,
		long walkingDistanceMeters,
		String timeSource,
		AccessibilityResponse accessibility,
		List<LegResponse> legs
	) {
	}

	record AccessibilityResponse(String result, boolean stairFree, List<String> reasonCodes) {
	}

	sealed interface LegResponse permits EntryLegResponse, RideLegResponse, TransferLegResponse, ExitLegResponse {
	}

	record EntryLegResponse(String type, String fromStationId, long durationSeconds) implements LegResponse {
	}

	record RideLegResponse(
		String type,
		String lineId,
		String tripId,
		String directionStationId,
		String fromStationId,
		String toStationId,
		String plannedDepartureTime,
		String plannedArrivalTime,
		String realtimeDepartureTime,
		String realtimeArrivalTime
	) implements LegResponse {
	}

	record TransferLegResponse(
		String type,
		String fromStationId,
		String toStationId,
		long durationSeconds
	) implements LegResponse {
	}

	record ExitLegResponse(String type, String fromStationId, long durationSeconds) implements LegResponse {
	}
}
