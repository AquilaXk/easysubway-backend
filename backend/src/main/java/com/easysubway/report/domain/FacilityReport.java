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
	String photoFileName,
	String photoContentType,
	String photoDataBase64,
	BigDecimal latitude,
	BigDecimal longitude,
	String duplicateOfReportId,
	FacilityReportStatus status,
	LocalDateTime createdAt,
	LocalDateTime reviewedAt,
	String reviewedBy
) {
}
