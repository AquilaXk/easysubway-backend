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

	record PlanResult(String queryId, List<JourneyCandidate> candidates, ScanMetrics scanMetrics) {
		public PlanResult {
			Objects.requireNonNull(queryId, "queryId");
			if (queryId.isBlank()) throw new IllegalArgumentException("queryId must not be blank");
			candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
			scanMetrics = Objects.requireNonNull(scanMetrics, "scanMetrics");
		}
	}

	record ScanMetrics(int expandedRoutes, int expandedTrips, int expandedTransfers) {
		public ScanMetrics {
			if (expandedRoutes < 0 || expandedTrips < 0 || expandedTransfers < 0) {
				throw new IllegalArgumentException("scan metrics must not be negative");
			}
		}
	}
}
