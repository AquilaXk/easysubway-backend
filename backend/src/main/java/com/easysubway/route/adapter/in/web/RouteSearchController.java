package com.easysubway.route.adapter.in.web;

import com.easysubway.common.web.ApiResponse;
import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.application.port.in.RouteSearchUseCase;
import com.easysubway.route.application.port.in.SearchInternalRouteCommand;
import com.easysubway.route.application.port.in.SearchRouteCommand;
import com.easysubway.route.application.port.in.SubmitRouteFeedbackCommand;
import com.easysubway.route.domain.InternalRouteResult;
import com.easysubway.route.domain.RouteFeedback;
import com.easysubway.route.domain.RouteFeedbackRating;
import com.easysubway.route.domain.RouteSearchResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
// 경로 검색은 사용자별 저장 데이터 없이 역과 이동 조건만 계산하므로 로그인 전 탐색 화면에서도 호출한다.
class RouteSearchController {

	private final RouteSearchUseCase routeSearchUseCase;

	RouteSearchController(RouteSearchUseCase routeSearchUseCase) {
		this.routeSearchUseCase = routeSearchUseCase;
	}

	@PostMapping("/api/v1/routes/search")
	ApiResponse<RouteSearchResult> searchRoute(@RequestBody SearchRouteRequest request) {
		return ApiResponse.ok(routeSearchUseCase.searchRoute(request.toCommand()));
	}

	@PostMapping("/api/v1/routes/internal")
	ApiResponse<InternalRouteResult> searchInternalRoute(@RequestBody SearchInternalRouteRequest request) {
		return ApiResponse.ok(routeSearchUseCase.searchInternalRoute(request.toCommand()));
	}

	@GetMapping("/api/v1/routes/{routeSearchId}")
	ApiResponse<RouteSearchResult> getRouteSearch(@PathVariable String routeSearchId) {
		return ApiResponse.ok(routeSearchUseCase.getRouteSearch(routeSearchId));
	}

	@PostMapping("/api/v1/routes/{routeSearchId}/feedback")
	ApiResponse<RouteFeedback> submitRouteFeedback(
		@PathVariable String routeSearchId,
		@RequestBody SubmitRouteFeedbackRequest request
	) {
		return ApiResponse.ok(routeSearchUseCase.submitRouteFeedback(request.toCommand(routeSearchId)));
	}

	record SearchRouteRequest(
		String originStationId,
		String destinationStationId,
		MobilityType mobilityType
	) {

		SearchRouteCommand toCommand() {
			return new SearchRouteCommand(originStationId, destinationStationId, mobilityType);
		}
	}

	record SearchInternalRouteRequest(
		String stationId,
		String fromNodeId,
		String toNodeId,
		MobilityType mobilityType
	) {

		SearchInternalRouteCommand toCommand() {
			return new SearchInternalRouteCommand(stationId, fromNodeId, toNodeId, mobilityType);
		}
	}

	record SubmitRouteFeedbackRequest(
		String userId,
		RouteFeedbackRating rating,
		String comment
	) {

		SubmitRouteFeedbackCommand toCommand(String routeSearchId) {
			return new SubmitRouteFeedbackCommand(routeSearchId, userId, rating, comment);
		}
	}
}
