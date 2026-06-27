package com.easysubway.transit.application.port.in;

import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.application.port.out.MasterDataCapability;
import com.easysubway.transit.application.port.out.TransitMasterOverrideAudit;
import com.easysubway.transit.domain.RouteEdge;
import com.easysubway.transit.domain.RouteNode;
import com.easysubway.transit.domain.StationLayoutSource;
import com.easysubway.transit.domain.SimplifiedStationLayout;
import java.util.List;

public interface TransitMasterAdminUseCase {

	MasterDataCapability masterDataCapability();

	AccessibilityFacility createAccessibilityFacility(CreateAccessibilityFacilityCommand command);

	AccessibilityFacility updateAccessibilityFacility(UpdateAccessibilityFacilityCommand command);

	AccessibilityFacility updateFacilityStatus(UpdateAccessibilityFacilityStatusCommand command);

	StationLayoutSource updateStationLayoutSource(UpdateStationLayoutSourceCommand command);

	SimplifiedStationLayout updateSimplifiedStationLayoutStatus(UpdateSimplifiedStationLayoutStatusCommand command);

	RouteNode updateRouteNodeDisplay(UpdateRouteNodeDisplayCommand command);

	RouteEdge updateRouteEdge(UpdateRouteEdgeCommand command);

	void rollbackMasterDataOverride(String entityType, String entityId, String updatedBy);

	List<TransitMasterOverrideAudit> listMasterDataOverrideAudits(String entityType, String entityId);
}
