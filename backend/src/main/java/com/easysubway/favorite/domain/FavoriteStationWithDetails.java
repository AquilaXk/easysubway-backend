package com.easysubway.favorite.domain;

import com.easysubway.transit.domain.StationWithLines;

public record FavoriteStationWithDetails(
	FavoriteStation favoriteStation,
	StationWithLines station
) {
}
