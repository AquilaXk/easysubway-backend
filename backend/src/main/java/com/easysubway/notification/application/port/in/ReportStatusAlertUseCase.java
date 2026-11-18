package com.easysubway.notification.application.port.in;

public interface ReportStatusAlertUseCase {

	void alertReportStatusChanged(ReportStatusChangedAlertCommand command);
}
