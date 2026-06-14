package com.easysubway.favorite.adapter.in.web;

import com.easysubway.common.web.ApiResponse;
import com.easysubway.favorite.application.port.in.FavoriteFacilityUseCase;
import com.easysubway.favorite.application.port.in.ListFavoriteFacilitiesCommand;
import com.easysubway.favorite.application.port.in.RemoveFavoriteFacilityCommand;
import com.easysubway.favorite.application.port.in.SaveFavoriteFacilityCommand;
import com.easysubway.favorite.domain.FavoriteFacilityWithDetails;
import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.AccessibilityFacilityType;
import com.easysubway.transit.domain.DataConfidenceLevel;
import com.easysubway.transit.domain.Station;
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
class FavoriteFacilityController {

	private final FavoriteFacilityUseCase favoriteFacilityUseCase;

	FavoriteFacilityController(FavoriteFacilityUseCase favoriteFacilityUseCase) {
		this.favoriteFacilityUseCase = favoriteFacilityUseCase;
	}

	@GetMapping("/api/v1/me/favorites/facilities")
	ApiResponse<List<FavoriteFacilityResponse>> listFavoriteFacilities(Principal principal) {
		List<FavoriteFacilityResponse> response = favoriteFacilityUseCase
			.listFavoriteFacilities(new ListFavoriteFacilitiesCommand(principal.getName()))
			.stream()
			.map(FavoriteFacilityResponse::from)
			.toList();
		return ApiResponse.ok(response);
	}

	@PutMapping("/api/v1/me/favorites/facilities/{facilityId}")
	ApiResponse<FavoriteFacilityResponse> saveFavoriteFacility(
		@PathVariable String facilityId,
		Principal principal
	) {
		FavoriteFacilityWithDetails favoriteFacility = favoriteFacilityUseCase.saveFavoriteFacility(
			new SaveFavoriteFacilityCommand(principal.getName(), facilityId)
		);
		return ApiResponse.ok(FavoriteFacilityResponse.from(favoriteFacility));
	}

	@DeleteMapping("/api/v1/me/favorites/facilities/{facilityId}")
	ApiResponse<Void> removeFavoriteFacility(
		@PathVariable String facilityId,
		Principal principal
	) {
		favoriteFacilityUseCase.removeFavoriteFacility(new RemoveFavoriteFacilityCommand(
			principal.getName(),
			facilityId
		));
		return ApiResponse.ok(null);
	}

	record FavoriteFacilityResponse(
		String userId,
		String facilityId,
		String stationId,
		String stationNameKo,
		String stationNameEn,
		String exitId,
		AccessibilityFacilityType type,
		String name,
		String floorFrom,
		String floorTo,
		String description,
		AccessibilityFacilityStatus status,
		DataConfidenceLevel dataConfidence,
		LocalDate lastUpdatedAt,
		LocalDateTime addedAt
	) {

		static FavoriteFacilityResponse from(FavoriteFacilityWithDetails favoriteFacility) {
			AccessibilityFacility facility = favoriteFacility.facility();
			Station station = favoriteFacility.station();
			return new FavoriteFacilityResponse(
				favoriteFacility.favoriteFacility().userId(),
				favoriteFacility.favoriteFacility().facilityId(),
				facility.stationId(),
				station.nameKo(),
				station.nameEn(),
				facility.exitId(),
				facility.type(),
				facility.name(),
				facility.floorFrom(),
				facility.floorTo(),
				facility.description(),
				facility.status(),
				facility.dataConfidence(),
				facility.lastUpdatedAt(),
				favoriteFacility.favoriteFacility().addedAt()
			);
		}
	}
}
