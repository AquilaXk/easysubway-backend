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
		BoundaryObservation boundaryObservation
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
			if (!serviceDate.equals(ServiceDayResolver.resolve(effectiveDepartureTime).serviceDate())) {
				throw new IllegalArgumentException("serviceDate does not match effectiveDepartureTime");
			}
			if (bundleGeneration < 1) throw new IllegalArgumentException("bundleGeneration must be positive");
			scanMetrics = Objects.requireNonNull(scanMetrics, "scanMetrics");
			sourceIdentity = Objects.requireNonNull(sourceIdentity, "sourceIdentity");
			requestPolicy = Objects.requireNonNull(requestPolicy, "requestPolicy");
			journeys = List.copyOf(Objects.requireNonNull(journeys, "journeys"));
			boundaryObservation = Objects.requireNonNull(boundaryObservation, "boundaryObservation");
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
			if (requestPolicy.timePolicy() == JourneyRequest.TimePolicy.TIMETABLE_REQUIRED
				&& boundaryObservation.status() != BoundaryObservation.Status.OBSERVED) {
				throw new IllegalArgumentException("timetable success requires an observed snapshot boundary");
			}
			if (requestPolicy.timePolicy() == JourneyRequest.TimePolicy.REALTIME_REQUIRED
				&& boundaryObservation.status() != BoundaryObservation.Status.UNOBSERVABLE) {
				throw new IllegalArgumentException("realtime success requires a per-invocation receipt before observation");
			}
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
			return new ExecutionObservation(requestId, sourceIdentity.routeBundleSha256(), bundleGeneration,
				serviceDayIdentity(), scanMetrics, ActiveServingIdentity.unobservable(), boundaryObservation);
		}

		public Source source() {
			return Source.SERVER_TIMETABLE_RAPTOR;
		}
	}

	record ServiceDayIdentity(LocalDate serviceDate, String timezone, String cutoffLocalTime) {
		public ServiceDayIdentity {
			serviceDate = Objects.requireNonNull(serviceDate, "serviceDate");
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
		ActiveServingIdentity activeServingIdentity,
		BoundaryObservation boundaryObservation
	) {
		public ExecutionObservation {
			requestId = requireText(requestId, "requestId");
			routeBundleSha256 = requireText(routeBundleSha256, "routeBundleSha256");
			if (!routeBundleSha256.matches("^[a-f0-9]{64}$")) {
				throw new IllegalArgumentException("routeBundleSha256 must be lowercase SHA-256");
			}
			if (bundleGeneration < 1) throw new IllegalArgumentException("bundleGeneration must be positive");
			serviceDay = Objects.requireNonNull(serviceDay, "serviceDay");
			scanMetrics = Objects.requireNonNull(scanMetrics, "scanMetrics");
			activeServingIdentity = Objects.requireNonNull(activeServingIdentity, "activeServingIdentity");
			boundaryObservation = Objects.requireNonNull(boundaryObservation, "boundaryObservation");
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
			status = Objects.requireNonNull(status, "status");
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

	record BoundaryObservation(
		Status status,
		Long providerCalls,
		Long cacheHits,
		Long staleArtifactUses,
		Long fallbackUses
	) {
		public enum Status { OBSERVED, UNOBSERVABLE }

		public BoundaryObservation {
			status = Objects.requireNonNull(status, "status");
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
		JourneyRequest.WalkingPace walkingPace,
		JourneyRequest.MobilityProfile mobilityProfile,
		JourneyRequest.ConstraintMode constraintMode,
		int maxTransfers,
		int alternativeCount
	) {
		public RequestPolicy {
			timePolicy = Objects.requireNonNull(timePolicy, "timePolicy");
			walkingPace = Objects.requireNonNull(walkingPace, "walkingPace");
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
