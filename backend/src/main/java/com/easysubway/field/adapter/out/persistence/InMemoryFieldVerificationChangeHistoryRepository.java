package com.easysubway.field.adapter.out.persistence;

import com.easysubway.field.application.port.out.FieldVerificationChangeHistoryRepository;
import com.easysubway.field.domain.FieldVerificationChangeHistory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!prod & !staging & !release & !prod-like")
public class InMemoryFieldVerificationChangeHistoryRepository implements FieldVerificationChangeHistoryRepository {

	private final Map<String, List<FieldVerificationChangeHistory>> historiesByStationId = new ConcurrentHashMap<>();

	@Override
	public synchronized void save(FieldVerificationChangeHistory history) {
		historiesByStationId.computeIfAbsent(history.stationId(), ignored -> new ArrayList<>()).add(0, history);
	}

	@Override
	public synchronized List<FieldVerificationChangeHistory> listByStationId(String stationId) {
		return List.copyOf(historiesByStationId.getOrDefault(stationId, List.of()));
	}
}
