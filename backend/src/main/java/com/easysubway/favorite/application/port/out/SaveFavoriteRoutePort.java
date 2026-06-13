package com.easysubway.favorite.application.port.out;

import com.easysubway.favorite.domain.FavoriteRoute;

public interface SaveFavoriteRoutePort {

	FavoriteRoute saveFavoriteRoute(FavoriteRoute favoriteRoute);
}
