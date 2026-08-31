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
		long bundleGeneration,
		JourneyRaptorPort.ScanMetrics scanMetrics,
		SourceIdentity sourceIdentity,
		RequestPolicy requestPolicy,
		List<JourneyCandidate> journeys,
		SafetyBoundary safetyBoundary,
		RequestMeasurement requestMeasurement
	) implements JourneyExecutionResult {
		private static final Pattern REQUEST_ID = Pattern.compile("^[0-7][0-9A-HJKMNP-TV-Z]{25}$");

		public Success {
			requireText(requestId, "requestId");
			if (!REQUEST_ID.matcher(requestId).matches()) {
				throw new IllegalArgumentException("requestId must be a ULID");
			}
			requireText(queryId, "queryId");
			validateTimes(calculatedAt, validUntil, effectiveDepartureTime, serviceDate);
			if (bundleGeneration < 1) throw new IllegalArgumentException("bundleGeneration must be positive");
			Objects.requireNonNull(scanMetrics, "scanMetrics");
			Objects.requireNonNull(sourceIdentity, "sourceIdentity");
			Objects.requireNonNull(requestPolicy, "requestPolicy");
			journeys = List.copyOf(Objects.requireNonNull(journeys, "journeys"));
			Objects.requireNonNull(safetyBoundary, "safetyBoundary");
			Objects.requireNonNull(requestMeasurement, "requestMeasurement");
			validateJourneys(journeys, requestPolicy);
			validateSourcePolicy(sourceIdentity, requestPolicy, safetyBoundary);
			validateMeasurement(requestId, bundleGeneration, sourceIdentity, requestMeasurement);
		}

		private static void validateTimes(Instant calculatedAt, Instant validUntil,
			Instant effectiveDepartureTime, LocalDate serviceDate) {
			Objects.requireNonNull(calculatedAt, "calculatedAt");
			Objects.requireNonNull(validUntil, "validUntil");
			if (!validUntil.isAfter(calculatedAt)) {
				throw new IllegalArgumentException("validUntil must be after calculatedAt");
			}
			Objects.requireNonNull(effectiveDepartureTime, "effectiveDepartureTime");
			Objects.requireNonNull(serviceDate, "serviceDate");
			if (!serviceDate.equals(ServiceDayResolver.resolve(effectiveDepartureTime).serviceDate())) {
				throw new IllegalArgumentException("serviceDate does not match effectiveDepartureTime");
			}
		}

		private static void validateJourneys(List<JourneyCandidate> journeys, RequestPolicy requestPolicy) {
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
		}

		private static void validateSourcePolicy(SourceIdentity sourceIdentity, RequestPolicy requestPolicy,
			SafetyBoundary safetyBoundary) {
			if ((requestPolicy.timePolicy() == JourneyRequest.TimePolicy.TIMETABLE_REQUIRED)
				!= (sourceIdentity.realtimeSnapshotId() == null)) {
				throw new IllegalArgumentException("realtime identity does not match request policy");
			}
			if (requestPolicy.timePolicy() == JourneyRequest.TimePolicy.TIMETABLE_REQUIRED
				&& safetyBoundary.status() != SafetyBoundary.Status.OBSERVED) {
				throw new IllegalArgumentException("timetable success requires an observed snapshot boundary");
			}
			if (requestPolicy.timePolicy() == JourneyRequest.TimePolicy.REALTIME_REQUIRED
				&& safetyBoundary.status() != SafetyBoundary.Status.UNOBSERVABLE) {
				throw new IllegalArgumentException("realtime success requires a per-invocation receipt before observation");
			}
		}

		private static void validateMeasurement(String requestId, long bundleGeneration,
			SourceIdentity sourceIdentity, RequestMeasurement requestMeasurement) {
			if (requestMeasurement.status() == RequestMeasurement.Status.OBSERVED) {
				var identity = requestMeasurement.identity();
				if (!requestId.equals(identity.requestId())
					|| !sourceIdentity.routeBundleSha256().equals(identity.routeBundleSha256())
					|| bundleGeneration != identity.generation()) {
					throw new IllegalArgumentException("request measurement identity does not match success");
				}
			}
		}

		public Success(String requestId, String queryId, Instant calculatedAt, Instant validUntil,
			Instant effectiveDepartureTime, LocalDate serviceDate, long bundleGeneration,
			JourneyRaptorPort.ScanMetrics scanMetrics, SourceIdentity sourceIdentity,
			RequestPolicy requestPolicy, List<JourneyCandidate> journeys,
			SafetyBoundary safetyBoundary) {
			this(requestId, queryId, calculatedAt, validUntil, effectiveDepartureTime, serviceDate,
				bundleGeneration, scanMetrics, sourceIdentity, requestPolicy, journeys, safetyBoundary,
				RequestMeasurement.unobservable());
		}

		public String contractVersion() {
			return CONTRACT_VERSION;
		}

		public String serviceTimezone() {
			return SERVICE_TIMEZONE;
		}

		public ServiceDayIdentity serviceDayIdentity() {
			return new ServiceDayIdentity(serviceDate, SERVICE_TIMEZONE, ServiceDayResolver.CUTOFF_LOCAL_TIME);
		}

		public ExecutionObservation executionObservation() {
			if (requestMeasurement.status() == RequestMeasurement.Status.UNOBSERVABLE) {
				return new ExecutionObservation(requestId, sourceIdentity.routeBundleSha256(), bundleGeneration,
					serviceDayIdentity(), scanMetrics, null, ActiveServingIdentity.unobservable(),
					requestMeasurement.boundaryObservation());
			}
			return new ExecutionObservation(requestId, sourceIdentity.routeBundleSha256(), bundleGeneration,
				serviceDayIdentity(), scanMetrics, requestMeasurement.identity().activeReadinessIdentity(),
				requestMeasurement.identity().activeServingIdentity(), requestMeasurement.boundaryObservation());
		}

		public Source source() {
			return Source.SERVER_TIMETABLE_RAPTOR;
		}
	}

	record SafetyBoundary(Status status) {
		public enum Status { OBSERVED, UNOBSERVABLE }

		public SafetyBoundary {
			requireStatus(status);
		}

		public static SafetyBoundary observed() {
			return new SafetyBoundary(Status.OBSERVED);
		}

		public static SafetyBoundary unobservable() {
			return new SafetyBoundary(Status.UNOBSERVABLE);
		}
	}

	record ServiceDayIdentity(LocalDate serviceDate, String timezone, String cutoffLocalTime) {
		public ServiceDayIdentity {
			Objects.requireNonNull(serviceDate, "serviceDate");
			if (!SERVICE_TIMEZONE.equals(timezone) || !ServiceDayResolver.CUTOFF_LOCAL_TIME.equals(cutoffLocalTime)) {
				throw new IllegalArgumentException("service-day identity is not current");
			}
		}
	}

	record ExecutionObservation(
		String requestId,
		String routeBundleSha256,
		long bundleGeneration,
		ServiceDayIdentity serviceDay,
		JourneyRaptorPort.ScanMetrics scanMetrics,
		ActiveReadinessIdentity activeReadinessIdentity,
		ActiveServingIdentity activeServingIdentity,
		BoundaryObservation boundaryObservation
	) {
		public ExecutionObservation {
			requireText(requestId, "requestId");
			requireText(routeBundleSha256, "routeBundleSha256");
			if (!isSha256(routeBundleSha256)) {
				throw new IllegalArgumentException("routeBundleSha256 must be lowercase SHA-256");
			}
			if (bundleGeneration < 1) throw new IllegalArgumentException("bundleGeneration must be positive");
			Objects.requireNonNull(serviceDay, "serviceDay");
			Objects.requireNonNull(scanMetrics, "scanMetrics");
			Objects.requireNonNull(activeServingIdentity, "activeServingIdentity");
			Objects.requireNonNull(boundaryObservation, "boundaryObservation");
			if (activeServingIdentity.status() == ActiveServingIdentity.Status.OBSERVED) {
				Objects.requireNonNull(activeReadinessIdentity, "activeReadinessIdentity");
				if (boundaryObservation.status() != BoundaryObservation.Status.OBSERVED) {
					throw new IllegalArgumentException("observed execution identity requires observed boundary counters");
				}
			} else if (activeReadinessIdentity != null
				|| boundaryObservation.status() != BoundaryObservation.Status.UNOBSERVABLE) {
				throw new IllegalArgumentException("unobservable execution identity must not have measurement values");
			}
		}
	}

	record ActiveServingIdentity(
		Status status,
		String descriptorSha256,
		String receiptSha256,
		String deploymentIdentity,
		String deploymentRevision,
		String serviceDayCutoff
	) {
		public enum Status { OBSERVED, UNOBSERVABLE }

		public ActiveServingIdentity {
			requireStatus(status);
			if (status == Status.UNOBSERVABLE) {
				if (descriptorSha256 != null || receiptSha256 != null || deploymentIdentity != null
					|| deploymentRevision != null || serviceDayCutoff != null) {
					throw new IllegalArgumentException("unobservable active-serving identity must not have values");
				}
			} else {
				for (String value : new String[] {descriptorSha256, receiptSha256, deploymentIdentity,
					deploymentRevision, serviceDayCutoff}) {
					if (value == null || value.isBlank()) {
						throw new IllegalArgumentException("observed active-serving identity is incomplete");
					}
				}
			}
		}

		public static ActiveServingIdentity unobservable() {
			return new ActiveServingIdentity(Status.UNOBSERVABLE, null, null, null, null, null);
		}
	}

	record ActiveReadinessIdentity(
		int schemaVersion,
		String artifactKind,
		String instanceId,
		String releaseTupleSha256,
		String backendImageDigest,
		String backendConfigSha256,
		String journeyContractSha256,
		String routeBundleManifestSha256,
		String bundleId,
		long bundleReleaseSequence,
		long generation,
		String serviceTimezone,
		String serviceDayCutoff,
		long trafficGeneration,
		boolean servingReady,
		boolean draining,
		Instant freshUntil,
		Instant activatedAt,
		String evidenceSha256
	) {
		private static final Pattern IMAGE_DIGEST = Pattern.compile("^sha256:[a-f0-9]{64}$");

		public ActiveReadinessIdentity {
			if (schemaVersion != 1 || !"journey-v3-active-readiness".equals(artifactKind)) {
				throw new IllegalArgumentException("active readiness contract is not current");
			}
			requireText(instanceId, "instanceId");
			for (String value : new String[] {releaseTupleSha256, backendConfigSha256,
				journeyContractSha256, routeBundleManifestSha256, evidenceSha256}) {
				if (!isSha256(value)) {
					throw new IllegalArgumentException("active readiness digest is invalid");
				}
			}
			if (backendImageDigest == null || !IMAGE_DIGEST.matcher(backendImageDigest).matches()) {
				throw new IllegalArgumentException("active readiness image digest is invalid");
			}
			requireText(bundleId, "bundleId");
			if (bundleReleaseSequence < 1 || generation < 1 || trafficGeneration < 1) {
				throw new IllegalArgumentException("active readiness generations must be positive");
			}
			if (!SERVICE_TIMEZONE.equals(serviceTimezone)
				|| !ServiceDayResolver.CUTOFF_LOCAL_TIME.equals(serviceDayCutoff)) {
				throw new IllegalArgumentException("active readiness service-day identity is not current");
			}
			Objects.requireNonNull(freshUntil, "freshUntil");
			Objects.requireNonNull(activatedAt, "activatedAt");
		}
	}

	record RequestMeasurement(
		Status status,
		ActiveJourneySnapshotPort.RequestExecutionIdentity identity,
		BoundaryObservation boundaryObservation
	) {
		public enum Status { OBSERVED, UNOBSERVABLE }

		public RequestMeasurement {
			requireStatus(status);
			Objects.requireNonNull(boundaryObservation, "boundaryObservation");
			if (status == Status.OBSERVED) {
				if (identity == null || boundaryObservation.status() != BoundaryObservation.Status.OBSERVED) {
					throw new IllegalArgumentException("observed request measurement is incomplete");
				}
			} else if (identity != null
				|| boundaryObservation.status() != BoundaryObservation.Status.UNOBSERVABLE) {
				throw new IllegalArgumentException("unobservable request measurement must not have values");
			}
		}

		public static RequestMeasurement observed(
			ActiveJourneySnapshotPort.RequestExecutionIdentity identity,
			BoundaryObservation boundaryObservation) {
			return new RequestMeasurement(Status.OBSERVED, identity, boundaryObservation);
		}

		public static RequestMeasurement unobservable() {
			return new RequestMeasurement(Status.UNOBSERVABLE, null, BoundaryObservation.unobservable());
		}
	}

	record BoundaryObservation(
		Status status,
		Long providerCalls,
		Long cacheHits,
		Long staleArtifactUses,
		Long fallbackUses
	) {
		public enum Status { OBSERVED, UNOBSERVABLE }

		public BoundaryObservation {
			requireStatus(status);
			if (status == Status.UNOBSERVABLE) {
				if (providerCalls != null || cacheHits != null || staleArtifactUses != null || fallbackUses != null) {
					throw new IllegalArgumentException("unobservable boundary must not have counters");
				}
			} else {
				for (Long value : new Long[] {providerCalls, cacheHits, staleArtifactUses, fallbackUses}) {
					if (value == null || value < 0) {
						throw new IllegalArgumentException("observed boundary counters must be nonnegative");
					}
				}
			}
		}

		public static BoundaryObservation unobservable() {
			return new BoundaryObservation(Status.UNOBSERVABLE, null, null, null, null);
		}

		public static BoundaryObservation observed(long providerCalls, long cacheHits,
			long staleArtifactUses, long fallbackUses) {
			return new BoundaryObservation(Status.OBSERVED, providerCalls, cacheHits, staleArtifactUses, fallbackUses);
		}
	}

	record SourceIdentity(
		String routeBundleId,
		String routeBundleSha256,
		String timetableSnapshotId,
		String accessibilitySnapshotId,
		String realtimeSnapshotId
	) {
		public SourceIdentity {
			requireText(routeBundleId, "routeBundleId");
			requireText(routeBundleSha256, "routeBundleSha256");
			if (!isSha256(routeBundleSha256)) {
				throw new IllegalArgumentException("routeBundleSha256 must be lowercase SHA-256");
			}
			requireText(timetableSnapshotId, "timetableSnapshotId");
			requireText(accessibilitySnapshotId, "accessibilitySnapshotId");
			if (realtimeSnapshotId != null) requireText(realtimeSnapshotId, "realtimeSnapshotId");
		}
	}

	record RequestPolicy(
		JourneyRequest.TimePolicy timePolicy,
		JourneyRequest.WalkingPace walkingPace,
		JourneyRequest.MobilityProfile mobilityProfile,
		JourneyRequest.ConstraintMode constraintMode,
		int maxTransfers,
		int alternativeCount
	) {
		public RequestPolicy {
			Objects.requireNonNull(timePolicy, "timePolicy");
			Objects.requireNonNull(walkingPace, "walkingPace");
			Objects.requireNonNull(mobilityProfile, "mobilityProfile");
			Objects.requireNonNull(constraintMode, "constraintMode");
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

	private static boolean isSha256(String value) {
		return value != null && value.matches("^[a-f0-9]{64}$");
	}

	private static void requireStatus(Object status) {
		Objects.requireNonNull(status, "status");
	}
}
