package com.easysubway.field.application.port.in;

import com.easysubway.field.domain.FieldVerificationStatus;

public record UpdateFieldVerificationItemStatusCommand(
	String stationId,
	String itemId,
	FieldVerificationStatus status,
	String note,
	String changedBy
) {
}
