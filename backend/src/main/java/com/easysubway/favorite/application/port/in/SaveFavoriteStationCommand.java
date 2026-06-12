package com.easysubway.favorite.application.port.in;

public record SaveFavoriteStationCommand(
	String userId,
	String stationId
) {
}
