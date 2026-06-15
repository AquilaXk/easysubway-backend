package com.easysubway.user.application.port.out;

public interface DeleteUserFavoriteStationPort {

	int deleteFavoriteStationsByUserId(String userId);
}
