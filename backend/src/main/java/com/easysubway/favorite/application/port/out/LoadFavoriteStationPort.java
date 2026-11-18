package com.easysubway.favorite.application.port.out;

import com.easysubway.favorite.domain.FavoriteStation;
import java.util.List;
import java.util.Optional;

public interface LoadFavoriteStationPort {

	List<FavoriteStation> loadFavoriteStations(String userId);

	Optional<FavoriteStation> loadFavoriteStation(String userId, String stationId);
}
