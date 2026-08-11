package com.easysubway.journey.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

@FunctionalInterface
public interface JourneyRaptorPort {
	PlanResult plan(
		JourneyRequest request,
		ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot,
		Instant effectiveInstant,
		JourneyRealtimePort.RealtimeObservation realtimeOrNull
	);

	record PlanResult(String queryId, List<JourneyCandidate> candidates) {
		public PlanResult {
			Objects.requireNonNull(queryId, "queryId");
			if (queryId.isBlank()) throw new IllegalArgumentException("queryId must not be blank");
			candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
		}
	}
}
