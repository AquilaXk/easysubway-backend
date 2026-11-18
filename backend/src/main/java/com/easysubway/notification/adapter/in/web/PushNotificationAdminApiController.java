package com.easysubway.notification.adapter.in.web;

import com.easysubway.common.web.ApiResponse;
import com.easysubway.notification.application.port.in.PushNotificationDashboardUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PushNotificationAdminApiController {

	private final PushNotificationDashboardUseCase pushNotificationDashboardUseCase;

	PushNotificationAdminApiController(PushNotificationDashboardUseCase pushNotificationDashboardUseCase) {
		this.pushNotificationDashboardUseCase = pushNotificationDashboardUseCase;
	}

	@GetMapping("/admin/notifications/push/summary")
	ApiResponse<PushNotificationDashboardView> pushNotificationSummary() {
		return ApiResponse.ok(PushNotificationDashboardView.from(pushNotificationDashboardUseCase.summarizePushNotifications()));
	}
}
