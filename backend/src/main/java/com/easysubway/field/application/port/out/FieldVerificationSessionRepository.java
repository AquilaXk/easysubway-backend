package com.easysubway.field.application.port.out;

import com.easysubway.field.domain.FieldVerificationSession;
import java.util.List;
import java.util.Optional;

public interface FieldVerificationSessionRepository {

	List<FieldVerificationSession> listAll();

	Optional<FieldVerificationSession> findByStationId(String stationId);

	void save(FieldVerificationSession session);
}
