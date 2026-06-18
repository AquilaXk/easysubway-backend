package com.easysubway.operator.adapter.in.web;

import com.easysubway.common.web.ApiResponse;
import com.easysubway.route.adapter.in.web.RouteFeedbackDashboardAssembler;
import com.easysubway.route.adapter.in.web.RouteFeedbackDashboardView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class OperatorRouteFeedbackReportController {

	private final RouteFeedbackDashboardAssembler routeFeedbackDashboardAssembler;

	OperatorRouteFeedbackReportController(RouteFeedbackDashboardAssembler routeFeedbackDashboardAssembler) {
		this.routeFeedbackDashboardAssembler = routeFeedbackDashboardAssembler;
	}

	@GetMapping("/operator/api/route-feedback-report")
	ApiResponse<RouteFeedbackDashboardView> routeFeedbackReport() {
		return ApiResponse.ok(routeFeedbackDashboardAssembler.assemble());
	}
}
