package com.easysubway.journey.application;

import java.time.Instant;
import java.util.BitSet;
import java.util.Set;

public interface FacilityAvailabilityView {
	boolean available();

	Instant observedAt();

	Set<String> blockedPathwayEdgeIds();

	BitSet blockedTransitionIds();

	static FacilityAvailabilityView unavailable() {
		return UnavailableFacilityAvailabilityView.INSTANCE;
	}

	static FacilityAvailabilityView empty(Instant observedAt) {
		return new SimpleFacilityAvailabilityView(true, observedAt, Set.of(), new BitSet(0));
	}

	static FacilityAvailabilityView blocked(Instant observedAt, Set<String> blockedPathwayEdgeIds) {
		return new SimpleFacilityAvailabilityView(true, observedAt, blockedPathwayEdgeIds, new BitSet(0));
	}

	static FacilityAvailabilityView blocked(
		Instant observedAt,
		Set<String> blockedPathwayEdgeIds,
		BitSet blockedTransitionIds
	) {
		return new SimpleFacilityAvailabilityView(true, observedAt, blockedPathwayEdgeIds, blockedTransitionIds);
	}
}
