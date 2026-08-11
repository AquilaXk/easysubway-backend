package com.easysubway.journey.application;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

@FunctionalInterface
public interface JourneyRealtimePort {
	RealtimeObservation requireFresh(
		JourneyRequest request,
		ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot,
		Instant effectiveInstant
	);

	record RealtimeObservation(String identity, String routeBundleSha256, Instant validUntil, boolean fresh) {
		private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");

		public RealtimeObservation {
			identity = requireText(identity, "identity");
			routeBundleSha256 = requireText(routeBundleSha256, "routeBundleSha256");
			if (!SHA256.matcher(routeBundleSha256).matches()) {
				throw new IllegalArgumentException("routeBundleSha256 must be lowercase SHA-256");
			}
			validUntil = Objects.requireNonNull(validUntil, "validUntil");
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
