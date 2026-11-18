package com.easysubway.operator.adapter.in.web;

import com.easysubway.notification.application.port.in.PushNotificationDashboardUseCase;
import com.easysubway.notification.domain.PushNotificationDashboardSummary;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class OperatorPushNotificationReportAssembler {

	private static final String OPERATOR_SAFE_FAILURE_REASON = "푸시 발송 처리 중 오류가 발생했습니다. 관리자 점검이 필요합니다.";

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
			safeFailureReason(summary.failedCount()),
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
					failedDescription(summary.failedCount()),
					summary.failedCount()
				)
			)
		);
	}

	private static String safeFailureReason(long failedCount) {
		return failedCount == 0 ? null : OPERATOR_SAFE_FAILURE_REASON;
	}

	private static String failedDescription(long failedCount) {
		if (failedCount == 0) {
			return "최근 실패 없음";
		}
		return OPERATOR_SAFE_FAILURE_REASON;
	}
}
