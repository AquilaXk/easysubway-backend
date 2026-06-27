package com.easysubway.admin.operations.adapter.out.persistence;

import com.easysubway.admin.operations.application.port.out.AdminIncidentRepository;
import com.easysubway.admin.operations.domain.AdminIncident;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("prod")
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
			SELECT incident_id, severity, status, source, summary, owner, opened_at, resolved_at, resolution
			FROM admin_incidents
			ORDER BY opened_at DESC, incident_id DESC
			LIMIT ? OFFSET ?
			""", this::mapIncident, Math.max(0, limit), Math.max(offset, 0));
	}

	@Override
	public Optional<AdminIncident> findById(String incidentId) {
		try {
			return Optional.ofNullable(jdbcTemplate.queryForObject("""
				SELECT incident_id, severity, status, source, summary, owner, opened_at, resolved_at, resolution
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
			SET severity = ?, status = ?, source = ?, summary = ?, owner = ?, resolved_at = ?, resolution = ?, updated_at = CURRENT_TIMESTAMP
			WHERE incident_id = ?
			""",
			incident.severity(),
			incident.status(),
			incident.source(),
			incident.summary(),
			incident.owner(),
			incident.resolvedAt(),
			incident.resolution(),
			incident.incidentId()
		);
		if (updated == 0) {
			jdbcTemplate.update("""
				INSERT INTO admin_incidents (
					incident_id, severity, status, source, summary, owner, opened_at, resolved_at, resolution, created_at, updated_at
				)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""",
				incident.incidentId(),
				incident.severity(),
				incident.status(),
				incident.source(),
				incident.summary(),
				incident.owner(),
				incident.openedAt(),
				incident.resolvedAt(),
				incident.resolution()
			);
		}
		return incident;
	}

	private AdminIncident mapIncident(ResultSet resultSet, int rowNumber) throws SQLException {
		var resolvedAt = resultSet.getTimestamp("resolved_at");
		return new AdminIncident(
			resultSet.getString("incident_id"),
			resultSet.getString("severity"),
			resultSet.getString("status"),
			resultSet.getString("source"),
			resultSet.getString("summary"),
			resultSet.getString("owner"),
			resultSet.getTimestamp("opened_at").toLocalDateTime(),
			resolvedAt == null ? null : resolvedAt.toLocalDateTime(),
			resultSet.getString("resolution")
		);
	}
}
