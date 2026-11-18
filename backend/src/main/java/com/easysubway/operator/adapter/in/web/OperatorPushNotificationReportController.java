package com.easysubway.operator.adapter.in.web;

import com.easysubway.common.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class OperatorPushNotificationReportController {

	private final OperatorPushNotificationReportAssembler pushNotificationReportAssembler;

	OperatorPushNotificationReportController(
		OperatorPushNotificationReportAssembler pushNotificationReportAssembler
	) {
		this.pushNotificationReportAssembler = pushNotificationReportAssembler;
	}

	@GetMapping("/operator/api/push-notification-report")
	ApiResponse<OperatorPushNotificationReportView> pushNotificationReport() {
		return ApiResponse.ok(pushNotificationReportAssembler.assemble());
	}
}
