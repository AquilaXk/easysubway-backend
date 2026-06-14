package com.easysubway.favorite.application.port.in;

import com.easysubway.favorite.domain.FavoriteFacilityWithDetails;
import java.util.List;

public interface FavoriteFacilityUseCase {

	List<FavoriteFacilityWithDetails> listFavoriteFacilities(ListFavoriteFacilitiesCommand command);

	FavoriteFacilityWithDetails saveFavoriteFacility(SaveFavoriteFacilityCommand command);

	void removeFavoriteFacility(RemoveFavoriteFacilityCommand command);
}
