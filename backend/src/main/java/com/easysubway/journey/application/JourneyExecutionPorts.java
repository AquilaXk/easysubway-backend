package com.easysubway.journey.application;

import com.easysubway.journey.application.JourneySearchUseCase.JourneyCandidate;
import com.easysubway.journey.application.JourneySearchUseCase.JourneySearchCommand;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class JourneyExecutionPorts {

	private JourneyExecutionPorts() {
	}

	@FunctionalInterface
	public interface ActiveBundleSnapshotProvider {
		BundleSnapshot capture(Instant acceptedAt);
	}

	@FunctionalInterface
	public interface StrictRealtimeProvider {
		RealtimeSnapshot load(RealtimeQuery query);
	}

	@FunctionalInterface
	public interface RaptorExecutor {
		JourneyPlan execute(RaptorQuery query);
	}

	public interface JourneyRuntimeSnapshot {
	}

	public interface RealtimeOverlay {
	}

	public record BundleSnapshot(
		long generation,
		String routeBundleId,
		String routeBundleSha256,
		String stationSetSha256,
		String timetableSnapshotId,
		String accessibilitySnapshotId,
		String serviceTimezone,
		Instant freshUntil,
		JourneyRuntimeSnapshot runtime
	) {
		public BundleSnapshot {
			if (generation < 1) {
				throw new IllegalArgumentException("generation must be positive");
			}
			requireText(routeBundleId, "routeBundleId");
			requireSha256(routeBundleSha256, "routeBundleSha256");
			requireSha256(stationSetSha256, "stationSetSha256");
			requireText(timetableSnapshotId, "timetableSnapshotId");
			requireText(accessibilitySnapshotId, "accessibilitySnapshotId");
			if (!"Asia/Seoul".equals(serviceTimezone)) {
				throw new IllegalArgumentException("serviceTimezone must be Asia/Seoul");
			}
			freshUntil = Objects.requireNonNull(freshUntil, "freshUntil");
			runtime = Objects.requireNonNull(runtime, "runtime");
		}
	}

	public record RealtimeQuery(
		Instant acceptedAt,
		Instant effectiveDeparture,
		JourneySearchCommand command,
		BundleSnapshot bundle
	) {
		public RealtimeQuery {
			acceptedAt = Objects.requireNonNull(acceptedAt, "acceptedAt");
			effectiveDeparture = Objects.requireNonNull(effectiveDeparture, "effectiveDeparture");
			command = Objects.requireNonNull(command, "command");
			bundle = Objects.requireNonNull(bundle, "bundle");
		}
	}

	public record RealtimeSnapshot(
		String snapshotId,
		String routeBundleSha256,
		Instant observedAt,
		Instant freshUntil,
		RealtimeOverlay overlay
	) {
		public RealtimeSnapshot {
			requireText(snapshotId, "snapshotId");
			requireSha256(routeBundleSha256, "routeBundleSha256");
			observedAt = Objects.requireNonNull(observedAt, "observedAt");
			freshUntil = Objects.requireNonNull(freshUntil, "freshUntil");
			if (!freshUntil.isAfter(observedAt)) {
				throw new IllegalArgumentException("freshUntil must be after observedAt");
			}
			overlay = Objects.requireNonNull(overlay, "overlay");
		}
	}

	public record RaptorQuery(
		Instant acceptedAt,
		Instant effectiveDeparture,
		JourneySearchCommand command,
		BundleSnapshot bundle,
		RealtimeSnapshot realtime
	) {
		public RaptorQuery {
			acceptedAt = Objects.requireNonNull(acceptedAt, "acceptedAt");
			effectiveDeparture = Objects.requireNonNull(effectiveDeparture, "effectiveDeparture");
			command = Objects.requireNonNull(command, "command");
			bundle = Objects.requireNonNull(bundle, "bundle");
		}
	}

	public record JourneyPlan(
		String routeBundleSha256,
		String timetableSnapshotId,
		String accessibilitySnapshotId,
		String realtimeSnapshotId,
		List<JourneyCandidate> candidates
	) {
		public JourneyPlan {
			requireSha256(routeBundleSha256, "routeBundleSha256");
			requireText(timetableSnapshotId, "timetableSnapshotId");
			requireText(accessibilitySnapshotId, "accessibilitySnapshotId");
			if (realtimeSnapshotId != null) {
				requireText(realtimeSnapshotId, "realtimeSnapshotId");
			}
			candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
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
