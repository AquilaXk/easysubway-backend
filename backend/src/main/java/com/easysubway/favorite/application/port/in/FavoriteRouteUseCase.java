package com.easysubway.favorite.application.port.in;

import com.easysubway.favorite.domain.FavoriteRouteWithDetails;
import java.util.List;

public interface FavoriteRouteUseCase {

	List<FavoriteRouteWithDetails> listFavoriteRoutes(ListFavoriteRoutesCommand command);

	FavoriteRouteWithDetails saveFavoriteRoute(SaveFavoriteRouteCommand command);

	void removeFavoriteRoute(RemoveFavoriteRouteCommand command);
}
