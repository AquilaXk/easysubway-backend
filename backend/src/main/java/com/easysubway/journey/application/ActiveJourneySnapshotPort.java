package com.easysubway.journey.application;

import java.time.Instant;
import java.util.Objects;

@FunctionalInterface
public interface ActiveJourneySnapshotPort {
	ActiveJourneySnapshot requireActive(Instant effectiveInstant);

	record ActiveJourneySnapshot(String identity, String bundleIdentity, long generation, boolean fresh) {
		public ActiveJourneySnapshot {
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
