package com.easysubway.report.application.port.out;

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
