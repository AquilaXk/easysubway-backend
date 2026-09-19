package com.easysubway.journey.application;

import java.time.Instant;

@FunctionalInterface
public interface JourneyProfileSnapshotPort {
	ActiveJourneySnapshotPort.ActiveJourneySnapshot requireActive(
		JourneyRaptorQuery query,
		Instant freshnessReference,
		JourneyRequestMeasurement requestMeasurement
	);
}
