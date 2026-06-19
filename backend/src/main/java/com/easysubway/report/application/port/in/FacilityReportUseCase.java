package com.easysubway.report.application.port.in;

import com.easysubway.common.domain.PageResult;
import com.easysubway.report.domain.FacilityReport;
import com.easysubway.report.domain.FacilityReportReviewAudit;
import com.easysubway.report.domain.FacilityReportSummary;
import com.easysubway.report.domain.FacilityReportStatus;
import com.easysubway.report.domain.RepeatedBrokenFacilityReportSummary;
import com.easysubway.report.domain.ReportProcessingTimeSummary;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public interface FacilityReportUseCase {

	FacilityReport createReport(CreateFacilityReportCommand command);

	CreatedFacilityReport createReportWithReceipt(CreateFacilityReportCommand command);

	FacilityReport getReport(String reportId);

	FacilityReport getUserReport(String reportId, String userId);

	FacilityReport getReportByReceiptToken(String reportId, String receiptToken);

	List<FacilityReport> listUserReports(String userId);

	PageResult<FacilityReportSummary> listUserReportSummaries(
		String userId,
		FacilityReportPageRequest pageRequest
	);

	List<FacilityReport> listReports(FacilityReportStatus status);

	PageResult<FacilityReportSummary> listReportSummaries(
		FacilityReportStatus status,
		FacilityReportPageRequest pageRequest
	);

	Map<FacilityReportStatus, Long> countReportsByStatus();

	long countReportsCreatedSince(LocalDateTime cutoff);

	ReportProcessingTimeSummary summarizeReportProcessingTime();

	List<RepeatedBrokenFacilityReportSummary> listRepeatedBrokenReportFacilities();

	FacilityReport reviewReport(ReviewFacilityReportCommand command);

	FacilityReport confirmReportResult(String reportId, String userId);

	List<FacilityReportReviewAudit> listReviewAudits(String reportId);
}
