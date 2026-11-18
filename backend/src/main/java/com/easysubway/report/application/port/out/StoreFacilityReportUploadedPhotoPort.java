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
		public StoreUploadedReportPhotoCommand {
			bytes = bytes == null ? null : bytes.clone();
		}

		@Override
		public byte[] bytes() {
			return bytes == null ? null : bytes.clone();
		}
	}
}
