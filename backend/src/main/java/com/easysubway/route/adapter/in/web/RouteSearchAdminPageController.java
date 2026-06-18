package com.easysubway.route.adapter.in.web;

import com.easysubway.route.application.port.in.RouteSearchDashboardUseCase;
import com.easysubway.route.domain.RouteSearchDashboardSummary;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class RouteSearchAdminPageController {

	private final RouteSearchDashboardUseCase routeSearchDashboardUseCase;

	RouteSearchAdminPageController(RouteSearchDashboardUseCase routeSearchDashboardUseCase) {
		this.routeSearchDashboardUseCase = routeSearchDashboardUseCase;
	}

	@GetMapping("/admin/routes/searches/page")
	String routeSearchDashboardPage(Model model) {
		RouteSearchDashboardSummary summary = routeSearchDashboardUseCase.summarizeRouteSearches();
		model.addAttribute("summary", RouteSearchDashboardView.from(summary));
		return "admin/routes/searches";
	}
}
