package com.easysubway.report.application.port.in;

import com.easysubway.report.domain.FacilityReport;
import com.easysubway.report.domain.FacilityReportStatus;
import java.util.List;

public interface FacilityReportUseCase {

	FacilityReport createReport(CreateFacilityReportCommand command);

	FacilityReport getReport(String reportId);

	List<FacilityReport> listReports(FacilityReportStatus status);

	FacilityReport reviewReport(ReviewFacilityReportCommand command);
}
