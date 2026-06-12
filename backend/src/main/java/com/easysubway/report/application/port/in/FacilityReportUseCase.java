package com.easysubway.report.application.port.in;

import com.easysubway.report.domain.FacilityReport;

public interface FacilityReportUseCase {

	FacilityReport createReport(CreateFacilityReportCommand command);

	FacilityReport getReport(String reportId);
}
