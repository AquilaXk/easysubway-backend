package com.easysubway.notification.application.service;

import com.easysubway.notification.application.port.in.DispatchPushNotificationCommand;
import com.easysubway.notification.application.port.in.PushNotificationDispatchUseCase;
import com.easysubway.notification.application.port.in.ReportStatusAlertUseCase;
import com.easysubway.notification.application.port.in.ReportStatusChangedAlertCommand;
import com.easysubway.notification.domain.PushNotificationType;
import com.easysubway.report.domain.FacilityReportStatus;
import org.springframework.stereotype.Service;

@Service
public class ReportStatusAlertService implements ReportStatusAlertUseCase {

	private final PushNotificationDispatchUseCase pushNotificationDispatchUseCase;

	public ReportStatusAlertService(PushNotificationDispatchUseCase pushNotificationDispatchUseCase) {
		this.pushNotificationDispatchUseCase = pushNotificationDispatchUseCase;
	}

	@Override
	public void alertReportStatusChanged(ReportStatusChangedAlertCommand command) {
		pushNotificationDispatchUseCase.dispatch(new DispatchPushNotificationCommand(
			command.userId(),
			PushNotificationType.REPORT_STATUS,
			"신고 처리 결과",
			body(command.status()),
			"report-status:%s:%s".formatted(command.reportId(), command.status().name())
		));
	}

	private String body(FacilityReportStatus status) {
		return switch (status) {
			case ACCEPTED -> "제보해 주신 신고가 확인되어 시설 정보에 반영되었습니다.";
			case REJECTED -> "제보해 주신 신고를 검토했지만 이번에는 반영되지 않았습니다.";
			case DUPLICATE -> "제보해 주신 신고는 이미 접수된 내용과 같아 중복으로 정리되었습니다.";
			case UNDER_REVIEW -> "제보해 주신 신고를 검토하고 있습니다.";
			case RESOLVED -> "제보해 주신 신고 처리가 완료되었습니다.";
			case SUBMITTED -> "제보해 주신 신고가 접수되어 확인을 기다리고 있습니다.";
		};
	}
}
