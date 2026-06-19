package com.easysubway.report.application.port.out;

import com.easysubway.common.domain.PageResult;
import com.easysubway.report.application.port.in.FacilityReportPageRequest;
import com.easysubway.report.domain.FacilityReport;
import com.easysubway.report.domain.FacilityReportSummary;
import com.easysubway.report.domain.FacilityReportStatus;
import com.easysubway.report.domain.RepeatedBrokenFacilityReportSummary;
import com.easysubway.report.domain.ReportProcessingTimeSummary;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface LoadFacilityReportPort {

	Optional<FacilityReport> loadReport(String reportId);

	List<FacilityReport> loadReports();

	PageResult<FacilityReportSummary> loadUserReportSummaries(String userId, FacilityReportPageRequest pageRequest);

	PageResult<FacilityReportSummary> loadReportSummaries(
		FacilityReportStatus status,
		FacilityReportPageRequest pageRequest
	);

	Map<FacilityReportStatus, Long> loadReportStatusCounts();

	long countReportsCreatedSince(LocalDateTime cutoff);

	ReportProcessingTimeSummary loadReportProcessingTimeSummary();

	List<RepeatedBrokenFacilityReportSummary> loadRepeatedBrokenReportFacilities();
}
