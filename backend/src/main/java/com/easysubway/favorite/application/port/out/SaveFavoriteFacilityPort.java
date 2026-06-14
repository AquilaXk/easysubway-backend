package com.easysubway.favorite.application.port.out;

import com.easysubway.favorite.domain.FavoriteFacility;

public interface SaveFavoriteFacilityPort {

	FavoriteFacility saveFavoriteFacility(FavoriteFacility favoriteFacility);
}
