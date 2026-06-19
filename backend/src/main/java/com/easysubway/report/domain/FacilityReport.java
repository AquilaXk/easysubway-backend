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
	String photoObjectKey,
	String photoThumbnailObjectKey,
	String photoSha256,
	Long photoSizeBytes,
	BigDecimal latitude,
	BigDecimal longitude,
	String duplicateOfReportId,
	FacilityReportStatus status,
	LocalDateTime createdAt,
	LocalDateTime reviewedAt,
	String reviewedBy,
	String clientSubmissionId,
	String receiptTokenHash
) {

	public static final String ANONYMIZED_USER_ID = "__easysubway_deleted_facility_report__";

	public FacilityReport(
		String id,
		String userId,
		String stationId,
		String facilityId,
		FacilityReportType reportType,
		String description,
		String photoFileName,
		String photoContentType,
		String legacyPhotoObjectKey,
		BigDecimal latitude,
		BigDecimal longitude,
		String duplicateOfReportId,
		FacilityReportStatus status,
		LocalDateTime createdAt,
		LocalDateTime reviewedAt,
		String reviewedBy
	) {
		this(
			id,
			userId,
			stationId,
			facilityId,
			reportType,
			description,
			photoFileName,
			photoContentType,
			legacyPhotoObjectKey,
			null,
			null,
			null,
			latitude,
			longitude,
			duplicateOfReportId,
			status,
			createdAt,
			reviewedAt,
			reviewedBy,
			null,
			null
		);
	}

	public FacilityReport(
		String id,
		String userId,
		String stationId,
		String facilityId,
		FacilityReportType reportType,
		String description,
		String photoFileName,
		String photoContentType,
		String photoObjectKey,
		String photoThumbnailObjectKey,
		String photoSha256,
		Long photoSizeBytes,
		BigDecimal latitude,
		BigDecimal longitude,
		String duplicateOfReportId,
		FacilityReportStatus status,
		LocalDateTime createdAt,
		LocalDateTime reviewedAt,
		String reviewedBy
	) {
		this(
			id,
			userId,
			stationId,
			facilityId,
			reportType,
			description,
			photoFileName,
			photoContentType,
			photoObjectKey,
			photoThumbnailObjectKey,
			photoSha256,
			photoSizeBytes,
			latitude,
			longitude,
			duplicateOfReportId,
			status,
			createdAt,
			reviewedAt,
			reviewedBy,
			null,
			null
		);
	}

	public boolean isAnonymizedUserData() {
		return ANONYMIZED_USER_ID.equals(userId);
	}

	public boolean hasPhoto() {
		return hasText(photoFileName)
			&& hasText(photoContentType)
			&& hasText(photoObjectKey);
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}
}
