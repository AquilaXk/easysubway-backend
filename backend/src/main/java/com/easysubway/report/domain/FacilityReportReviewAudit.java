package com.easysubway.report.domain;

import java.time.LocalDateTime;

public record FacilityReportReviewAudit(
	String id,
	String reportId,
	String reviewerId,
	FacilityReportReviewDecision decision,
	FacilityReportStatus previousStatus,
	FacilityReportStatus nextStatus,
	LocalDateTime createdAt
) {
}
