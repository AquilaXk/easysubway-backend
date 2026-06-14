package com.easysubway.notification.application.port.in;

import com.easysubway.notification.domain.InvalidPushNotificationException;
import com.easysubway.report.domain.FacilityReportStatus;

public record ReportStatusChangedAlertCommand(
	String userId,
	String reportId,
	FacilityReportStatus status
) {

	public ReportStatusChangedAlertCommand {
		if (userId == null || userId.isBlank()) {
			throw new InvalidPushNotificationException("사용자 식별자가 필요합니다.");
		}
		if (reportId == null || reportId.isBlank()) {
			throw new InvalidPushNotificationException("신고 식별자가 필요합니다.");
		}
		if (status == null) {
			throw new InvalidPushNotificationException("신고 상태를 선택해야 합니다.");
		}
		userId = userId.trim();
		reportId = reportId.trim();
	}
}
