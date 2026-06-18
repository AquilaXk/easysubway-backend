package com.easysubway.operator.adapter.in.web;

import com.easysubway.common.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class OperatorAccessibilityReportController {

	private final OperatorAccessibilityReportAssembler reportAssembler;

	OperatorAccessibilityReportController(OperatorAccessibilityReportAssembler reportAssembler) {
		this.reportAssembler = reportAssembler;
	}

	@GetMapping("/operator/api/accessibility-report")
	ApiResponse<OperatorAccessibilityReportView> accessibilityReport() {
		return ApiResponse.ok(reportAssembler.assemble());
	}
}
