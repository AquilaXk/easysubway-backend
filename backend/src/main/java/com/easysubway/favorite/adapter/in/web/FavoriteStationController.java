package com.easysubway.favorite.adapter.in.web;

import com.easysubway.common.web.ApiResponse;
import com.easysubway.favorite.application.port.in.FavoriteStationUseCase;
import com.easysubway.favorite.application.port.in.ListFavoriteStationsCommand;
import com.easysubway.favorite.application.port.in.RemoveFavoriteStationCommand;
import com.easysubway.favorite.application.port.in.SaveFavoriteStationCommand;
import com.easysubway.favorite.domain.FavoriteStationWithDetails;
import com.easysubway.transit.domain.DataQualityLevel;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationLineSummary;
import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class FavoriteStationController {

	private final FavoriteStationUseCase favoriteStationUseCase;

	FavoriteStationController(FavoriteStationUseCase favoriteStationUseCase) {
		this.favoriteStationUseCase = favoriteStationUseCase;
	}

	@GetMapping("/api/v1/me/favorites/stations")
	ApiResponse<List<FavoriteStationResponse>> listFavoriteStations(Principal principal) {
		List<FavoriteStationResponse> response = favoriteStationUseCase
			.listFavoriteStations(new ListFavoriteStationsCommand(principal.getName()))
			.stream()
			.map(FavoriteStationResponse::from)
			.toList();
		return ApiResponse.ok(response);
	}

	@PutMapping("/api/v1/me/favorites/stations/{stationId}")
	ApiResponse<FavoriteStationResponse> saveFavoriteStation(
		@PathVariable String stationId,
		Principal principal
	) {
		FavoriteStationWithDetails favoriteStation = favoriteStationUseCase.saveFavoriteStation(
			new SaveFavoriteStationCommand(principal.getName(), stationId)
		);
		return ApiResponse.ok(FavoriteStationResponse.from(favoriteStation));
	}

	@DeleteMapping("/api/v1/me/favorites/stations/{stationId}")
	ApiResponse<Void> removeFavoriteStation(
		@PathVariable String stationId,
		Principal principal
	) {
		favoriteStationUseCase.removeFavoriteStation(new RemoveFavoriteStationCommand(principal.getName(), stationId));
		return ApiResponse.ok(null);
	}

	record FavoriteStationResponse(
		String userId,
		String stationId,
		String nameKo,
		String nameEn,
		String region,
		DataQualityLevel dataQualityLevel,
		LocalDate lastVerifiedAt,
		List<FavoriteStationLineResponse> lines,
		LocalDateTime addedAt
	) {

		static FavoriteStationResponse from(FavoriteStationWithDetails favoriteStation) {
			Station station = favoriteStation.station().station();
			return new FavoriteStationResponse(
				favoriteStation.favoriteStation().userId(),
				favoriteStation.favoriteStation().stationId(),
				station.nameKo(),
				station.nameEn(),
				station.region(),
				station.dataQualityLevel(),
				station.lastVerifiedAt(),
				favoriteStation.station().lines()
					.stream()
					.map(FavoriteStationLineResponse::from)
					.toList(),
				favoriteStation.favoriteStation().addedAt()
			);
		}
	}

	record FavoriteStationLineResponse(
		String id,
		String name,
		String color,
		String stationCode
	) {

		static FavoriteStationLineResponse from(StationLineSummary line) {
			return new FavoriteStationLineResponse(
				line.id(),
				line.name(),
				line.color(),
				line.stationCode()
			);
		}
	}
}
