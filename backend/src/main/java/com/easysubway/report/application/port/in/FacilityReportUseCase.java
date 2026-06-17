package com.easysubway.report.application.port.in;

import com.easysubway.report.domain.FacilityReport;
import com.easysubway.report.domain.FacilityReportReviewAudit;
import com.easysubway.report.domain.FacilityReportStatus;
import com.easysubway.report.domain.RepeatedBrokenFacilityReportSummary;
import java.util.List;
import java.util.Map;

public interface FacilityReportUseCase {

	FacilityReport createReport(CreateFacilityReportCommand command);

	FacilityReport getReport(String reportId);

	List<FacilityReport> listUserReports(String userId);

	List<FacilityReport> listReports(FacilityReportStatus status);

	Map<FacilityReportStatus, Long> countReportsByStatus();

	List<RepeatedBrokenFacilityReportSummary> listRepeatedBrokenReportFacilities();

	FacilityReport reviewReport(ReviewFacilityReportCommand command);

	List<FacilityReportReviewAudit> listReviewAudits(String reportId);
}
