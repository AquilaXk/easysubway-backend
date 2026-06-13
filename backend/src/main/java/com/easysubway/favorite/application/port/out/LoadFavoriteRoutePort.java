package com.easysubway.favorite.application.port.out;

import com.easysubway.favorite.domain.FavoriteRoute;
import java.util.List;
import java.util.Optional;

public interface LoadFavoriteRoutePort {

	List<FavoriteRoute> loadFavoriteRoutes(String userId);

	Optional<FavoriteRoute> loadFavoriteRoute(String userId, String routeSearchId);
}
