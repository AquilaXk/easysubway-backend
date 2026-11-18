package com.easysubway.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.admin.authorization.AdminRbacRole;
import java.util.Set;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@DisplayName("데이터 수집 실행 V63 migration")
class DataCollectionRunMigrationTest {

	@Test
	@DisplayName("V62의 단일 RUNNING source는 V63에서 active claim으로 보존된다")
	void migratesSingleLegacyRunningRunToActiveClaim() {
		var dataSource = dataSource("single");
		migrateToV62(dataSource);
		var jdbcTemplate = new JdbcTemplate(dataSource);
		insertLegacyRunningRun(jdbcTemplate, "legacy-running");

		migrateLatest(dataSource);

		assertThat(jdbcTemplate.queryForObject(
			"SELECT active_source FROM data_collection_runs WHERE run_id = ?",
			String.class,
			"legacy-running"
		)).isEqualTo("TRANSIT_MASTER");
	}

	@Test
	@DisplayName("V62의 중복 RUNNING source는 임의 종료 없이 V63 migration을 fail closed 한다")
	void rejectsDuplicateLegacyRunningRunsWithoutMutatingStatus() {
		var dataSource = dataSource("duplicate");
		migrateToV62(dataSource);
		var jdbcTemplate = new JdbcTemplate(dataSource);
		insertLegacyRunningRun(jdbcTemplate, "legacy-running-a");
		insertLegacyRunningRun(jdbcTemplate, "legacy-running-b");

		assertThatThrownBy(() -> migrateLatest(dataSource))
			.isInstanceOf(FlywayException.class)
			.hasMessageContaining("V63__admin_batch_run_permission.sql")
			.rootCause()
			.hasMessageContaining("ux_data_collection_runs_active_source");
		assertThat(jdbcTemplate.queryForList(
			"SELECT status FROM data_collection_runs ORDER BY run_id",
			String.class
		)).containsExactly("RUNNING", "RUNNING");
	}

	@Test
	@DisplayName("DATA_OPERATOR의 Java 권한과 H2 seed 권한은 일치한다")
	void dataOperatorJavaPermissionsMatchDatabaseSeed() {
		var dataSource = dataSource("rbac-parity");
		migrateLatest(dataSource);
		var jdbcTemplate = new JdbcTemplate(dataSource);
		Set<String> databaseAuthorities = Set.copyOf(jdbcTemplate.queryForList(
			"SELECT permission_code FROM admin_role_permissions WHERE role_code = 'DATA_OPERATOR'",
			String.class
		));
		Set<String> javaAuthorities = AdminRbacRole.DATA_OPERATOR.permissions().stream()
			.map(permission -> permission.authority())
			.collect(Collectors.toUnmodifiableSet());

		assertThat(javaAuthorities)
			.contains("admin.batch.run")
			.isEqualTo(databaseAuthorities);
	}

	private DriverManagerDataSource dataSource(String name) {
		return new DriverManagerDataSource(
			"jdbc:h2:mem:v63-" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
	}

	private void migrateToV62(DriverManagerDataSource dataSource) {
		Flyway.configure()
			.dataSource(dataSource)
			.locations("classpath:db/migration/h2")
			.target(MigrationVersion.fromVersion("62"))
			.load()
			.migrate();
	}

	private void migrateLatest(DriverManagerDataSource dataSource) {
		Flyway.configure()
			.dataSource(dataSource)
			.locations("classpath:db/migration/h2")
			.load()
			.migrate();
	}

	private void insertLegacyRunningRun(JdbcTemplate jdbcTemplate, String runId) {
		jdbcTemplate.update("""
			INSERT INTO data_collection_runs (
				run_id, source, status, requested_by, started_at, completed_at,
				collected_count, failure_message, retryable, operator_action
			)
			VALUES (?, 'TRANSIT_MASTER', 'RUNNING', 'legacy-admin', CURRENT_TIMESTAMP,
				NULL, 0, NULL, FALSE, '수집 실행 중입니다.')
			""", runId);
	}
}
