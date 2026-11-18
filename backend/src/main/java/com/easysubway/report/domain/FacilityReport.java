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

	public static final class PhotoMediaType {
		public static final String IMAGE_JPEG_VALUE = "image/jpeg";
		public static final String IMAGE_PNG_VALUE = "image/png";
		public static final String IMAGE_WEBP_VALUE = "image/webp";

		public static final PhotoMediaType IMAGE_JPEG = new PhotoMediaType(IMAGE_JPEG_VALUE);
		public static final PhotoMediaType IMAGE_PNG = new PhotoMediaType(IMAGE_PNG_VALUE);
		public static final PhotoMediaType IMAGE_WEBP = new PhotoMediaType(IMAGE_WEBP_VALUE);

		private final String canonicalValue;

		private PhotoMediaType(String canonicalValue) {
			this.canonicalValue = canonicalValue;
		}

		public static PhotoMediaType from(String rawContentType) {
			if (rawContentType == null || rawContentType.isBlank()) {
				throw new InvalidFacilityReportException("사진 파일 형식을 확인해야 합니다.");
			}
			if (rawContentType.contains(";") || rawContentType.contains("*")) {
				throw new InvalidFacilityReportException("사진 파일 형식을 확인해야 합니다.");
			}
			String normalized = rawContentType.trim().toLowerCase(Locale.ROOT);
			return switch (normalized) {
				case IMAGE_JPEG_VALUE -> IMAGE_JPEG;
				case IMAGE_PNG_VALUE -> IMAGE_PNG;
				case IMAGE_WEBP_VALUE -> IMAGE_WEBP;
				default -> throw new InvalidFacilityReportException("사진 파일 형식을 확인해야 합니다.");
			};
		}

		public String canonicalValue() {
			return canonicalValue;
		}

		public String extension() {
			return switch (canonicalValue) {
				case IMAGE_PNG_VALUE -> ".png";
				case IMAGE_WEBP_VALUE -> ".webp";
				default -> ".jpg";
			};
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof PhotoMediaType that)) {
				return false;
			}
			return canonicalValue.equals(that.canonicalValue);
		}

		@Override
		public int hashCode() {
			return canonicalValue.hashCode();
		}

		@Override
		public String toString() {
			return canonicalValue;
		}
	}
}
