package com.easysubway.favorite.adapter.in.web;

import com.easysubway.common.web.ApiResponse;
import com.easysubway.favorite.application.port.in.FavoriteRouteUseCase;
import com.easysubway.favorite.application.port.in.ListFavoriteRoutesCommand;
import com.easysubway.favorite.application.port.in.RemoveFavoriteRouteCommand;
import com.easysubway.favorite.application.port.in.SaveFavoriteRouteCommand;
import com.easysubway.favorite.domain.FavoriteRouteWithDetails;
import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.domain.RouteSearchStatus;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class FavoriteRouteController {

	private final FavoriteRouteUseCase favoriteRouteUseCase;

	FavoriteRouteController(FavoriteRouteUseCase favoriteRouteUseCase) {
		this.favoriteRouteUseCase = favoriteRouteUseCase;
	}

	@GetMapping("/api/v1/me/favorites/routes")
	ApiResponse<List<FavoriteRouteResponse>> listFavoriteRoutes(Principal principal) {
		List<FavoriteRouteResponse> response = favoriteRouteUseCase
			.listFavoriteRoutes(new ListFavoriteRoutesCommand(principal.getName()))
			.stream()
			.map(FavoriteRouteResponse::from)
			.toList();
		return ApiResponse.ok(response);
	}

	@PostMapping("/api/v1/me/favorites/routes")
	ApiResponse<FavoriteRouteResponse> saveFavoriteRoute(
		@RequestBody SaveFavoriteRouteRequest request,
		Principal principal
	) {
		FavoriteRouteWithDetails favoriteRoute = favoriteRouteUseCase.saveFavoriteRoute(
			new SaveFavoriteRouteCommand(principal.getName(), request.routeSearchId())
		);
		return ApiResponse.ok(FavoriteRouteResponse.from(favoriteRoute));
	}

	@DeleteMapping("/api/v1/me/favorites/routes/{favoriteRouteId}")
	ApiResponse<Void> removeFavoriteRoute(
		@PathVariable String favoriteRouteId,
		Principal principal
	) {
		favoriteRouteUseCase.removeFavoriteRoute(new RemoveFavoriteRouteCommand(principal.getName(), favoriteRouteId));
		return ApiResponse.ok(null);
	}

	record SaveFavoriteRouteRequest(
		String userId,
		String routeSearchId
	) {
	}

	record FavoriteRouteResponse(
		String userId,
		String favoriteRouteId,
		String routeSearchId,
		String originStationId,
		String originStationName,
		String destinationStationId,
		String destinationStationName,
		MobilityType mobilityType,
		RouteSearchStatus status,
		String lineId,
		String lineName,
		int score,
		LocalDateTime routeCreatedAt,
		LocalDateTime addedAt
	) {

		static FavoriteRouteResponse from(FavoriteRouteWithDetails favoriteRoute) {
			var route = favoriteRoute.route();
			return new FavoriteRouteResponse(
				favoriteRoute.favoriteRoute().userId(),
				favoriteRoute.favoriteRoute().routeSearchId(),
				route.routeSearchId(),
				route.originStationId(),
				route.originStationName(),
				route.destinationStationId(),
				route.destinationStationName(),
				route.mobilityType(),
				route.status(),
				route.lineId(),
				route.lineName(),
				route.score(),
				route.createdAt(),
				favoriteRoute.favoriteRoute().addedAt()
			);
		}
	}
}
