package com.easysubway.report.application.port.out;

import com.easysubway.report.domain.FacilityReport;
import com.easysubway.report.domain.FacilityReportStatus;
import com.easysubway.report.domain.RepeatedBrokenFacilityReportSummary;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface LoadFacilityReportPort {

	Optional<FacilityReport> loadReport(String reportId);

	List<FacilityReport> loadReports();

	Map<FacilityReportStatus, Long> loadReportStatusCounts();

	List<RepeatedBrokenFacilityReportSummary> loadRepeatedBrokenReportFacilities();
}
