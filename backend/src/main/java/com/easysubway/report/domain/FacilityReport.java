package com.easysubway.report.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record FacilityReport(
	String id,
	String userId,
	String stationId,
	String facilityId,
	FacilityReportType reportType,
	String description,
	String photoUrl,
	BigDecimal latitude,
	BigDecimal longitude,
	FacilityReportStatus status,
	LocalDateTime createdAt,
	LocalDateTime reviewedAt,
	String reviewedBy
) {
}
