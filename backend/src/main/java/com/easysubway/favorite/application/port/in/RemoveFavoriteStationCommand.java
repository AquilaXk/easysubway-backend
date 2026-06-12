package com.easysubway.favorite.application.port.in;

public record RemoveFavoriteStationCommand(
	String userId,
	String stationId
) {
}
