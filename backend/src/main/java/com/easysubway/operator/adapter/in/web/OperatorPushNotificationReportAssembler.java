package com.easysubway.operator.adapter.in.web;

import com.easysubway.notification.application.port.in.PushNotificationDashboardUseCase;
import com.easysubway.notification.domain.PushNotificationDashboardSummary;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class OperatorPushNotificationReportAssembler {

	private final PushNotificationDashboardUseCase pushNotificationDashboardUseCase;

	OperatorPushNotificationReportAssembler(PushNotificationDashboardUseCase pushNotificationDashboardUseCase) {
		this.pushNotificationDashboardUseCase = pushNotificationDashboardUseCase;
	}

	OperatorPushNotificationReportView assemble() {
		PushNotificationDashboardSummary summary = pushNotificationDashboardUseCase.summarizePushNotifications();
		return new OperatorPushNotificationReportView(
			summary.totalCount(),
			summary.pendingCount(),
			summary.sentCount(),
			summary.failedCount(),
			summary.latestFailureReason(),
			List.of(
				new OperatorPushNotificationReportView.StatusCountRow(
					"대기 중",
					"아직 발송 처리 전",
					summary.pendingCount()
				),
				new OperatorPushNotificationReportView.StatusCountRow(
					"발송 완료",
					"외부 발송 성공",
					summary.sentCount()
				),
				new OperatorPushNotificationReportView.StatusCountRow(
					"발송 실패",
					failedDescription(summary.latestFailureReason()),
					summary.failedCount()
				)
			)
		);
	}

	private static String failedDescription(String latestFailureReason) {
		if (latestFailureReason == null || latestFailureReason.isBlank()) {
			return "발송 어댑터 실패 또는 예외";
		}
		return "발송 어댑터 실패 또는 예외 · 최근 실패: " + latestFailureReason;
	}
}
