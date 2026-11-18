package com.easysubway.report.application.port.in;

import com.easysubway.report.domain.FacilityReportReviewDecision;

public record ReviewFacilityReportCommand(
	String reportId,
	FacilityReportReviewDecision decision,
	String reviewedBy,
	String duplicateOfReportId
) {

	public ReviewFacilityReportCommand(
		String reportId,
		FacilityReportReviewDecision decision,
		String reviewedBy
	) {
		this(reportId, decision, reviewedBy, null);
	}
}
