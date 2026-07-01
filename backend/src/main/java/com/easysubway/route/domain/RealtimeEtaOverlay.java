package com.easysubway.route.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class RealtimeEtaOverlay {

	public Result overlay(
		Instant readyAt,
		int plannedWaitSeconds,
		String direction,
		ArrivalFreshness providerStatus,
		String fallbackCode,
		List<ArrivalCandidate> candidates
	) {
		return overlay(readyAt, plannedWaitSeconds, direction, providerStatus, fallbackCode, null, null, 0, candidates);
	}

	public Result overlay(
		Instant readyAt,
		int plannedWaitSeconds,
		String direction,
		ArrivalFreshness providerStatus,
		String fallbackCode,
		String providerSnapshotId,
		Instant providerReceivedAt,
		int providerHealthCount,
		List<ArrivalCandidate> candidates
	) {
		if (readyAt == null) {
			throw new IllegalArgumentException("readyAt is required.");
		}
		if (plannedWaitSeconds < 0) {
			throw new IllegalArgumentException("plannedWaitSeconds must be greater than or equal to zero.");
		}
		ArrivalFreshness status = providerStatus == null ? ArrivalFreshness.UNAVAILABLE : providerStatus;
		List<ArrivalCandidate> safeCandidates = candidates == null ? List.of() : candidates;
		return switch (status) {
			case FRESH_REALTIME -> freshOverlay(
				readyAt,
				plannedWaitSeconds,
				direction,
				providerSnapshotId,
				providerReceivedAt,
				providerHealthCount,
				safeCandidates
			);
			case STALE_REALTIME -> planned(
				readyAt,
				plannedWaitSeconds,
				EtaSource.PLANNED,
				EtaConfidence.MEDIUM,
				providerSnapshotId,
				providerReceivedAt,
				providerHealthCount,
				List.of("STALE_REALTIME")
			);
			case UNSUPPORTED -> planned(
				readyAt,
				plannedWaitSeconds,
				EtaSource.PLANNED,
				EtaConfidence.MEDIUM,
				providerSnapshotId,
				providerReceivedAt,
				providerHealthCount,
				List.of("UNSUPPORTED_REALTIME")
			);
			case UNAVAILABLE -> planned(
				readyAt,
				plannedWaitSeconds,
				EtaSource.FALLBACK,
				EtaConfidence.LOW,
				providerSnapshotId,
				providerReceivedAt,
				providerHealthCount,
				warnings("REALTIME_UNAVAILABLE_PLANNED_USED", fallbackCode)
			);
			case EMPTY_PROVIDER_RESULT -> planned(
				readyAt,
				plannedWaitSeconds,
				EtaSource.FALLBACK,
				EtaConfidence.LOW,
				providerSnapshotId,
				providerReceivedAt,
				providerHealthCount,
				warnings(ArrivalFreshness.EMPTY_PROVIDER_RESULT.name(), fallbackCode)
			);
		};
	}

	private Result freshOverlay(
		Instant readyAt,
		int plannedWaitSeconds,
		String direction,
		String providerSnapshotId,
		Instant providerReceivedAt,
		int providerHealthCount,
		List<ArrivalCandidate> candidates
	) {
		return candidates.stream()
			.filter(candidate -> candidate.freshness() == ArrivalFreshness.FRESH_REALTIME)
			.filter(candidate -> !candidate.expectedArrivalAt().isBefore(readyAt))
			.filter(candidate -> matchesDirection(direction, candidate.direction()))
			.min(Comparator.comparing(ArrivalCandidate::expectedArrivalAt))
			.map(candidate -> realtime(readyAt, plannedWaitSeconds, providerSnapshotId, providerReceivedAt, providerHealthCount, candidate))
			.orElseGet(() -> planned(
				readyAt,
				plannedWaitSeconds,
				EtaSource.FALLBACK,
				EtaConfidence.LOW,
				providerSnapshotId,
				providerReceivedAt,
				providerHealthCount,
				List.of("NO_USABLE_REALTIME_CANDIDATE")
			));
	}

	private Result realtime(
		Instant readyAt,
		int plannedWaitSeconds,
		String providerSnapshotId,
		Instant providerReceivedAt,
		int providerHealthCount,
		ArrivalCandidate candidate
	) {
		int waitSeconds = Math.toIntExact(Duration.between(readyAt, candidate.expectedArrivalAt()).toSeconds());
		Instant evidenceReceivedAt = providerReceivedAt == null ? candidate.providerReceivedAt() : providerReceivedAt;
		String evidence = evidenceReceivedAt == null
			? null
			: "providerReceivedAt=" + evidenceReceivedAt;
		return new Result(
			EtaSource.REALTIME,
			candidate.confidence(),
			plannedWaitSeconds,
			waitSeconds,
			candidate.expectedArrivalAt(),
			candidate.trainNo(),
			evidence,
			providerSnapshotId,
			evidenceReceivedAt,
			providerHealthCount,
			List.of()
		);
	}

	private Result planned(
		Instant readyAt,
		int plannedWaitSeconds,
		EtaSource etaSource,
		EtaConfidence confidence,
		String providerSnapshotId,
		Instant providerReceivedAt,
		int providerHealthCount,
		List<String> warningCodes
	) {
		return new Result(
			etaSource,
			confidence,
			plannedWaitSeconds,
			plannedWaitSeconds,
			readyAt.plusSeconds(plannedWaitSeconds),
			null,
			null,
			providerSnapshotId,
			providerReceivedAt,
			providerHealthCount,
			warningCodes
		);
	}

	private boolean matchesDirection(String expected, String actual) {
		return expected == null || expected.isBlank() || expected.equals(actual);
	}

	private List<String> warnings(String defaultCode, String fallbackCode) {
		List<String> warnings = new ArrayList<>();
		warnings.add(defaultCode);
		if (fallbackCode != null && !fallbackCode.isBlank() && !defaultCode.equals(fallbackCode)) {
			warnings.add(fallbackCode);
		}
		return List.copyOf(warnings);
	}

	public record Result(
		EtaSource etaSource,
		EtaConfidence confidence,
		int plannedWaitSeconds,
		int waitSeconds,
		Instant expectedDepartureAt,
		String trainNo,
		String providerEvidence,
		String providerSnapshotId,
		Instant providerReceivedAt,
		int providerHealthCount,
		List<String> warningCodes
	) {
		public Result {
			warningCodes = warningCodes == null ? List.of() : List.copyOf(warningCodes);
		}
	}
}
