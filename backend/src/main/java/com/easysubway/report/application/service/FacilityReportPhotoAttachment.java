package com.easysubway.report.application.service;

record FacilityReportPhotoAttachment(
	String fileName,
	String contentType,
	byte[] storedBytes,
	byte[] thumbnailBytes,
	String sha256,
	long sizeBytes
) {
}
