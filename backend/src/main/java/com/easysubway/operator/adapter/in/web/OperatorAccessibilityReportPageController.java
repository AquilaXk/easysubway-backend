package com.easysubway.operator.adapter.in.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class OperatorAccessibilityReportPageController {

	private final OperatorAccessibilityReportAssembler reportAssembler;

	OperatorAccessibilityReportPageController(OperatorAccessibilityReportAssembler reportAssembler) {
		this.reportAssembler = reportAssembler;
	}

	@GetMapping("/operator/accessibility-report/page")
	String accessibilityReportPage(Model model) {
		model.addAttribute("report", reportAssembler.assemble());
		return "operator/accessibility-report";
	}
}
