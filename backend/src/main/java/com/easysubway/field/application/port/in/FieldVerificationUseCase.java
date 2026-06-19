package com.easysubway.field.application.port.in;

import com.easysubway.field.domain.FieldVerificationSession;

public interface FieldVerificationUseCase {

	FieldVerificationSession getStationVerification(String stationId);
}
