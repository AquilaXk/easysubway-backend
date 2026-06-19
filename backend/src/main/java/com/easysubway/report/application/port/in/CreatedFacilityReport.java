package com.easysubway.report.application.port.in;

import com.easysubway.report.domain.FacilityReport;

public record CreatedFacilityReport(
	FacilityReport report,
	String receiptToken
) {
}
