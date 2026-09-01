package com.easysubway.journey.application;

import java.time.Instant;
import java.util.Objects;

public sealed interface JourneyProfileExecutionResult
	permits JourneyProfileExecutionResult.Success, JourneyProfileExecutionResult.Failure {

	record Success(
		Instant calculatedAt,
		Instant validUntil,
		SourceIdentity sourceIdentity,
		JourneyProfileResourcePolicy.Identity resourcePolicyIdentity,
		JourneyProfileRaptorPort.TemporalPlan temporalPlan,
		JourneyRaptorPruningInventoryV1.CountSnapshot countSnapshot
	) implements JourneyProfileExecutionResult {
		public Success {
			calculatedAt = Objects.requireNonNull(calculatedAt, "calculatedAt");
			validUntil = Objects.requireNonNull(validUntil, "validUntil");
			if (!validUntil.isAfter(calculatedAt)) {
				throw new IllegalArgumentException("validUntil must be after calculatedAt");
			}
			sourceIdentity = Objects.requireNonNull(sourceIdentity, "sourceIdentity");
			resourcePolicyIdentity = Objects.requireNonNull(resourcePolicyIdentity, "resourcePolicyIdentity");
			temporalPlan = Objects.requireNonNull(temporalPlan, "temporalPlan");
			countSnapshot = Objects.requireNonNull(countSnapshot, "countSnapshot");
		}
	}

	record Failure(
		Reason reason,
		JourneyRaptorPruningInventoryV1.CountSnapshot countSnapshot
	) implements JourneyProfileExecutionResult {
		public Failure {
			reason = Objects.requireNonNull(reason, "reason");
		}

		public Failure(Reason reason) {
			this(reason, null);
		}
	}

	enum Reason {
		CANCELLED,
		ACTIVE_SNAPSHOT_UNAVAILABLE,
		ACTIVE_SNAPSHOT_STALE,
		REALTIME_UNAVAILABLE,
		TEMPORAL_QUERY_TOO_COMPLEX,
		RAPTOR_FRONTIER_CAPACITY_EXCEEDED,
		RAPTOR_FAILED
	}

	record SourceIdentity(
		String routeBundleId,
		String routeBundleSha256,
		String timetableSnapshotId,
		String accessibilitySnapshotId,
		long generation
	) {
		public SourceIdentity {
			routeBundleId = requireText(routeBundleId, "routeBundleId");
			routeBundleSha256 = requireText(routeBundleSha256, "routeBundleSha256");
			timetableSnapshotId = requireText(timetableSnapshotId, "timetableSnapshotId");
			accessibilitySnapshotId = requireText(accessibilitySnapshotId, "accessibilitySnapshotId");
			if (generation < 1) throw new IllegalArgumentException("generation must be positive");
		}

		private static String requireText(String value, String name) {
			Objects.requireNonNull(value, name);
			if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
			return value;
		}
	}
}
