package com.easysubway.transit.application.port.in;

import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.SimplifiedStationLayout;

public interface TransitMasterAdminUseCase {

	AccessibilityFacility createAccessibilityFacility(CreateAccessibilityFacilityCommand command);

	AccessibilityFacility updateAccessibilityFacility(UpdateAccessibilityFacilityCommand command);

	AccessibilityFacility updateFacilityStatus(UpdateAccessibilityFacilityStatusCommand command);

	SimplifiedStationLayout updateSimplifiedStationLayoutStatus(UpdateSimplifiedStationLayoutStatusCommand command);
}
