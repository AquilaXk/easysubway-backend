package com.easysubway.transit.application.port.in;

import com.easysubway.transit.domain.AccessibilityFacility;

public interface TransitMasterAdminUseCase {

	AccessibilityFacility updateFacilityStatus(UpdateAccessibilityFacilityStatusCommand command);
}
