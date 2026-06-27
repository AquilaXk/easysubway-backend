package com.easysubway.transit.application.port.out;

import java.util.List;

public interface RollbackTransitMasterOverridePort {

	void rollbackMasterDataOverride(String entityType, String entityId, String updatedBy);

	List<TransitMasterOverrideAudit> listMasterDataOverrideAudits(String entityType, String entityId);
}
