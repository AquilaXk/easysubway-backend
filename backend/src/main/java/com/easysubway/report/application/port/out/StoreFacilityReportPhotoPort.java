package com.easysubway.report.application.port.out;

/**
 * Stores processed report photos. The object key is the durable photo reference;
 * report rows must not persist base64 photo payloads or signed upload URLs.
 */
public interface StoreFacilityReportPhotoPort {

	StoredFacilityReportPhoto storeFacilityReportPhoto(StoreFacilityReportPhotoCommand command);

	record StoreFacilityReportPhotoCommand(
		String reportId,
		String fileName,
		String contentType,
		byte[] storedBytes,
		byte[] thumbnailBytes,
		String sha256,
		long sizeBytes
	) {
	}

	record StoredFacilityReportPhoto(
		String objectKey,
		String thumbnailObjectKey
	) {
	}
}
