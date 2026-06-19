package com.easysubway.field.application.port.in;

import com.easysubway.field.domain.FieldVerificationSession;
import java.util.List;

public interface FieldVerificationUseCase {

	List<FieldVerificationSession> listStationVerifications();

	FieldVerificationSession getStationVerification(String stationId);
}
