package com.easysubway.journey.application;

import java.util.List;
import java.util.Objects;

public sealed interface JourneyExecutionResult permits JourneyExecutionResult.Success, JourneyExecutionFailure {

	enum Source {
		SERVER_TIMETABLE_RAPTOR
	}

	record Success(Source source, String bundleIdentity, String realtimeIdentity, List<String> candidates)
		implements JourneyExecutionResult {
		public Success {
			if (source != Source.SERVER_TIMETABLE_RAPTOR) {
				throw new IllegalArgumentException("source must be SERVER_TIMETABLE_RAPTOR");
			}
			bundleIdentity = requireText(bundleIdentity, "bundleIdentity");
			candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
			if (candidates.isEmpty()) {
				throw new IllegalArgumentException("candidates must not be empty");
			}
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
