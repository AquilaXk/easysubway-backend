package com.easysubway.report.application.port.in;

import com.easysubway.report.domain.FacilityReportType;
import java.math.BigDecimal;

public record CreateFacilityReportCommand(
	String userId,
	String clientSubmissionId,
	String stationId,
	String facilityId,
	FacilityReportType reportType,
	String description,
	String photoFileName,
	String photoContentType,
	String photoDataBase64,
	String photoObjectKey,
	String photoSha256,
	Long photoSizeBytes,
	String receiptTokenHash,
	BigDecimal latitude,
	BigDecimal longitude
) {

	public CreateFacilityReportCommand(
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
		this(
			userId,
			null,
			stationId,
			facilityId,
			reportType,
			description,
			photoFileName,
			photoContentType,
			photoDataBase64,
			null,
			null,
			null,
			null,
			latitude,
			longitude
		);
	}
}
