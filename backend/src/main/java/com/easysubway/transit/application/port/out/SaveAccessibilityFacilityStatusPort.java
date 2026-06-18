package com.easysubway.transit.application.port.out;

import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import java.time.LocalDate;

public interface SaveAccessibilityFacilityStatusPort {

	void saveFacilityStatus(String facilityId, AccessibilityFacilityStatus status, LocalDate updatedAt);

	default void saveAccessibilityFacility(AccessibilityFacility facility) {
		throw new UnsupportedOperationException("Accessibility facility saving is not implemented.");
	}
}
