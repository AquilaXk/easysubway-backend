package com.easysubway.notification.adapter.in.web;

import com.easysubway.notification.application.port.in.PushNotificationDashboardUseCase;
import com.easysubway.notification.domain.PushNotificationDashboardSummary;
import java.util.List;
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

	record PushNotificationDashboardView(
		long totalCount,
		long pendingCount,
		long sentCount,
		long failedCount,
		List<StatusCountRow> statusRows
	) {

		static PushNotificationDashboardView from(PushNotificationDashboardSummary summary) {
			return new PushNotificationDashboardView(
				summary.totalCount(),
				summary.pendingCount(),
				summary.sentCount(),
				summary.failedCount(),
				List.of(
					new StatusCountRow("대기 중", "아직 발송 처리 전", summary.pendingCount()),
					new StatusCountRow("발송 완료", "외부 발송 성공", summary.sentCount()),
					new StatusCountRow("발송 실패", "발송 어댑터 실패 또는 예외", summary.failedCount())
				)
			);
		}
	}

	record StatusCountRow(String label, String description, long count) {
	}
}
