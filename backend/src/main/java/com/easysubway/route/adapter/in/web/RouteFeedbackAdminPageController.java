package com.easysubway.route.adapter.in.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class RouteFeedbackAdminPageController {

	private final RouteFeedbackDashboardAssembler routeFeedbackDashboardAssembler;

	RouteFeedbackAdminPageController(RouteFeedbackDashboardAssembler routeFeedbackDashboardAssembler) {
		this.routeFeedbackDashboardAssembler = routeFeedbackDashboardAssembler;
	}

	@GetMapping("/admin/routes/feedback/page")
	String routeFeedbackDashboardPage(Model model) {
		model.addAttribute("summary", routeFeedbackDashboardAssembler.assemble());
		return "admin/routes/feedback";
	}
}
