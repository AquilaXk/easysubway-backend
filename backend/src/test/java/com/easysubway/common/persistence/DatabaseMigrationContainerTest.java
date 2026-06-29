package com.easysubway.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
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
				"data_source_snapshots",
				"external_alias_approvals",
				"source_quarantine_records",
				"source_quarantine_resolutions",
				"transit_master_overrides",
				"transit_master_override_audits"
			);
		assertThat(successfulMigrationVersions(jdbcTemplate)).contains("1", "14", "16", "17");
		assertThat(foreignKeyNames(jdbcTemplate))
			.contains(
				"fk_facility_report_review_audits_report",
				"fk_data_source_snapshots_previous",
				"fk_external_alias_approvals_snapshot_source",
				"fk_external_alias_approvals_superseded",
				"fk_source_quarantine_records_snapshot_source",
				"fk_source_quarantine_resolutions_record"
			);
		assertThat(checkConstraintNames(jdbcTemplate))
			.contains(
				"chk_external_alias_approvals_confidence",
				"chk_external_alias_approvals_approved_state",
				"chk_source_quarantine_records_resolution_state",
				"chk_source_quarantine_resolutions_status"
			);
		assertSnapshotSourceForeignKeysRejectMismatch(jdbcTemplate);
	}

	@Test
	@DisplayName("H2 migration도 ledger row의 source와 snapshot source 불일치를 차단한다")
	void h2MigrationRejectsMismatchedLedgerSnapshotSource() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:datapack-ledger-source-fk;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		Flyway.configure()
			.dataSource(dataSource)
			.locations("classpath:db/migration/h2")
			.load()
			.migrate();

		assertSnapshotSourceForeignKeysRejectMismatch(new JdbcTemplate(dataSource));
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

	private List<String> checkConstraintNames(JdbcTemplate jdbcTemplate) {
		return jdbcTemplate.queryForList("""
			SELECT constraint_name
			FROM information_schema.table_constraints
			WHERE table_schema = 'public'
				AND constraint_type = 'CHECK'
			ORDER BY constraint_name
			""", String.class);
	}

	private void assertSnapshotSourceForeignKeysRejectMismatch(JdbcTemplate jdbcTemplate) {
		insertSnapshot(jdbcTemplate, "snapshot-a", "source-a");
		insertSnapshot(jdbcTemplate, "snapshot-b", "source-b");

		assertThatThrownBy(() -> insertAliasApproval(jdbcTemplate, "source-b", "snapshot-a"))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertQuarantineRecord(jdbcTemplate, "source-b", "snapshot-a"))
			.isInstanceOf(DataAccessException.class);
	}

	private void insertSnapshot(JdbcTemplate jdbcTemplate, String snapshotId, String sourceId) {
		jdbcTemplate.update("""
			INSERT INTO data_source_snapshots (
				snapshot_id, source_id, provider, retrieved_at, source_updated_at, row_count,
				raw_sha256, raw_object_uri, redacted_request_fingerprint, schema_fingerprint,
				snapshot_status, schema_status, license_status, fetch_status,
				redistribution_allowed, credential_redacted, previous_snapshot_id,
				diff_summary, freshness_expires_at
			)
			VALUES (?, ?, 'KRIC', '2026-06-29 00:00:00', NULL, 1, ?, ?, ?, ?,
				'LOCKED', 'PASS', 'PASS', 'SUCCESS', TRUE, TRUE, NULL, NULL, '2026-07-06 00:00:00')
			""",
			snapshotId,
			sourceId,
			"a".repeat(64),
			"s3://evidence/" + snapshotId,
			"b".repeat(64),
			"c".repeat(64)
		);
	}

	private void insertAliasApproval(JdbcTemplate jdbcTemplate, String sourceId, String sourceSnapshotId) {
		jdbcTemplate.update("""
			INSERT INTO external_alias_approvals (
				id, source_id, source_snapshot_id, provider_entity_type, provider_entity_id,
				canonical_entity_type, canonical_entity_id, confidence, match_method,
				approval_status, requested_by, approved_by, approved_at, evidence_hash,
				superseded_by, created_at
			)
			VALUES ('alias-mismatch', ?, ?, 'STATION', 'provider-station',
				'STATION', 'station-1', 90, 'AUTO', 'PENDING', 'qa', NULL, NULL, ?,
				NULL, '2026-06-29 00:00:00')
			""", sourceId, sourceSnapshotId, "d".repeat(64));
	}

	private void insertQuarantineRecord(JdbcTemplate jdbcTemplate, String sourceId, String sourceSnapshotId) {
		jdbcTemplate.update("""
			INSERT INTO source_quarantine_records (
				id, source_id, source_snapshot_id, provider_record_hash, reason_code,
				severity, redacted_excerpt, resolution_status, resolved_by, resolved_at,
				created_at
			)
			VALUES ('quarantine-mismatch', ?, ?, ?, 'ALIAS_CONFLICT',
				'P1', NULL, 'OPEN', NULL, NULL, '2026-06-29 00:00:00')
			""", sourceId, sourceSnapshotId, "e".repeat(64));
	}
}
