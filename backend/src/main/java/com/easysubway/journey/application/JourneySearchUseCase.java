package com.easysubway.journey.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public interface JourneySearchUseCase {

	JourneySearchResult search(JourneySearchCommand command);

	record JourneySearchCommand(
		String requestId,
		String originStationId,
		String destinationStationId,
		Departure departure,
		TimePolicy timePolicy,
		MobilityProfile mobilityProfile,
		ConstraintMode constraintMode,
		int maxTransfers,
		int alternativeCount
	) {
		private static final Pattern REQUEST_ID = Pattern.compile("[0-7][0-9A-HJKMNP-TV-Z]{25}");

		public JourneySearchCommand {
			if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
				throw new IllegalArgumentException("requestId must be a canonical ULID");
			}
			requireText(originStationId, "originStationId");
			requireText(destinationStationId, "destinationStationId");
			if (originStationId.equals(destinationStationId)) {
				throw new IllegalArgumentException("originStationId and destinationStationId must differ");
			}
			departure = Objects.requireNonNull(departure, "departure");
			timePolicy = Objects.requireNonNull(timePolicy, "timePolicy");
			mobilityProfile = Objects.requireNonNull(mobilityProfile, "mobilityProfile");
			constraintMode = Objects.requireNonNull(constraintMode, "constraintMode");
			if (mobilityProfile == MobilityProfile.NO_STAIRS && constraintMode == ConstraintMode.NONE) {
				throw new IllegalArgumentException("NO_STAIRS requires an explicit accessibility constraint");
			}
			if (maxTransfers < 0 || maxTransfers > 3) {
				throw new IllegalArgumentException("maxTransfers must be between 0 and 3");
			}
			if (alternativeCount < 1 || alternativeCount > 3) {
				throw new IllegalArgumentException("alternativeCount must be between 1 and 3");
			}
		}
	}

	sealed interface Departure permits DepartureNow, DepartureScheduled {
	}

	record DepartureNow() implements Departure {
	}

	record DepartureScheduled(OffsetDateTime requestedAt) implements Departure {
		public DepartureScheduled {
			requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
		}
	}

	enum TimePolicy {
		TIMETABLE_REQUIRED,
		REALTIME_REQUIRED
	}

	enum MobilityProfile {
		STANDARD,
		SLOW,
		NO_STAIRS,
		STEP_FREE
	}

	enum ConstraintMode {
		NONE,
		REQUIRE_STEP_FREE
	}

	enum PlanSource {
		SERVER_TIMETABLE_RAPTOR
	}

	enum TimeSource {
		TIMETABLE,
		REALTIME
	}

	record JourneyCandidate(
		String journeyId,
		Instant plannedDepartureTime,
		Instant plannedArrivalTime,
		Instant realtimeDepartureTime,
		Instant realtimeArrivalTime,
		int durationSeconds,
		int transferCount,
		int walkingDistanceMeters,
		TimeSource timeSource,
		boolean stairFree,
		List<String> accessibilityReasonCodes
	) {
		public JourneyCandidate {
			requireText(journeyId, "journeyId");
			plannedDepartureTime = Objects.requireNonNull(plannedDepartureTime, "plannedDepartureTime");
			plannedArrivalTime = Objects.requireNonNull(plannedArrivalTime, "plannedArrivalTime");
			if (plannedArrivalTime.isBefore(plannedDepartureTime)) {
				throw new IllegalArgumentException("plannedArrivalTime must not precede plannedDepartureTime");
			}
			if ((realtimeDepartureTime == null) != (realtimeArrivalTime == null)) {
				throw new IllegalArgumentException("realtime timestamps must be both present or both absent");
			}
			if (realtimeDepartureTime != null && realtimeArrivalTime.isBefore(realtimeDepartureTime)) {
				throw new IllegalArgumentException("realtimeArrivalTime must not precede realtimeDepartureTime");
			}
			if (durationSeconds < 0) {
				throw new IllegalArgumentException("durationSeconds must not be negative");
			}
			if (transferCount < 0 || transferCount > 3) {
				throw new IllegalArgumentException("transferCount must be between 0 and 3");
			}
			if (walkingDistanceMeters < 0) {
				throw new IllegalArgumentException("walkingDistanceMeters must not be negative");
			}
			timeSource = Objects.requireNonNull(timeSource, "timeSource");
			if (timeSource == TimeSource.TIMETABLE && realtimeDepartureTime != null) {
				throw new IllegalArgumentException("timetable candidate must not contain realtime timestamps");
			}
			if (timeSource == TimeSource.REALTIME && realtimeDepartureTime == null) {
				throw new IllegalArgumentException("realtime candidate requires realtime timestamps");
			}
			accessibilityReasonCodes = List.copyOf(
				Objects.requireNonNull(accessibilityReasonCodes, "accessibilityReasonCodes"));
			if (accessibilityReasonCodes.stream().anyMatch(code -> code == null || code.isBlank())) {
				throw new IllegalArgumentException("accessibilityReasonCodes must contain non-blank values");
			}
			if (new HashSet<>(accessibilityReasonCodes).size() != accessibilityReasonCodes.size()) {
				throw new IllegalArgumentException("accessibilityReasonCodes must be unique");
			}
		}

		public PlanSource planSource() {
			return PlanSource.SERVER_TIMETABLE_RAPTOR;
		}
	}

	record JourneySourceIdentity(
		String routeBundleId,
		String routeBundleSha256,
		String timetableSnapshotId,
		String accessibilitySnapshotId,
		String realtimeSnapshotId
	) {
		public JourneySourceIdentity {
			requireText(routeBundleId, "routeBundleId");
			requireSha256(routeBundleSha256, "routeBundleSha256");
			requireText(timetableSnapshotId, "timetableSnapshotId");
			requireText(accessibilitySnapshotId, "accessibilitySnapshotId");
			if (realtimeSnapshotId != null) {
				requireText(realtimeSnapshotId, "realtimeSnapshotId");
			}
		}
	}

	record JourneyRequestPolicy(
		TimePolicy timePolicy,
		MobilityProfile mobilityProfile,
		ConstraintMode constraintMode,
		int maxTransfers,
		int alternativeCount
	) {
		public JourneyRequestPolicy {
			Objects.requireNonNull(timePolicy, "timePolicy");
			Objects.requireNonNull(mobilityProfile, "mobilityProfile");
			Objects.requireNonNull(constraintMode, "constraintMode");
		}
	}

	record JourneySearchResult(
		String requestId,
		String queryId,
		Instant calculatedAt,
		Instant validUntil,
		OffsetDateTime effectiveDepartureTime,
		LocalDate serviceDate,
		String serviceTimezone,
		JourneySourceIdentity sourceIdentity,
		JourneyRequestPolicy requestPolicy,
		List<JourneyCandidate> journeys
	) {
		public JourneySearchResult {
			requireText(requestId, "requestId");
			requireText(queryId, "queryId");
			calculatedAt = Objects.requireNonNull(calculatedAt, "calculatedAt");
			validUntil = Objects.requireNonNull(validUntil, "validUntil");
			if (!validUntil.isAfter(calculatedAt)) {
				throw new IllegalArgumentException("validUntil must be after calculatedAt");
			}
			effectiveDepartureTime = Objects.requireNonNull(effectiveDepartureTime, "effectiveDepartureTime");
			serviceDate = Objects.requireNonNull(serviceDate, "serviceDate");
			if (!"Asia/Seoul".equals(serviceTimezone)) {
				throw new IllegalArgumentException("serviceTimezone must be Asia/Seoul");
			}
			sourceIdentity = Objects.requireNonNull(sourceIdentity, "sourceIdentity");
			requestPolicy = Objects.requireNonNull(requestPolicy, "requestPolicy");
			journeys = List.copyOf(Objects.requireNonNull(journeys, "journeys"));
			if (journeys.isEmpty() || journeys.size() > requestPolicy.alternativeCount()) {
				throw new IllegalArgumentException("journeys must respect the requested alternative count");
			}
		}
	}

	private static void requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
	}

	private static void requireSha256(String value, String field) {
		if (value == null || !value.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException(field + " must be a lowercase SHA-256 digest");
		}
	}
}
