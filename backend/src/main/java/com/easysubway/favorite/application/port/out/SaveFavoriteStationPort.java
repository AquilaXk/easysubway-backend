package com.easysubway.favorite.application.port.out;

import com.easysubway.favorite.domain.FavoriteStation;

public interface SaveFavoriteStationPort {

	FavoriteStation saveFavoriteStation(FavoriteStation favoriteStation);
}
