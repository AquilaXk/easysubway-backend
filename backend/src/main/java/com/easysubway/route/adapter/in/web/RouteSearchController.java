package com.easysubway.route.adapter.in.web;

import com.easysubway.common.web.ApiResponse;
import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.application.port.in.RouteSearchUseCase;
import com.easysubway.route.application.port.in.SearchRouteCommand;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteSearchStatus;
import com.easysubway.route.domain.RouteStep;
import com.easysubway.route.domain.RouteWarning;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RouteSearchController {

	private final RouteSearchUseCase routeSearchUseCase;

	RouteSearchController(RouteSearchUseCase routeSearchUseCase) {
		this.routeSearchUseCase = routeSearchUseCase;
	}

	@PostMapping("/api/v1/routes/search")
	ApiResponse<RouteSearchV1Response> searchRoute(@Valid @RequestBody RouteSearchRequest request) {
		return ApiResponse.ok(RouteSearchV1Response.from(routeSearchUseCase.searchRoute(request.toCommand())));
	}

	private record RouteSearchRequest(
		@NotBlank(message = "출발역을 선택해야 합니다.")
		String originStationId,
		@NotBlank(message = "도착역을 선택해야 합니다.")
		String destinationStationId,
		@NotNull(message = "이동 유형을 선택해야 합니다.")
		MobilityType mobilityType
	) {

		SearchRouteCommand toCommand() {
			return new SearchRouteCommand(originStationId, destinationStationId, mobilityType);
		}
	}

	private record RouteSearchV1Response(
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
		int burdenCost,
		int estimatedDurationSeconds,
		int walkingDistanceMeters,
		int transferCount,
		List<RouteStep> steps,
		List<RouteWarning> warnings,
		List<String> blockedReasons,
		List<String> recommendationReasons,
		List<String> evidenceSummary,
		LocalDateTime createdAt,
		String etaSource,
		String routeQuality,
		boolean commercialEtaEligible
	) {

		private static RouteSearchV1Response from(RouteSearchResult result) {
			return new RouteSearchV1Response(
				result.routeSearchId(),
				result.originStationId(),
				result.originStationName(),
				result.destinationStationId(),
				result.destinationStationName(),
				result.mobilityType(),
				result.status(),
				result.lineId(),
				result.lineName(),
				result.score(),
				result.burdenCost(),
				result.estimatedDurationSeconds(),
				result.walkingDistanceMeters(),
				result.transferCount(),
				result.steps(),
				result.warnings(),
				result.blockedReasons(),
				result.recommendationReasons(),
				result.evidenceSummary(),
				result.createdAt(),
				"STATIC_BACKEND_V1",
				"LEGACY_STATIC",
				false
			);
		}
	}
}
