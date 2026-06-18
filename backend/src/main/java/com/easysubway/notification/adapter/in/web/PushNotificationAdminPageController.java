package com.easysubway.notification.adapter.in.web;

import com.easysubway.notification.application.port.in.PushNotificationDashboardUseCase;
import com.easysubway.notification.domain.PushNotificationDashboardSummary;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class PushNotificationAdminPageController {

	private final PushNotificationDashboardUseCase pushNotificationDashboardUseCase;

	PushNotificationAdminPageController(PushNotificationDashboardUseCase pushNotificationDashboardUseCase) {
		this.pushNotificationDashboardUseCase = pushNotificationDashboardUseCase;
	}

	@GetMapping("/admin/notifications/push/page")
	String pushNotificationDashboardPage(Model model) {
		PushNotificationDashboardSummary summary = pushNotificationDashboardUseCase.summarizePushNotifications();
		model.addAttribute("summary", PushNotificationDashboardView.from(summary));
		return "admin/notifications/push";
	}
}
