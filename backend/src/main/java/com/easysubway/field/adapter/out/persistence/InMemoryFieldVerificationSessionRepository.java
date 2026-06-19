package com.easysubway.field.adapter.out.persistence;

import com.easysubway.field.application.port.out.FieldVerificationSessionRepository;
import com.easysubway.field.domain.FieldVerificationSession;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!prod")
public class InMemoryFieldVerificationSessionRepository implements FieldVerificationSessionRepository {

	private final Map<String, FieldVerificationSession> sessionsByStationId = new LinkedHashMap<>();

	@Override
	public synchronized List<FieldVerificationSession> listAll() {
		return List.copyOf(sessionsByStationId.values());
	}

	@Override
	public synchronized Optional<FieldVerificationSession> findByStationId(String stationId) {
		return Optional.ofNullable(sessionsByStationId.get(stationId));
	}

	@Override
	public synchronized void save(FieldVerificationSession session) {
		sessionsByStationId.put(session.stationId(), session);
	}
}
