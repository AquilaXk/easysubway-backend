package com.easysubway.admin.operations.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

@DisplayName("V39 장애 상태 전이 마이그레이션")
class V39AdminIncidentTransitionsMigrationTest {

	private static final LocalDateTime OPENED_AT = LocalDateTime.of(2026, 6, 27, 0, 0);
	private static final LocalDateTime RESOLVED_AT = LocalDateTime.of(2026, 6, 27, 1, 0);

	@Test
	@DisplayName("기존 OPEN은 접수로 무손실 이관하고 RESOLVED는 보존하며 타임라인을 재구성한다")
	void migratesLegacyIncidentsAndBackfillsTimeline() {
		var dataSource = seededDataSource();
		var jdbcTemplate = new JdbcTemplate(dataSource);

		new ResourceDatabasePopulator(
			new ClassPathResource("db/migration/h2/V39__admin_incident_transitions.sql")
		).execute(dataSource);

		assertThat(jdbcTemplate.queryForObject(
			"SELECT status FROM admin_incidents WHERE incident_id = 'INC-OPEN'", String.class))
			.isEqualTo("RECEIVED");
		assertThat(jdbcTemplate.queryForObject(
			"SELECT status FROM admin_incidents WHERE incident_id = 'INC-DONE'", String.class))
			.isEqualTo("RESOLVED");
		assertThat(jdbcTemplate.queryForObject(
			"SELECT resolution FROM admin_incidents WHERE incident_id = 'INC-DONE'", String.class))
			.isEqualTo("복구 완료");

		// 접수(1) + 종결(1) = 종결 장애의 타임라인 2행, 접수 장애는 1행
		assertThat(jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM admin_incident_transitions WHERE incident_id = 'INC-OPEN'", Integer.class))
			.isEqualTo(1);
		assertThat(jdbcTemplate.queryForList(
			"SELECT to_status FROM admin_incident_transitions WHERE incident_id = 'INC-DONE' ORDER BY changed_at",
			String.class))
			.containsExactly("RECEIVED", "RESOLVED");
	}

	@Test
	@DisplayName("공통코드 INCIDENT_STATUS를 4상태로 갱신한다")
	void migratesIncidentStatusCommonCodes() {
		var dataSource = seededDataSource();
		var jdbcTemplate = new JdbcTemplate(dataSource);

		new ResourceDatabasePopulator(
			new ClassPathResource("db/migration/h2/V39__admin_incident_transitions.sql")
		).execute(dataSource);

		assertThat(jdbcTemplate.queryForList(
			"SELECT code FROM admin_common_codes WHERE group_code = 'INCIDENT_STATUS' ORDER BY sort_order",
			String.class))
			.containsExactly("RECEIVED", "IN_PROGRESS", "MONITORING", "RESOLVED");
		assertThat(jdbcTemplate.queryForObject(
			"SELECT display_name FROM admin_common_codes WHERE group_code = 'INCIDENT_STATUS' AND code = 'RESOLVED'",
			String.class))
			.isEqualTo("종결");
	}

	private DataSource seededDataSource() {
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
				created_at TIMESTAMP NOT NULL,
				updated_at TIMESTAMP NOT NULL,
				CHECK ((status = 'RESOLVED' AND resolved_at IS NOT NULL AND resolution IS NOT NULL)
					OR (status <> 'RESOLVED' AND resolved_at IS NULL AND resolution IS NULL))
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE admin_common_code_groups (
				group_code VARCHAR(80) NOT NULL PRIMARY KEY,
				display_name VARCHAR(120) NOT NULL,
				description VARCHAR(500),
				sort_order INTEGER NOT NULL DEFAULT 0,
				enabled BOOLEAN NOT NULL DEFAULT TRUE,
				created_at TIMESTAMP NOT NULL,
				updated_at TIMESTAMP NOT NULL
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE admin_common_codes (
				group_code VARCHAR(80) NOT NULL,
				code VARCHAR(80) NOT NULL,
				display_name VARCHAR(120) NOT NULL,
				description VARCHAR(500),
				sort_order INTEGER NOT NULL DEFAULT 0,
				enabled BOOLEAN NOT NULL DEFAULT TRUE,
				created_at TIMESTAMP NOT NULL,
				updated_at TIMESTAMP NOT NULL,
				PRIMARY KEY (group_code, code),
				FOREIGN KEY (group_code) REFERENCES admin_common_code_groups(group_code)
			)
			""");
		jdbcTemplate.update("""
			INSERT INTO admin_incidents (incident_id, severity, status, source, summary, owner, opened_at, resolved_at, resolution, created_at, updated_at)
			VALUES ('INC-OPEN', 'MAJOR', 'OPEN', 'HEALTH', 'database DOWN', 'ops', ?, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", OPENED_AT);
		jdbcTemplate.update("""
			INSERT INTO admin_incidents (incident_id, severity, status, source, summary, owner, opened_at, resolved_at, resolution, created_at, updated_at)
			VALUES ('INC-DONE', 'MINOR', 'RESOLVED', 'BATCH', 'batch fail', 'ops', ?, ?, '복구 완료', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", OPENED_AT, RESOLVED_AT);
		jdbcTemplate.update("""
			INSERT INTO admin_common_code_groups (group_code, display_name, description, sort_order, enabled, created_at, updated_at)
			VALUES ('INCIDENT_STATUS', '장애 상태', '운영 incident 처리 상태', 50, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""");
		jdbcTemplate.update("""
			INSERT INTO admin_common_codes (group_code, code, display_name, description, sort_order, enabled, created_at, updated_at)
			VALUES ('INCIDENT_STATUS', 'OPEN', 'Open', '처리 전', 10, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
			       ('INCIDENT_STATUS', 'RESOLVED', 'Resolved', '해결됨', 20, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""");
		return dataSource;
	}
}
