package com.easysubway.journey.application;

@FunctionalInterface
public interface FacilityAvailabilityPort {
	FacilityAvailabilityView currentView();

	static FacilityAvailabilityPort unavailable() {
		return () -> FacilityAvailabilityView.unavailable();
	}
}
