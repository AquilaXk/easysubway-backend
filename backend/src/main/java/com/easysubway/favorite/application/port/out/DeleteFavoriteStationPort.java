package com.easysubway.favorite.application.port.out;

public interface DeleteFavoriteStationPort {

	void deleteFavoriteStation(String userId, String stationId);
}
