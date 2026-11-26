package com.easysubway.journey.application;

import java.time.Instant;
import java.util.Set;

public record SimpleFacilityAvailabilityView(
	boolean available,
	Instant observedAt,
	Set<String> blockedPathwayEdgeIds,
	Set<String> internalBlockedPathwayEdgeIds
) implements FacilityAvailabilityView {
	public SimpleFacilityAvailabilityView(
		boolean available,
		Instant observedAt,
		Set<String> blockedPathwayEdgeIds
	) {
		this(available, observedAt, blockedPathwayEdgeIds, Set.of());
	}

	public SimpleFacilityAvailabilityView {
		blockedPathwayEdgeIds = blockedPathwayEdgeIds == null ? Set.of() : Set.copyOf(blockedPathwayEdgeIds);
		internalBlockedPathwayEdgeIds = internalBlockedPathwayEdgeIds == null
			? Set.of()
			: Set.copyOf(internalBlockedPathwayEdgeIds);
	}
}
