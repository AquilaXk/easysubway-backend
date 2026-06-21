package com.easysubway.report.application.port.out;

import java.util.Optional;

/**
 * Loads stored report photos by object key.
 * Contract marker: photo bytes must only be loaded through authorized review or receipt-token flows.
 */
public interface LoadFacilityReportPhotoPort {

	Optional<LoadedFacilityReportPhoto> loadFacilityReportPhoto(String objectKey);

	record LoadedFacilityReportPhoto(
		String contentType,
		byte[] bytes
	) {
	}
}
