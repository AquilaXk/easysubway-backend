package com.easysubway.favorite.application.port.in;

import com.easysubway.favorite.domain.FavoriteStationWithDetails;
import java.util.List;

public interface FavoriteStationUseCase {

	List<FavoriteStationWithDetails> listFavoriteStations(ListFavoriteStationsCommand command);

	FavoriteStationWithDetails saveFavoriteStation(SaveFavoriteStationCommand command);

	void removeFavoriteStation(RemoveFavoriteStationCommand command);
}
