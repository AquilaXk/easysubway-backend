package com.easysubway.favorite.adapter.out.persistence;

import com.easysubway.favorite.application.port.out.DeleteFavoriteRoutePort;
import com.easysubway.favorite.application.port.out.LoadFavoriteRoutePort;
import com.easysubway.favorite.application.port.out.SaveFavoriteRoutePort;
import com.easysubway.favorite.domain.FavoriteRoute;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryFavoriteRouteRepository implements
	LoadFavoriteRoutePort,
	SaveFavoriteRoutePort,
	DeleteFavoriteRoutePort {

	static final int MAX_FAVORITE_ROUTES_PER_USER = 100;

	private final Map<String, Map<String, FavoriteRoute>> favoritesByUserId = new LinkedHashMap<>();

	@Override
	public synchronized List<FavoriteRoute> loadFavoriteRoutes(String userId) {
		return new ArrayList<>(favoritesByUserId.getOrDefault(userId, Map.of()).values());
	}

	@Override
	public synchronized Optional<FavoriteRoute> loadFavoriteRoute(String userId, String routeSearchId) {
		return Optional.ofNullable(favoritesByUserId.getOrDefault(userId, Map.of()).get(routeSearchId));
	}

	@Override
	public synchronized FavoriteRoute saveFavoriteRoute(FavoriteRoute favoriteRoute) {
		Map<String, FavoriteRoute> favorites = favoritesByUserId
			.computeIfAbsent(favoriteRoute.userId(), ignored -> new LinkedHashMap<>());
		favorites.put(favoriteRoute.routeSearchId(), favoriteRoute);
		evictOldestFavoriteRoutes(favorites);
		return favoriteRoute;
	}

	@Override
	public synchronized void deleteFavoriteRoute(String userId, String routeSearchId) {
		Map<String, FavoriteRoute> favorites = favoritesByUserId.get(userId);
		if (favorites != null) {
			favorites.remove(routeSearchId);
		}
	}

	private void evictOldestFavoriteRoutes(Map<String, FavoriteRoute> favorites) {
		// 경로 즐겨찾기는 검색할 때마다 새 ID가 생기므로 사용자별 인메모리 보관량을 제한한다.
		while (favorites.size() > MAX_FAVORITE_ROUTES_PER_USER) {
			String oldestRouteSearchId = favorites.keySet().iterator().next();
			favorites.remove(oldestRouteSearchId);
		}
	}
}
