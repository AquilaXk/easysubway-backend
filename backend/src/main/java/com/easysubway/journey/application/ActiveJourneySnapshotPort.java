package com.easysubway.journey.application;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

public interface ActiveJourneySnapshotPort {
	ActiveJourneySnapshot requireActive(JourneyRequest request, Instant effectiveInstant,
		JourneyRequestMeasurement measurement);

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
		SnapshotBoundaryReceipt boundaryReceipt,
		SnapshotMeasurementReceipt measurementReceipt
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
			measurementReceipt = Objects.requireNonNull(measurementReceipt, "measurementReceipt");
		}

		public ActiveJourneySnapshot(String identity, String routeBundleId, String routeBundleSha256,
			String timetableSnapshotId, String accessibilitySnapshotId, long generation,
			JourneyRaptorRuntimeView runtimeView, Instant validUntil, boolean fresh,
			ActiveServingEvidence servingEvidence, SnapshotBoundaryReceipt boundaryReceipt) {
			this(identity, routeBundleId, routeBundleSha256, timetableSnapshotId, accessibilitySnapshotId,
				generation, runtimeView, validUntil, fresh, servingEvidence, boundaryReceipt,
				SnapshotMeasurementReceipt.unobservable());
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

	/** Immutable request and deployment identity captured by one Journey execution boundary. */
	record RequestExecutionIdentity(String requestId, String routeBundleSha256, long generation,
		JourneyExecutionResult.ActiveReadinessIdentity activeReadinessIdentity,
		JourneyExecutionResult.ActiveServingIdentity activeServingIdentity) {
		private static final Pattern REQUEST_ID = Pattern.compile("^[0-7][0-9A-HJKMNP-TV-Z]{25}$");
		private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");

		public RequestExecutionIdentity {
			if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
				throw new IllegalArgumentException("requestId must be a ULID");
			}
			if (routeBundleSha256 == null || !SHA256.matcher(routeBundleSha256).matches()) {
				throw new IllegalArgumentException("routeBundleSha256 is invalid");
			}
			if (generation < 1) throw new IllegalArgumentException("generation must be positive");
			activeReadinessIdentity = Objects.requireNonNull(activeReadinessIdentity, "activeReadinessIdentity");
			activeServingIdentity = Objects.requireNonNull(activeServingIdentity, "activeServingIdentity");
			if (activeServingIdentity.status() != JourneyExecutionResult.ActiveServingIdentity.Status.OBSERVED) {
				throw new IllegalArgumentException("request execution identity requires observed active identity");
			}
			if (!routeBundleSha256.equals(activeReadinessIdentity.routeBundleManifestSha256())
				|| generation != activeReadinessIdentity.generation()
				|| !activeReadinessIdentity.servingReady() || activeReadinessIdentity.draining()
				|| !JourneyExecutionResult.SERVICE_TIMEZONE.equals(activeReadinessIdentity.serviceTimezone())
				|| !activeReadinessIdentity.serviceDayCutoff().equals(activeServingIdentity.serviceDayCutoff())
				|| !("sha256:" + activeReadinessIdentity.releaseTupleSha256())
					.equals(activeServingIdentity.deploymentIdentity())) {
				throw new IllegalArgumentException("request execution identities do not match");
			}
		}
	}

	record SnapshotBoundaryReceipt(Status status, Long providerCalls, Long cacheHits, Long staleArtifactUses) {
		enum Status { OBSERVED, UNOBSERVABLE }

		public SnapshotBoundaryReceipt {
			status = Objects.requireNonNull(status, "status");
			if (status == Status.UNOBSERVABLE) {
				if (providerCalls != null || cacheHits != null || staleArtifactUses != null) {
					throw new IllegalArgumentException("unobservable snapshot receipt must not have counters");
				}
			} else if (providerCalls == null || providerCalls < 0 || cacheHits == null || cacheHits < 0
				|| staleArtifactUses == null || staleArtifactUses < 0) {
				throw new IllegalArgumentException("observed snapshot receipt is incomplete");
			}
		}

		public static SnapshotBoundaryReceipt observed(
			long providerCalls, long cacheHits, long staleArtifactUses) {
			return new SnapshotBoundaryReceipt(Status.OBSERVED, providerCalls, cacheHits, staleArtifactUses);
		}

		public static SnapshotBoundaryReceipt unobservable() {
			return new SnapshotBoundaryReceipt(Status.UNOBSERVABLE, null, null, null);
		}
	}

	record SnapshotMeasurementReceipt(Status status, RequestExecutionIdentity identity,
		Long providerCalls, Long cacheHits, Long staleArtifactUses) {
		public enum Status { OBSERVED, UNOBSERVABLE }

		public SnapshotMeasurementReceipt {
			status = Objects.requireNonNull(status, "status");
			if (status == Status.OBSERVED && (identity == null || providerCalls == null || providerCalls < 0
				|| cacheHits == null || cacheHits < 0 || staleArtifactUses == null || staleArtifactUses < 0)) {
				throw new IllegalArgumentException("observed measurement is incomplete");
			}
			if (status == Status.UNOBSERVABLE
				&& (identity != null || providerCalls != null || cacheHits != null || staleArtifactUses != null)) {
				throw new IllegalArgumentException("unobservable measurement must not have values");
			}
		}

		public static SnapshotMeasurementReceipt observed(
			JourneyRequestMeasurement.SnapshotObservation observation) {
			Objects.requireNonNull(observation, "observation");
			return new SnapshotMeasurementReceipt(Status.OBSERVED, observation.identity(),
				observation.providerCalls(), observation.cacheHits(), observation.staleArtifactUses());
		}

		public static SnapshotMeasurementReceipt unobservable() {
			return new SnapshotMeasurementReceipt(Status.UNOBSERVABLE, null, null, null, null);
		}
	}
}
