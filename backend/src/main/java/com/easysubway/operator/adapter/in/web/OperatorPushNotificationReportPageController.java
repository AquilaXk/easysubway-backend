package com.easysubway.operator.adapter.in.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class OperatorPushNotificationReportPageController {

	private final OperatorPushNotificationReportAssembler pushNotificationReportAssembler;

	OperatorPushNotificationReportPageController(
		OperatorPushNotificationReportAssembler pushNotificationReportAssembler
	) {
		this.pushNotificationReportAssembler = pushNotificationReportAssembler;
	}

	@GetMapping("/operator/push-notification-report/page")
	String pushNotificationReportPage(Model model) {
		model.addAttribute("report", pushNotificationReportAssembler.assemble());
		return "operator/push-notification-report";
	}
}
