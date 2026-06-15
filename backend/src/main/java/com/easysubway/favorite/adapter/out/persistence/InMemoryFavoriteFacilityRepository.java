package com.easysubway.favorite.adapter.out.persistence;

import com.easysubway.favorite.application.port.out.DeleteFavoriteFacilityPort;
import com.easysubway.favorite.application.port.out.LoadFavoriteFacilityAlertTargetPort;
import com.easysubway.favorite.application.port.out.LoadFavoriteFacilityPort;
import com.easysubway.favorite.application.port.out.SaveFavoriteFacilityPort;
import com.easysubway.favorite.domain.FavoriteFacility;
import com.easysubway.user.application.port.out.DeleteUserFavoriteFacilityPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryFavoriteFacilityRepository implements
	LoadFavoriteFacilityPort,
	LoadFavoriteFacilityAlertTargetPort,
	SaveFavoriteFacilityPort,
	DeleteFavoriteFacilityPort,
	DeleteUserFavoriteFacilityPort {

	private final Map<String, Map<String, FavoriteFacility>> favoritesByUserId = new ConcurrentHashMap<>();

	@Override
	public List<FavoriteFacility> loadFavoriteFacilities(String userId) {
		return new ArrayList<>(favoritesByUserId.getOrDefault(userId, Map.of()).values());
	}

	@Override
	public Optional<FavoriteFacility> loadFavoriteFacility(String userId, String facilityId) {
		return Optional.ofNullable(favoritesByUserId.getOrDefault(userId, Map.of()).get(facilityId));
	}

	@Override
	public List<String> loadUserIdsByFavoriteFacilityId(String facilityId) {
		Objects.requireNonNull(facilityId, "시설 식별자가 필요합니다.");
		return favoritesByUserId.entrySet()
			.stream()
			.filter(entry -> entry.getValue().containsKey(facilityId))
			.map(Map.Entry::getKey)
			.sorted()
			.toList();
	}

	@Override
	public FavoriteFacility saveFavoriteFacility(FavoriteFacility favoriteFacility) {
		favoritesByUserId
			.computeIfAbsent(favoriteFacility.userId(), ignored -> new ConcurrentHashMap<>())
			.put(favoriteFacility.facilityId(), favoriteFacility);
		return favoriteFacility;
	}

	@Override
	public void deleteFavoriteFacility(String userId, String facilityId) {
		Map<String, FavoriteFacility> favorites = favoritesByUserId.get(userId);
		if (favorites != null) {
			favorites.remove(facilityId);
		}
	}

	@Override
	public int deleteFavoriteFacilitiesByUserId(String userId) {
		Map<String, FavoriteFacility> removed = favoritesByUserId.remove(userId);
		return removed == null ? 0 : removed.size();
	}
}
