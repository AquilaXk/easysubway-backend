package com.easysubway.journey.application;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

@FunctionalInterface
public interface ActiveJourneySnapshotPort {
	ActiveJourneySnapshot requireActive(Instant effectiveInstant);

	record ActiveJourneySnapshot(
		String identity,
		String routeBundleId,
		String routeBundleSha256,
		String timetableSnapshotId,
		String accessibilitySnapshotId,
		long generation,
		JourneyRaptorRuntimeView runtimeView,
		Instant validUntil,
		boolean fresh,
		ActiveServingEvidence servingEvidence,
		SnapshotBoundaryReceipt boundaryReceipt
	) {
		private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");

		public ActiveJourneySnapshot {
			identity = requireText(identity, "identity");
			routeBundleId = requireText(routeBundleId, "routeBundleId");
			routeBundleSha256 = requireSha256(routeBundleSha256);
			timetableSnapshotId = requireText(timetableSnapshotId, "timetableSnapshotId");
			accessibilitySnapshotId = requireText(accessibilitySnapshotId, "accessibilitySnapshotId");
			if (generation < 1) throw new IllegalArgumentException("generation must be positive");
			runtimeView = Objects.requireNonNull(runtimeView, "runtimeView");
			if (!routeBundleSha256.equals(runtimeView.routeBundleSha256())) {
				throw new IllegalArgumentException("runtime view routeBundleSha256 does not match snapshot");
			}
			if (generation != runtimeView.generation()) {
				throw new IllegalArgumentException("runtime view generation does not match snapshot");
			}
			validUntil = Objects.requireNonNull(validUntil, "validUntil");
			servingEvidence = Objects.requireNonNull(servingEvidence, "servingEvidence");
			boundaryReceipt = Objects.requireNonNull(boundaryReceipt, "boundaryReceipt");
		}

		private static String requireSha256(String value) {
			value = requireText(value, "routeBundleSha256");
			if (!SHA256.matcher(value).matches()) {
				throw new IllegalArgumentException("routeBundleSha256 must be lowercase SHA-256");
			}
			return value;
		}

		private static String requireText(String value, String name) {
			Objects.requireNonNull(value, name);
			if (value.isBlank()) {
				throw new IllegalArgumentException(name + " must not be blank");
			}
			return value;
		}
	}

	/** Immutable serving evidence captured with this active snapshot generation. */
	record ActiveServingEvidence(Status status, String descriptorSha256, String publicationReceiptSha256) {
		private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");

		enum Status { OBSERVED, UNOBSERVABLE }

		public ActiveServingEvidence {
			status = Objects.requireNonNull(status, "status");
			if (status == Status.UNOBSERVABLE) {
				if (descriptorSha256 != null || publicationReceiptSha256 != null) {
					throw new IllegalArgumentException("unobservable serving evidence must not have digests");
				}
			} else {
				descriptorSha256 = requireSha256(descriptorSha256, "descriptorSha256");
				publicationReceiptSha256 = requireSha256(
					publicationReceiptSha256, "publicationReceiptSha256");
			}
		}

		public static ActiveServingEvidence observed(
			String descriptorSha256, String publicationReceiptSha256) {
			return new ActiveServingEvidence(Status.OBSERVED, descriptorSha256, publicationReceiptSha256);
		}

		public static ActiveServingEvidence unobservable() {
			return new ActiveServingEvidence(Status.UNOBSERVABLE, null, null);
		}

		private static String requireSha256(String value, String name) {
			value = Objects.requireNonNull(value, name);
			if (!SHA256.matcher(value).matches()) {
				throw new IllegalArgumentException(name + " must be lowercase SHA-256");
			}
			return value;
		}
	}

	/** Immutable evidence emitted by the active-snapshot boundary for this captured snapshot. */
	record SnapshotBoundaryReceipt(Status status, Long cacheHits, Long staleArtifactUses) {
		enum Status { OBSERVED, UNOBSERVABLE }

		public SnapshotBoundaryReceipt {
			status = Objects.requireNonNull(status, "status");
			if (status == Status.UNOBSERVABLE) {
				if (cacheHits != null || staleArtifactUses != null) {
					throw new IllegalArgumentException("unobservable snapshot receipt must not have counters");
				}
			} else if (cacheHits == null || cacheHits < 0 || staleArtifactUses == null || staleArtifactUses < 0) {
				throw new IllegalArgumentException("observed snapshot receipt counters must be nonnegative");
			}
		}

		public static SnapshotBoundaryReceipt observed(long cacheHits, long staleArtifactUses) {
			return new SnapshotBoundaryReceipt(Status.OBSERVED, cacheHits, staleArtifactUses);
		}

		public static SnapshotBoundaryReceipt unobservable() {
			return new SnapshotBoundaryReceipt(Status.UNOBSERVABLE, null, null);
		}
	}
}
