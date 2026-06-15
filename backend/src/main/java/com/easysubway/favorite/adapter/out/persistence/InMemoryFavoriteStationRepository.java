package com.easysubway.favorite.adapter.out.persistence;

import com.easysubway.favorite.application.port.out.DeleteFavoriteStationPort;
import com.easysubway.favorite.application.port.out.LoadFavoriteStationAlertTargetPort;
import com.easysubway.favorite.application.port.out.LoadFavoriteStationPort;
import com.easysubway.favorite.application.port.out.SaveFavoriteStationPort;
import com.easysubway.favorite.domain.FavoriteStation;
import com.easysubway.user.application.port.out.DeleteUserFavoriteStationPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryFavoriteStationRepository implements
	LoadFavoriteStationPort,
	LoadFavoriteStationAlertTargetPort,
	SaveFavoriteStationPort,
	DeleteFavoriteStationPort,
	DeleteUserFavoriteStationPort {

	private final Map<String, Map<String, FavoriteStation>> favoritesByUserId = new ConcurrentHashMap<>();

	@Override
	public List<FavoriteStation> loadFavoriteStations(String userId) {
		return new ArrayList<>(favoritesByUserId.getOrDefault(userId, Map.of()).values());
	}

	@Override
	public Optional<FavoriteStation> loadFavoriteStation(String userId, String stationId) {
		return Optional.ofNullable(favoritesByUserId.getOrDefault(userId, Map.of()).get(stationId));
	}

	@Override
	public List<String> loadUserIdsByFavoriteStationId(String stationId) {
		Objects.requireNonNull(stationId, "역 식별자가 필요합니다.");
		return favoritesByUserId.entrySet()
			.stream()
			.filter(entry -> entry.getValue().containsKey(stationId))
			.map(Map.Entry::getKey)
			.sorted()
			.toList();
	}

	@Override
	public FavoriteStation saveFavoriteStation(FavoriteStation favoriteStation) {
		favoritesByUserId
			.computeIfAbsent(favoriteStation.userId(), ignored -> new ConcurrentHashMap<>())
			.put(favoriteStation.stationId(), favoriteStation);
		return favoriteStation;
	}

	@Override
	public void deleteFavoriteStation(String userId, String stationId) {
		Map<String, FavoriteStation> favorites = favoritesByUserId.get(userId);
		if (favorites != null) {
			favorites.remove(stationId);
		}
	}

	@Override
	public int deleteFavoriteStationsByUserId(String userId) {
		Map<String, FavoriteStation> removed = favoritesByUserId.remove(userId);
		return removed == null ? 0 : removed.size();
	}
}
