package com.easysubway.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@DisplayName("운영 스키마 Flyway migration")
class DatabaseMigrationContainerTest {

	@Container
	private static final PostgreSQLContainer<?> POSTGRES =
		new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

	@Test
	@DisplayName("깨끗한 PostgreSQL DB는 versioned migration만으로 핵심 운영 테이블과 제약을 만든다")
	void flywayMigratesCleanPostgresqlSchema() {
		var dataSource = new DriverManagerDataSource(
			POSTGRES.getJdbcUrl(),
			POSTGRES.getUsername(),
			POSTGRES.getPassword()
		);
		var flyway = Flyway.configure()
			.dataSource(dataSource)
			.locations("classpath:db/migration/postgresql")
			.load();

		var result = flyway.migrate();

		assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(1);
		var jdbcTemplate = new JdbcTemplate(dataSource);
		assertThat(tableNames(jdbcTemplate))
			.contains(
				"flyway_schema_history",
				"batch_job_instance",
				"facility_reports",
				"push_notification_outbox",
				"transit_master_overrides",
				"transit_master_override_audits"
			);
		assertThat(successfulMigrationVersions(jdbcTemplate)).contains("1", "14");
		assertThat(foreignKeyNames(jdbcTemplate))
			.contains("fk_facility_report_review_audits_report");
	}

	private List<String> tableNames(JdbcTemplate jdbcTemplate) {
		return jdbcTemplate.queryForList("""
			SELECT table_name
			FROM information_schema.tables
			WHERE table_schema = 'public'
			ORDER BY table_name
			""", String.class);
	}

	private List<String> successfulMigrationVersions(JdbcTemplate jdbcTemplate) {
		return jdbcTemplate.queryForList("""
			SELECT version
			FROM flyway_schema_history
			WHERE success = true
			ORDER BY installed_rank
			""", String.class);
	}

	private List<String> foreignKeyNames(JdbcTemplate jdbcTemplate) {
		return jdbcTemplate.queryForList("""
			SELECT constraint_name
			FROM information_schema.table_constraints
			WHERE table_schema = 'public'
				AND constraint_type = 'FOREIGN KEY'
			ORDER BY constraint_name
			""", String.class);
	}
}
