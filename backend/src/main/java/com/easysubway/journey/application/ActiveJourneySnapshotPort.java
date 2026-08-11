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
		boolean fresh
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
}
