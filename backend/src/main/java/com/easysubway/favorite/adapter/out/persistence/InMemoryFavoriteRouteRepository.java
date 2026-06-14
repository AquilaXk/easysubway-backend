package com.easysubway.favorite.adapter.out.persistence;

import com.easysubway.favorite.application.port.out.DeleteFavoriteRoutePort;
import com.easysubway.favorite.application.port.out.LoadFavoriteRouteAlertTargetPort;
import com.easysubway.favorite.application.port.out.LoadFavoriteRoutePort;
import com.easysubway.favorite.application.port.out.SaveFavoriteRoutePort;
import com.easysubway.favorite.domain.FavoriteRoute;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteStep;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryFavoriteRouteRepository implements
	LoadFavoriteRoutePort,
	LoadFavoriteRouteAlertTargetPort,
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
	public synchronized List<String> loadUserIdsByRouteStationId(String stationId) {
		Objects.requireNonNull(stationId, "역 식별자가 필요합니다.");
		return favoritesByUserId.entrySet()
			.stream()
			.filter(entry -> hasRouteTouchingStation(entry.getValue(), stationId))
			.map(Map.Entry::getKey)
			.sorted()
			.toList();
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

	private boolean hasRouteTouchingStation(Map<String, FavoriteRoute> favorites, String stationId) {
		return favorites.values()
			.stream()
			.anyMatch(favorite -> routeTouchesStation(favorite.route(), stationId));
	}

	private boolean routeTouchesStation(RouteSearchResult route, String stationId) {
		if (stationId.equals(route.originStationId()) || stationId.equals(route.destinationStationId())) {
			return true;
		}
		// 환승 경로는 중간 단계에 장애 알림 대상 역이 포함될 수 있어 모든 이동 구간을 확인한다.
		return route.steps()
			.stream()
			.anyMatch(step -> stepTouchesStation(step, stationId));
	}

	private boolean stepTouchesStation(RouteStep step, String stationId) {
		return stationId.equals(step.fromStationId()) || stationId.equals(step.toStationId());
	}
}
