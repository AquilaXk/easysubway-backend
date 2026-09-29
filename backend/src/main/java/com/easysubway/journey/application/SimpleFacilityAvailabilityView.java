package com.easysubway.journey.application;

import java.time.Instant;
import java.util.BitSet;
import java.util.Set;

public record SimpleFacilityAvailabilityView(
	boolean available,
	Instant observedAt,
	Set<String> blockedPathwayEdgeIds,
	BitSet blockedTransitionIds
) implements FacilityAvailabilityView {
	public SimpleFacilityAvailabilityView {
		blockedPathwayEdgeIds = blockedPathwayEdgeIds == null ? Set.of() : Set.copyOf(blockedPathwayEdgeIds);
		blockedTransitionIds = blockedTransitionIds == null ? new BitSet(0) : (BitSet) blockedTransitionIds.clone();
	}

	@Override
	public BitSet blockedTransitionIds() {
		return (BitSet) blockedTransitionIds.clone();
	}
}
