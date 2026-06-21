package com.easysubway.report.application.port.out;

/**
 * Claims a previously uploaded report photo. The object key is the durable photo reference.
 * It must be validated before a report can attach the object.
 */
public interface StoreFacilityReportUploadedPhotoPort {

	void storeUploadedReportPhoto(StoreUploadedReportPhotoCommand command);

	record StoreUploadedReportPhotoCommand(
		String objectKey,
		byte[] bytes
	) {
	}
}
