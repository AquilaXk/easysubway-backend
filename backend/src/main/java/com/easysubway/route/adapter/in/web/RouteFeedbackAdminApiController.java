package com.easysubway.route.adapter.in.web;

import com.easysubway.common.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RouteFeedbackAdminApiController {

	private final RouteFeedbackDashboardAssembler routeFeedbackDashboardAssembler;

	RouteFeedbackAdminApiController(RouteFeedbackDashboardAssembler routeFeedbackDashboardAssembler) {
		this.routeFeedbackDashboardAssembler = routeFeedbackDashboardAssembler;
	}

	@GetMapping("/admin/routes/feedback/summary")
	ApiResponse<RouteFeedbackDashboardView> routeFeedbackSummary() {
		return ApiResponse.ok(routeFeedbackDashboardAssembler.assemble());
	}
}
