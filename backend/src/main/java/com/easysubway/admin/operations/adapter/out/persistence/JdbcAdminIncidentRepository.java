package com.easysubway.admin.operations.adapter.out.persistence;

import com.easysubway.admin.operations.application.port.out.AdminIncidentRepository;
import com.easysubway.admin.operations.domain.AdminIncident;
import com.easysubway.admin.operations.domain.AdminIncidentStatus;
import com.easysubway.admin.operations.domain.AdminIncidentTransition;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("prod | staging | release | prod-like")
public class JdbcAdminIncidentRepository implements AdminIncidentRepository {

	private final JdbcTemplate jdbcTemplate;

	@Autowired
	public JdbcAdminIncidentRepository(DataSource dataSource) {
		this.jdbcTemplate = new JdbcTemplate(dataSource);
	}

	@Override
	public List<AdminIncident> findRecent(int limit) {
		return findRecent(limit, 0);
	}

	@Override
	public List<AdminIncident> findRecent(int limit, int offset) {
		return jdbcTemplate.query("""
			SELECT incident_id, severity, status, source, summary, owner, opened_at, resolved_at, resolution, station_id, line_id
			FROM admin_incidents
			ORDER BY opened_at DESC, incident_id DESC
			LIMIT ? OFFSET ?
			""", this::mapIncident, Math.max(0, limit), Math.max(offset, 0));
	}

	@Override
	public Optional<AdminIncident> findById(String incidentId) {
		try {
			return Optional.ofNullable(jdbcTemplate.queryForObject("""
				SELECT incident_id, severity, status, source, summary, owner, opened_at, resolved_at, resolution, station_id, line_id
				FROM admin_incidents
				WHERE incident_id = ?
				""", this::mapIncident, incidentId));
		} catch (EmptyResultDataAccessException exception) {
			return Optional.empty();
		}
	}

	@Override
	public AdminIncident save(AdminIncident incident) {
		int updated = jdbcTemplate.update("""
			UPDATE admin_incidents
			SET severity = ?, status = ?, source = ?, summary = ?, owner = ?, resolved_at = ?, resolution = ?,
				station_id = ?, line_id = ?, updated_at = CURRENT_TIMESTAMP
			WHERE incident_id = ?
			""",
			incident.severity(),
			incident.status().name(),
			incident.source(),
			incident.summary(),
			incident.owner(),
			incident.resolvedAt(),
			incident.resolution(),
			incident.stationId(),
			incident.lineId(),
			incident.incidentId()
		);
		if (updated == 0) {
			jdbcTemplate.update("""
				INSERT INTO admin_incidents (
					incident_id, severity, status, source, summary, owner, opened_at, resolved_at, resolution,
					station_id, line_id, created_at, updated_at
				)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""",
				incident.incidentId(),
				incident.severity(),
				incident.status().name(),
				incident.source(),
				incident.summary(),
				incident.owner(),
				incident.openedAt(),
				incident.resolvedAt(),
				incident.resolution(),
				incident.stationId(),
				incident.lineId()
			);
		}
		return incident;
	}

	@Override
	public void saveTransition(AdminIncidentTransition transition) {
		jdbcTemplate.update("""
			INSERT INTO admin_incident_transitions (incident_id, from_status, to_status, changed_at, changed_by, note)
			VALUES (?, ?, ?, ?, ?, ?)
			""",
			transition.incidentId(),
			transition.fromStatus() == null ? null : transition.fromStatus().name(),
			transition.toStatus().name(),
			transition.changedAt(),
			transition.changedBy(),
			transition.note()
		);
	}

	@Override
	public List<AdminIncidentTransition> findTransitions(String incidentId) {
		return jdbcTemplate.query("""
			SELECT incident_id, from_status, to_status, changed_at, changed_by, note
			FROM admin_incident_transitions
			WHERE incident_id = ?
			ORDER BY changed_at, id
			""", this::mapTransition, incidentId);
	}

	@Override
	public Map<String, List<AdminIncidentTransition>> findTransitions(Collection<String> incidentIds) {
		if (incidentIds.isEmpty()) {
			return Map.of();
		}
		List<String> ids = List.copyOf(incidentIds);
		String placeholders = String.join(", ", ids.stream().map(id -> "?").toList());
		List<AdminIncidentTransition> rows = jdbcTemplate.query("""
			SELECT incident_id, from_status, to_status, changed_at, changed_by, note
			FROM admin_incident_transitions
			WHERE incident_id IN (%s)
			ORDER BY changed_at, id
			""".formatted(placeholders), this::mapTransition, ids.toArray());
		Map<String, List<AdminIncidentTransition>> byIncident = new LinkedHashMap<>();
		for (AdminIncidentTransition row : rows) {
			byIncident.computeIfAbsent(row.incidentId(), key -> new ArrayList<>()).add(row);
		}
		return byIncident;
	}

	private AdminIncident mapIncident(ResultSet resultSet, int rowNumber) throws SQLException {
		var resolvedAt = resultSet.getTimestamp("resolved_at");
		return new AdminIncident(
			resultSet.getString("incident_id"),
			resultSet.getString("severity"),
			AdminIncidentStatus.from(resultSet.getString("status")),
			resultSet.getString("source"),
			resultSet.getString("summary"),
			resultSet.getString("owner"),
			resultSet.getTimestamp("opened_at").toLocalDateTime(),
			resolvedAt == null ? null : resolvedAt.toLocalDateTime(),
			resultSet.getString("resolution"),
			resultSet.getString("station_id"),
			resultSet.getString("line_id")
		);
	}

	private AdminIncidentTransition mapTransition(ResultSet resultSet, int rowNumber) throws SQLException {
		String fromStatus = resultSet.getString("from_status");
		return new AdminIncidentTransition(
			resultSet.getString("incident_id"),
			fromStatus == null ? null : AdminIncidentStatus.from(fromStatus),
			AdminIncidentStatus.from(resultSet.getString("to_status")),
			resultSet.getTimestamp("changed_at").toLocalDateTime(),
			resultSet.getString("changed_by"),
			resultSet.getString("note")
		);
	}
}
