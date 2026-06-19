package com.easysubway.report.application.port.out;

import java.util.Optional;

public interface LoadFacilityReportPhotoPort {

	Optional<LoadedFacilityReportPhoto> loadFacilityReportPhoto(String objectKey);

	record LoadedFacilityReportPhoto(
		String contentType,
		byte[] bytes
	) {
	}
}
