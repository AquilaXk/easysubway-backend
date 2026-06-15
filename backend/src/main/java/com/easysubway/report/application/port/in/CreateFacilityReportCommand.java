package com.easysubway.report.application.port.in;

import com.easysubway.report.domain.FacilityReportType;
import java.math.BigDecimal;

public record CreateFacilityReportCommand(
	String userId,
	String stationId,
	String facilityId,
	FacilityReportType reportType,
	String description,
	String photoFileName,
	String photoContentType,
	String photoDataBase64,
	BigDecimal latitude,
	BigDecimal longitude
) {
}
