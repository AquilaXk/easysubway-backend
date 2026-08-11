package com.easysubway.journey.application;

import java.time.Instant;
import java.util.Objects;

@FunctionalInterface
public interface JourneyRealtimePort {
	RealtimeObservation requireFresh(
		JourneyRequest request,
		ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot,
		Instant effectiveInstant
	);

	record RealtimeObservation(String identity, String bundleIdentity, boolean fresh) {
		public RealtimeObservation {
			identity = requireText(identity, "identity");
			bundleIdentity = requireText(bundleIdentity, "bundleIdentity");
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
