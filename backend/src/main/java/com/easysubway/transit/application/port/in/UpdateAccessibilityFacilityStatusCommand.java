package com.easysubway.transit.application.port.in;

import com.easysubway.transit.domain.AccessibilityFacilityStatus;

public record UpdateAccessibilityFacilityStatusCommand(
	String facilityId,
	AccessibilityFacilityStatus status,
	String updatedBy
) {
}
