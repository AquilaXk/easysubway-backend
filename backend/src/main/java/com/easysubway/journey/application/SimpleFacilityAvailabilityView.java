package com.easysubway.journey.application;

import java.time.Instant;
import java.util.Set;

public record SimpleFacilityAvailabilityView(
	boolean available,
	Instant observedAt,
	Set<String> blockedPathwayEdgeIds
) implements FacilityAvailabilityView {
	public SimpleFacilityAvailabilityView {
		blockedPathwayEdgeIds = blockedPathwayEdgeIds == null ? Set.of() : Set.copyOf(blockedPathwayEdgeIds);
	}
}
