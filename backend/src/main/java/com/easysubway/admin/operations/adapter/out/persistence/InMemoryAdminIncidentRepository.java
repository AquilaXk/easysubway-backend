package com.easysubway.admin.operations.adapter.out.persistence;

import com.easysubway.admin.operations.application.port.out.AdminIncidentRepository;
import com.easysubway.admin.operations.domain.AdminIncident;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!prod")
public class InMemoryAdminIncidentRepository implements AdminIncidentRepository {

	private final Map<String, AdminIncident> incidents = new LinkedHashMap<>();

	@Override
	public synchronized List<AdminIncident> findRecent(int limit) {
		return incidents.values()
			.stream()
			.sorted(Comparator.comparing(AdminIncident::openedAt)
				.reversed()
				.thenComparing(AdminIncident::incidentId, Comparator.reverseOrder()))
			.limit(Math.max(0, limit))
			.toList();
	}

	@Override
	public synchronized Optional<AdminIncident> findById(String incidentId) {
		return Optional.ofNullable(incidents.get(incidentId));
	}

	@Override
	public synchronized AdminIncident save(AdminIncident incident) {
		incidents.put(incident.incidentId(), incident);
		return incident;
	}
}
