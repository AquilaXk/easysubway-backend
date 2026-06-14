package com.easysubway.transit.domain;

public record NearbyStation(
	StationWithLines stationWithLines,
	int distanceMeters
) {
}
