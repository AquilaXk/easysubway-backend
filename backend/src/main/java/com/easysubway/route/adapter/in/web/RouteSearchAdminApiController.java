package com.easysubway.route.adapter.in.web;

import com.easysubway.common.web.ApiResponse;
import com.easysubway.route.application.port.in.RouteSearchDashboardUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RouteSearchAdminApiController {

	private final RouteSearchDashboardUseCase routeSearchDashboardUseCase;

	RouteSearchAdminApiController(RouteSearchDashboardUseCase routeSearchDashboardUseCase) {
		this.routeSearchDashboardUseCase = routeSearchDashboardUseCase;
	}

	@GetMapping("/admin/routes/searches/summary")
	ApiResponse<RouteSearchDashboardView> routeSearchSummary() {
		return ApiResponse.ok(RouteSearchDashboardView.from(routeSearchDashboardUseCase.summarizeRouteSearches()));
	}
}
