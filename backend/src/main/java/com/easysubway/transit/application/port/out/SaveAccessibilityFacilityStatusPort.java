package com.easysubway.transit.application.port.out;

import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import java.time.LocalDate;

public interface SaveAccessibilityFacilityStatusPort {

	void saveFacilityStatus(String facilityId, AccessibilityFacilityStatus status, LocalDate updatedAt);
}
