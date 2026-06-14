package com.easysubway.favorite.application.port.out;

import com.easysubway.favorite.domain.FavoriteFacility;
import java.util.List;
import java.util.Optional;

public interface LoadFavoriteFacilityPort {

	List<FavoriteFacility> loadFavoriteFacilities(String userId);

	Optional<FavoriteFacility> loadFavoriteFacility(String userId, String facilityId);
}
