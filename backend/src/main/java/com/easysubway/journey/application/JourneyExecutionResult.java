package com.easysubway.journey.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public sealed interface JourneyExecutionResult permits JourneyExecutionResult.Success, JourneyExecutionFailure {

	String CONTRACT_VERSION = "JOURNEY_SEARCH_V3";
	String SERVICE_TIMEZONE = "Asia/Seoul";
	ZoneId SERVICE_ZONE = ZoneId.of(SERVICE_TIMEZONE);

	enum Source {
		SERVER_TIMETABLE_RAPTOR
	}

	record Success(
		String requestId,
		String queryId,
		Instant calculatedAt,
		Instant validUntil,
		Instant effectiveDepartureTime,
		LocalDate serviceDate,
		SourceIdentity sourceIdentity,
		RequestPolicy requestPolicy,
		List<JourneyCandidate> journeys
	) implements JourneyExecutionResult {
		private static final Pattern REQUEST_ID = Pattern.compile("^[0-7][0-9A-HJKMNP-TV-Z]{25}$");

		public Success {
			requestId = requireText(requestId, "requestId");
			if (!REQUEST_ID.matcher(requestId).matches()) {
				throw new IllegalArgumentException("requestId must be a ULID");
			}
			queryId = requireText(queryId, "queryId");
			calculatedAt = Objects.requireNonNull(calculatedAt, "calculatedAt");
			validUntil = Objects.requireNonNull(validUntil, "validUntil");
			if (!validUntil.isAfter(calculatedAt)) {
				throw new IllegalArgumentException("validUntil must be after calculatedAt");
			}
			effectiveDepartureTime = Objects.requireNonNull(effectiveDepartureTime, "effectiveDepartureTime");
			serviceDate = Objects.requireNonNull(serviceDate, "serviceDate");
			if (!serviceDate.equals(effectiveDepartureTime.atZone(SERVICE_ZONE).toLocalDate())) {
				throw new IllegalArgumentException("serviceDate does not match effectiveDepartureTime");
			}
			sourceIdentity = Objects.requireNonNull(sourceIdentity, "sourceIdentity");
			requestPolicy = Objects.requireNonNull(requestPolicy, "requestPolicy");
			journeys = List.copyOf(Objects.requireNonNull(journeys, "journeys"));
			if (journeys.isEmpty() || journeys.size() > requestPolicy.alternativeCount() || journeys.size() > 3) {
				throw new IllegalArgumentException("journey count does not match request policy");
			}
			var journeyIds = new HashSet<String>();
			for (JourneyCandidate journey : journeys) {
				if (!journeyIds.add(journey.journeyId())) {
					throw new IllegalArgumentException("journeyId must be unique");
				}
				if (requestPolicy.timePolicy() == JourneyRequest.TimePolicy.TIMETABLE_REQUIRED
					&& journey.timeSource() != JourneyCandidate.TimeSource.TIMETABLE) {
					throw new IllegalArgumentException("timetable request has realtime journey");
				}
				if (requestPolicy.timePolicy() == JourneyRequest.TimePolicy.REALTIME_REQUIRED
					&& journey.timeSource() != JourneyCandidate.TimeSource.REALTIME) {
					throw new IllegalArgumentException("realtime request has timetable journey");
				}
			}
			if ((requestPolicy.timePolicy() == JourneyRequest.TimePolicy.TIMETABLE_REQUIRED)
				!= (sourceIdentity.realtimeSnapshotId() == null)) {
				throw new IllegalArgumentException("realtime identity does not match request policy");
			}
		}

		public String contractVersion() {
			return CONTRACT_VERSION;
		}

		public String serviceTimezone() {
			return SERVICE_TIMEZONE;
		}

		public Source source() {
			return Source.SERVER_TIMETABLE_RAPTOR;
		}
	}

	record SourceIdentity(
		String routeBundleId,
		String routeBundleSha256,
		String timetableSnapshotId,
		String accessibilitySnapshotId,
		String realtimeSnapshotId
	) {
		private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");

		public SourceIdentity {
			routeBundleId = requireText(routeBundleId, "routeBundleId");
			routeBundleSha256 = requireText(routeBundleSha256, "routeBundleSha256");
			if (!SHA256.matcher(routeBundleSha256).matches()) {
				throw new IllegalArgumentException("routeBundleSha256 must be lowercase SHA-256");
			}
			timetableSnapshotId = requireText(timetableSnapshotId, "timetableSnapshotId");
			accessibilitySnapshotId = requireText(accessibilitySnapshotId, "accessibilitySnapshotId");
			realtimeSnapshotId = realtimeSnapshotId == null
				? null : requireText(realtimeSnapshotId, "realtimeSnapshotId");
		}
	}

	record RequestPolicy(
		JourneyRequest.TimePolicy timePolicy,
		JourneyRequest.MobilityProfile mobilityProfile,
		JourneyRequest.ConstraintMode constraintMode,
		int maxTransfers,
		int alternativeCount
	) {
		public RequestPolicy {
			timePolicy = Objects.requireNonNull(timePolicy, "timePolicy");
			mobilityProfile = Objects.requireNonNull(mobilityProfile, "mobilityProfile");
			constraintMode = Objects.requireNonNull(constraintMode, "constraintMode");
			if (maxTransfers < 0 || maxTransfers > 3) {
				throw new IllegalArgumentException("maxTransfers must be between 0 and 3");
			}
			if (alternativeCount < 1 || alternativeCount > 3) {
				throw new IllegalArgumentException("alternativeCount must be between 1 and 3");
			}
			if (mobilityProfile == JourneyRequest.MobilityProfile.NO_STAIRS
				&& constraintMode == JourneyRequest.ConstraintMode.NONE) {
				throw new IllegalArgumentException("NO_STAIRS requires REQUIRE_STEP_FREE");
			}
		}
	}

	private static String requireText(String value, String name) {
		Objects.requireNonNull(value, name);
		if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}
}
