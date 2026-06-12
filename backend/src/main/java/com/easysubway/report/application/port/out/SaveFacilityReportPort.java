package com.easysubway.report.application.port.out;

import com.easysubway.report.domain.FacilityReport;

public interface SaveFacilityReportPort {

	FacilityReport saveReport(FacilityReport report);
}
