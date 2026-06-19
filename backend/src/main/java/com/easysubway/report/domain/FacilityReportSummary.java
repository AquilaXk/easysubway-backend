package com.easysubway.report.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record FacilityReportSummary(
	String id,
	String userId,
	String stationId,
	String facilityId,
	FacilityReportType reportType,
	String description,
	boolean hasPhoto,
	BigDecimal latitude,
	BigDecimal longitude,
	String duplicateOfReportId,
	FacilityReportStatus status,
	LocalDateTime createdAt,
	LocalDateTime reviewedAt,
	String reviewedBy
) {

	public static FacilityReportSummary from(FacilityReport report) {
		return new FacilityReportSummary(
			report.id(),
			report.userId(),
			report.stationId(),
			report.facilityId(),
			report.reportType(),
			report.description(),
			report.hasPhoto(),
			report.latitude(),
			report.longitude(),
			report.duplicateOfReportId(),
			report.status(),
			report.createdAt(),
			report.reviewedAt(),
			report.reviewedBy()
		);
	}
}
