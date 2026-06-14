package com.easysubway.favorite.domain;

import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.Station;

public record FavoriteFacilityWithDetails(
	FavoriteFacility favoriteFacility,
	AccessibilityFacility facility,
	Station station
) {
}
