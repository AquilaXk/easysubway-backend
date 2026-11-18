package com.easysubway.transit.application.port.out;

import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import java.time.LocalDate;

public interface SaveAccessibilityFacilityStatusPort {

	void saveFacilityStatus(String facilityId, AccessibilityFacilityStatus status, LocalDate updatedAt);

	default void saveFacilityStatus(
		String facilityId,
		AccessibilityFacilityStatus status,
		LocalDate updatedAt,
		String updatedBy
	) {
		saveFacilityStatus(facilityId, status, updatedAt);
	}

	default void saveAccessibilityFacility(AccessibilityFacility facility) {
		throw new UnsupportedOperationException("Accessibility facility saving is not implemented.");
	}

	default void saveAccessibilityFacility(AccessibilityFacility facility, String updatedBy) {
		saveAccessibilityFacility(facility);
	}
}
