package com.easysubway.report.application.port.out;

public interface StoreFacilityReportUploadedPhotoPort {

	void storeUploadedReportPhoto(StoreUploadedReportPhotoCommand command);

	record StoreUploadedReportPhotoCommand(
		String objectKey,
		byte[] bytes
	) {
	}
}
