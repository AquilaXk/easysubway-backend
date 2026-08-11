package com.easysubway.journey.application;

import java.time.Instant;
import java.util.List;

@FunctionalInterface
public interface JourneyRaptorPort {
	List<String> plan(
		JourneyRequest request,
		ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot,
		Instant effectiveInstant,
		JourneyRealtimePort.RealtimeObservation realtimeOrNull
	);
}
