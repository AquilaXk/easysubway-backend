package com.easysubway.journey.application;

import java.time.Instant;
import java.util.BitSet;
import java.util.Set;

public final class UnavailableFacilityAvailabilityView implements FacilityAvailabilityView {
	public static final UnavailableFacilityAvailabilityView INSTANCE = new UnavailableFacilityAvailabilityView();

	private UnavailableFacilityAvailabilityView() {
	}

	@Override
	public boolean available() {
		return false;
	}

	@Override
	public Instant observedAt() {
		return null;
	}

	@Override
	public Set<String> blockedPathwayEdgeIds() {
		return Set.of();
	}

	@Override
	public BitSet blockedTransitionIds() {
		return new BitSet(0);
	}
}
