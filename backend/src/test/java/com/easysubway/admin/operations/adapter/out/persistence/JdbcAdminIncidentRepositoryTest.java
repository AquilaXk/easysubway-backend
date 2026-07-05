package com.easysubway.admin.operations.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.admin.operations.domain.AdminIncident;
import com.easysubway.admin.operations.domain.AdminIncidentStatus;
import com.easysubway.admin.operations.domain.AdminIncidentTransition;
import java.time.LocalDateTime;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

@DisplayName("JDBC 관리자 incident 저장소")
class JdbcAdminIncidentRepositoryTest {

	private static final LocalDateTime OPENED_AT = LocalDateTime.of(2026, 6, 27, 0, 0);

	@Test
	@DisplayName("접수 → 조치 중 → 모니터링 → 종결 전이를 저장하고 상태·해결 필드를 왕복한다")
	void savesTransitionsAndRoundTripsStatus() {
		var dataSource = incidentDataSource();
		var repository = new JdbcAdminIncidentRepository(dataSource);

		AdminIncident received = repository.save(new AdminIncident(
			"INC-1", "MAJOR", AdminIncidentStatus.RECEIVED, "HEALTH", "database DOWN", "ops", OPENED_AT, null, null, "STN-1", "L1"));
		repository.saveTransition(new AdminIncidentTransition(
			"INC-1", null, AdminIncidentStatus.RECEIVED, OPENED_AT, "ops", "접수"));

		repository.save(received.transitionTo(AdminIncidentStatus.IN_PROGRESS, OPENED_AT.plusMinutes(1), null));
		repository.saveTransition(new AdminIncidentTransition(
			"INC-1", AdminIncidentStatus.RECEIVED, AdminIncidentStatus.IN_PROGRESS, OPENED_AT.plusMinutes(1), "ops", null));

		AdminIncident found = repository.findById("INC-1").orElseThrow();
		assertThat(found.status()).isEqualTo(AdminIncidentStatus.IN_PROGRESS);
		assertThat(found.resolvedAt()).isNull();
		assertThat(found.stationId()).isEqualTo("STN-1");
		assertThat(found.lineId()).isEqualTo("L1");
		assertThat(repository.findTransitions("INC-1"))
			.extracting(AdminIncidentTransition::toStatus)
			.containsExactly(AdminIncidentStatus.RECEIVED, AdminIncidentStatus.IN_PROGRESS);
	}

	@Test
	@DisplayName("여러 incident 전이 이력을 단일 벌크 조회로 읽는다")
	void findsTransitionsInBulk() {
		var dataSource = incidentDataSource();
		var repository = new JdbcAdminIncidentRepository(dataSource);
		repository.save(new AdminIncident(
			"INC-1", "MAJOR", AdminIncidentStatus.RECEIVED, "HEALTH", "a", "ops", OPENED_AT, null, null, null, null));
		repository.save(new AdminIncident(
			"INC-2", "MINOR", AdminIncidentStatus.RECEIVED, "HEALTH", "b", "ops", OPENED_AT, null, null, null, null));
		repository.saveTransition(new AdminIncidentTransition(
			"INC-1", null, AdminIncidentStatus.RECEIVED, OPENED_AT, "ops", null));
		repository.saveTransition(new AdminIncidentTransition(
			"INC-2", null, AdminIncidentStatus.RECEIVED, OPENED_AT, "ops", null));

		var byIncident = repository.findTransitions(List.of("INC-1", "INC-2"));

		assertThat(byIncident).containsOnlyKeys("INC-1", "INC-2");
		assertThat(byIncident.get("INC-1")).singleElement()
			.satisfies(step -> assertThat(step.isInitial()).isTrue());
	}

	@Test
	@DisplayName("레거시 OPEN 상태 행은 접수(RECEIVED)로 읽는다")
	void readsLegacyOpenRowAsReceived() {
		var dataSource = incidentDataSource();
		var jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.update("""
			INSERT INTO admin_incidents (incident_id, severity, status, source, summary, owner, opened_at, resolved_at, resolution, created_at, updated_at)
			VALUES ('INC-LEGACY', 'MAJOR', 'OPEN', 'HEALTH', 'legacy', 'ops', ?, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", OPENED_AT);

		var repository = new JdbcAdminIncidentRepository(dataSource);

		assertThat(repository.findById("INC-LEGACY")).get()
			.satisfies(incident -> assertThat(incident.status()).isEqualTo(AdminIncidentStatus.RECEIVED));
	}

	private DataSource incidentDataSource() {
		var dataSource = new EmbeddedDatabaseBuilder()
			.setType(EmbeddedDatabaseType.H2)
			.generateUniqueName(true)
			.build();
		var jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.execute("""
			CREATE TABLE admin_incidents (
				incident_id VARCHAR(40) NOT NULL PRIMARY KEY,
				severity VARCHAR(40) NOT NULL,
				status VARCHAR(40) NOT NULL,
				source VARCHAR(40) NOT NULL,
				summary VARCHAR(300) NOT NULL,
				owner VARCHAR(120) NOT NULL,
				opened_at TIMESTAMP NOT NULL,
				resolved_at TIMESTAMP,
				resolution VARCHAR(500),
				station_id VARCHAR(40),
				line_id VARCHAR(40),
				created_at TIMESTAMP NOT NULL,
				updated_at TIMESTAMP NOT NULL
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE admin_incident_transitions (
				id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				incident_id VARCHAR(40) NOT NULL,
				from_status VARCHAR(40),
				to_status VARCHAR(40) NOT NULL,
				changed_at TIMESTAMP NOT NULL,
				changed_by VARCHAR(120) NOT NULL,
				note VARCHAR(500),
				CONSTRAINT fk_admin_incident_transitions_incident
					FOREIGN KEY (incident_id) REFERENCES admin_incidents(incident_id)
			)
			""");
		return dataSource;
	}
}
