package com.easysubway.admin.operations.adapter.out.persistence;

import com.easysubway.admin.operations.application.port.out.AdminIncidentRepository;
import com.easysubway.admin.operations.domain.AdminIncident;
import com.easysubway.admin.operations.domain.AdminIncidentTransition;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!prod & !staging & !release & !prod-like")
public class InMemoryAdminIncidentRepository implements AdminIncidentRepository {

	private final Map<String, AdminIncident> incidents = new LinkedHashMap<>();
	private final List<AdminIncidentTransition> transitions = new ArrayList<>();

	@Override
	public synchronized List<AdminIncident> findRecent(int limit) {
		return findRecent(limit, 0);
	}

	@Override
	public synchronized List<AdminIncident> findRecent(int limit, int offset) {
		return incidents.values()
			.stream()
			.sorted(Comparator.comparing(AdminIncident::openedAt)
				.reversed()
				.thenComparing(AdminIncident::incidentId, Comparator.reverseOrder()))
			.skip(Math.max(offset, 0))
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

	@Override
	public synchronized void saveTransition(AdminIncidentTransition transition) {
		transitions.add(transition);
	}

	@Override
	public synchronized List<AdminIncidentTransition> findTransitions(String incidentId) {
		return transitions.stream()
			.filter(transition -> transition.incidentId().equals(incidentId))
			.sorted(Comparator.comparing(AdminIncidentTransition::changedAt))
			.toList();
	}

	@Override
	public synchronized Map<String, List<AdminIncidentTransition>> findTransitions(Collection<String> incidentIds) {
		Set<String> ids = new HashSet<>(incidentIds);
		Map<String, List<AdminIncidentTransition>> byIncident = new LinkedHashMap<>();
		transitions.stream()
			.filter(transition -> ids.contains(transition.incidentId()))
			.sorted(Comparator.comparing(AdminIncidentTransition::changedAt))
			.forEach(transition -> byIncident
				.computeIfAbsent(transition.incidentId(), key -> new ArrayList<>())
				.add(transition));
		return byIncident;
	}
}
