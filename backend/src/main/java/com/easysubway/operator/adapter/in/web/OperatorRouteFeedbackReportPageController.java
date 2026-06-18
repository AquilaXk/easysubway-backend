package com.easysubway.operator.adapter.in.web;

import com.easysubway.route.adapter.in.web.RouteFeedbackDashboardAssembler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class OperatorRouteFeedbackReportPageController {

	private final RouteFeedbackDashboardAssembler routeFeedbackDashboardAssembler;

	OperatorRouteFeedbackReportPageController(RouteFeedbackDashboardAssembler routeFeedbackDashboardAssembler) {
		this.routeFeedbackDashboardAssembler = routeFeedbackDashboardAssembler;
	}

	@GetMapping("/operator/route-feedback-report/page")
	String routeFeedbackReportPage(Model model) {
		model.addAttribute("summary", routeFeedbackDashboardAssembler.assemble());
		return "operator/route-feedback-report";
	}
}
