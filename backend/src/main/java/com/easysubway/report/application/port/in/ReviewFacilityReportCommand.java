package com.easysubway.report.application.port.in;

import com.easysubway.report.domain.FacilityReportReviewDecision;

public record ReviewFacilityReportCommand(
	String reportId,
	FacilityReportReviewDecision decision,
	String reviewedBy
) {
}
