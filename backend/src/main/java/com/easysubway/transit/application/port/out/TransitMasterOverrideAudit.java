package com.easysubway.transit.application.port.out;

import java.time.LocalDateTime;

public record TransitMasterOverrideAudit(
	long auditId,
	String entityType,
	String entityId,
	String action,
	String updatedBy,
	LocalDateTime updatedAt
) {
}
