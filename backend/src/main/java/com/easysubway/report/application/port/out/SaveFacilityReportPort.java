package com.easysubway.report.application.port.out;

import com.easysubway.report.domain.FacilityReport;
import com.easysubway.report.domain.FacilityReportStatus;
import java.util.Optional;

public interface SaveFacilityReportPort {

	FacilityReport saveReport(FacilityReport report);

	Optional<FacilityReport> saveReviewedReportIfStatus(FacilityReport report, FacilityReportStatus expectedStatus);
}
