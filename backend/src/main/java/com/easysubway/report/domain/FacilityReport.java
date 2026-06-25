package com.easysubway.report.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;

public record FacilityReport(
	String id,
	String publicReceiptCode,
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
		this(
			id,
			defaultPublicReceiptCode(id),
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
			clientSubmissionId,
			receiptTokenHash
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
			defaultPublicReceiptCode(id),
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
			defaultPublicReceiptCode(id),
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

	private static String defaultPublicReceiptCode(String reportId) {
		String compact = reportId == null
			? ""
			: reportId.replaceFirst("^report-", "")
				.replaceAll("[^A-Za-z0-9]", "")
				.toUpperCase(Locale.ROOT);
		if (compact.length() >= 8) {
			return "ES-" + compact.substring(0, 8);
		}
		return "ES-" + Integer.toUnsignedString(String.valueOf(reportId).hashCode(), 36)
			.toUpperCase(Locale.ROOT);
	}
}
