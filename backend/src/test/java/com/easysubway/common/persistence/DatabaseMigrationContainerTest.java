package com.easysubway.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
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
				"datapack_normalization_runs",
				"datapack_normalized_outputs",
				"datapack_candidates",
				"datapack_candidate_inputs",
				"datapack_release_evidence_bundles",
				"datapack_release_channels",
				"datapack_release_channel_events",
				"external_alias_approvals",
				"source_quarantine_records",
				"source_quarantine_resolutions",
				"facility_evidence",
				"manual_overrides",
				"route_edge_evidence",
				"transit_master_overrides",
				"transit_master_override_audits"
			);
		assertThat(successfulMigrationVersions(jdbcTemplate)).contains("1", "14", "16", "17", "18", "19", "20", "21", "22", "23", "25", "26");
		assertThat(foreignKeyNames(jdbcTemplate))
			.contains(
				"fk_facility_report_review_audits_report",
				"fk_data_source_snapshots_previous",
				"fk_datapack_normalization_runs_snapshot_source",
				"fk_datapack_normalized_outputs_run",
				"fk_external_alias_approvals_snapshot_source",
				"fk_external_alias_approvals_superseded",
				"fk_source_quarantine_records_snapshot_source",
				"fk_source_quarantine_resolutions_record",
				"fk_facility_evidence_manual_override",
				"fk_facility_evidence_snapshot_source",
				"fk_manual_overrides_superseded",
				"fk_route_edge_evidence_snapshot_source",
				"fk_datapack_candidate_inputs_candidate",
				"fk_datapack_release_evidence_candidate",
				"fk_datapack_release_channels_candidate",
				"fk_datapack_release_channels_previous_candidate",
				"fk_datapack_release_channel_events_channel",
				"fk_datapack_release_channel_events_next_candidate"
			);
		assertThat(checkConstraintNames(jdbcTemplate))
			.contains(
				"chk_datapack_normalization_runs_counts",
				"chk_datapack_normalization_runs_finished_state",
				"chk_datapack_normalized_outputs_kind",
				"chk_external_alias_approvals_confidence",
				"chk_external_alias_approvals_approved_state",
				"chk_source_quarantine_records_resolution_state",
				"chk_source_quarantine_resolutions_status",
				"chk_data_source_snapshots_credential_redacted",
				"chk_data_source_snapshots_raw_object_uri",
				"chk_data_source_snapshots_raw_retention",
				"chk_facility_evidence_strict_route",
				"chk_manual_overrides_approval_state",
				"chk_manual_overrides_effective_window",
				"chk_manual_overrides_route_safety",
				"chk_route_edge_evidence_strict_route",
				"chk_datapack_candidates_gate_status",
				"chk_datapack_candidates_approval_status",
				"chk_datapack_release_evidence_status",
				"chk_datapack_release_channels_operation",
				"chk_datapack_release_channels_rollback_target",
				"chk_datapack_release_channel_events_operation"
			);
		assertNormalizationRunGuards(jdbcTemplate);
		assertSnapshotSourceForeignKeysRejectMismatch(jdbcTemplate);
		assertSnapshotRawEvidencePolicyGuards(jdbcTemplate);
		assertPostgresqlSnapshotRawEvidenceConstraintsAreStaged(jdbcTemplate);
		assertFacilityEvidenceStrictRouteGuards(jdbcTemplate);
		assertManualOverrideProductionGuards(jdbcTemplate);
		assertRouteEdgeEvidenceStrictRouteGuards(jdbcTemplate);
		assertDatapackPermissionMatrix(jdbcTemplate);
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

	@Test
	@DisplayName("H2 migration도 source snapshot raw evidence policy를 차단한다")
	void h2MigrationRejectsUnsafeSourceSnapshotEvidence() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:datapack-source-snapshot-evidence;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		Flyway.configure()
			.dataSource(dataSource)
			.locations("classpath:db/migration/h2")
			.load()
			.migrate();

		assertSnapshotRawEvidencePolicyGuards(new JdbcTemplate(dataSource));
	}

	@Test
	@DisplayName("H2 migration도 production manual override guard를 차단한다")
	void h2MigrationRejectsUnsafeManualOverrides() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:datapack-manual-overrides;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		Flyway.configure()
			.dataSource(dataSource)
			.locations("classpath:db/migration/h2")
			.load()
			.migrate();

		assertManualOverrideProductionGuards(new JdbcTemplate(dataSource));
	}

	@Test
	@DisplayName("H2 migration도 route edge evidence의 strict route guard를 차단한다")
	void h2MigrationRejectsUnsafeRouteEdgeEvidence() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:datapack-route-edge-evidence;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		Flyway.configure()
			.dataSource(dataSource)
			.locations("classpath:db/migration/h2")
			.load()
			.migrate();

		assertRouteEdgeEvidenceStrictRouteGuards(new JdbcTemplate(dataSource));
	}

	@Test
	@DisplayName("H2 migration도 facility evidence의 strict route guard를 차단한다")
	void h2MigrationRejectsUnsafeFacilityEvidence() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:datapack-facility-evidence;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		Flyway.configure()
			.dataSource(dataSource)
			.locations("classpath:db/migration/h2")
			.load()
			.migrate();

		assertFacilityEvidenceStrictRouteGuards(new JdbcTemplate(dataSource));
	}

	@Test
	@DisplayName("H2 migration도 normalization run과 output ledger guard를 차단한다")
	void h2MigrationRejectsUnsafeNormalizationRuns() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:datapack-normalization-runs;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		Flyway.configure()
			.dataSource(dataSource)
			.locations("classpath:db/migration/h2")
			.load()
			.migrate();

		assertNormalizationRunGuards(new JdbcTemplate(dataSource));
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

	private void assertPostgresqlSnapshotRawEvidenceConstraintsAreStaged(JdbcTemplate jdbcTemplate) {
		assertThat(jdbcTemplate.queryForList("""
			SELECT conname
			FROM pg_constraint
			WHERE conname IN (
				'chk_data_source_snapshots_credential_redacted',
				'chk_data_source_snapshots_raw_object_uri',
				'chk_data_source_snapshots_raw_retention'
			)
				AND convalidated = false
			ORDER BY conname
			""", String.class))
			.containsExactly(
				"chk_data_source_snapshots_credential_redacted",
				"chk_data_source_snapshots_raw_object_uri",
				"chk_data_source_snapshots_raw_retention"
			);
	}

	private void assertDatapackPermissionMatrix(JdbcTemplate jdbcTemplate) {
		assertThat(permissionAuthoritiesForRole(jdbcTemplate, "ADMIN_VIEWER"))
			.contains("admin.datapack.read");
		assertThat(permissionAuthoritiesForRole(jdbcTemplate, "REPORT_REVIEWER"))
			.doesNotContain("admin.datapack.read");
		assertThat(permissionAuthoritiesForRole(jdbcTemplate, "DATA_OPERATOR"))
			.contains(
				"admin.datapack.read",
				"admin.datapack.source.run",
				"admin.datapack.candidate.build",
				"admin.datapack.staging.promote"
			)
			.doesNotContain(
				"admin.datapack.override.approve",
				"admin.datapack.production.approve",
				"admin.datapack.rollback"
			);
		assertThat(permissionAuthoritiesForRole(jdbcTemplate, "MASTER_EDITOR"))
			.contains(
				"admin.datapack.read",
				"admin.datapack.alias.review",
				"admin.datapack.quarantine.review",
				"admin.datapack.evidence.review",
				"admin.datapack.override.request"
			)
			.doesNotContain("admin.datapack.production.approve", "admin.datapack.rollback");
		assertThat(permissionAuthoritiesForRole(jdbcTemplate, "FIELD_OPERATOR"))
			.contains(
				"admin.datapack.read",
				"admin.datapack.evidence.review",
				"admin.datapack.override.request"
			)
			.doesNotContain("admin.datapack.production.approve", "admin.datapack.rollback");
		assertThat(permissionAuthoritiesForRole(jdbcTemplate, "SECURITY_ADMIN"))
			.contains("admin.datapack.audit.read")
			.doesNotContain("admin.datapack.production.approve");
		assertThat(permissionAuthoritiesForRole(jdbcTemplate, "SUPER_ADMIN"))
			.contains(
				"admin.datapack.read",
				"admin.datapack.source.run",
				"admin.datapack.alias.review",
				"admin.datapack.quarantine.review",
				"admin.datapack.evidence.review",
				"admin.datapack.override.request",
				"admin.datapack.override.approve",
				"admin.datapack.candidate.build",
				"admin.datapack.staging.promote",
				"admin.datapack.production.approve",
				"admin.datapack.rollback",
				"admin.datapack.audit.read"
			);
		assertThatThrownBy(() -> jdbcTemplate.update("""
			INSERT INTO admin_role_permissions (role_code, permission_code, created_at)
			VALUES ('DATA_OPERATOR', 'admin.datapack.unlisted', CURRENT_TIMESTAMP)
			"""))
			.isInstanceOf(DataAccessException.class);
	}

	private List<String> permissionAuthoritiesForRole(JdbcTemplate jdbcTemplate, String roleCode) {
		return jdbcTemplate.queryForList("""
			SELECT permission_code
			FROM admin_role_permissions
			WHERE role_code = ?
			ORDER BY permission_code
			""", String.class, roleCode);
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
				diff_summary, freshness_expires_at, raw_retention_expires_at
			)
			VALUES (?, ?, 'KRIC', '2026-06-29 00:00:00', NULL, 1, ?, ?, ?, ?,
				'LOCKED', 'PASS', 'PASS', 'SUCCESS', TRUE, TRUE, NULL, NULL,
				'2026-07-06 00:00:00', '2026-09-29 00:00:00')
			""",
			snapshotId,
			sourceId,
			"a".repeat(64),
			"s3://evidence/" + snapshotId,
			"b".repeat(64),
			"c".repeat(64)
		);
	}

	private void assertSnapshotRawEvidencePolicyGuards(JdbcTemplate jdbcTemplate) {
		insertSnapshot(jdbcTemplate, "snapshot-raw-policy-ok", "source-raw-policy");

		assertThatThrownBy(() -> insertSnapshotEvidencePolicyCase(
			jdbcTemplate,
			"snapshot-raw-policy-unredacted",
			"s3://evidence/snapshot-raw-policy-unredacted.json",
			false,
			"2026-07-06 00:00:00"
		))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertSnapshotEvidencePolicyCase(
			jdbcTemplate,
			"snapshot-raw-policy-secret-uri",
			"s3://evidence/snapshot-raw-policy-secret-uri.json?serviceKey=secret",
			true,
			"2026-07-06 00:00:00"
		))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertSnapshotEvidencePolicyCase(
			jdbcTemplate,
			"snapshot-raw-policy-userinfo-uri",
			"s3://access:secret@evidence/snapshot-raw-policy-userinfo-uri.json",
			true,
			"2026-07-06 00:00:00"
		))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertSnapshotEvidencePolicyCase(
			jdbcTemplate,
			"snapshot-raw-policy-object-key-at-uri",
			"s3://evidence/raw/provider@example.json",
			true,
			"2026-07-06 00:00:00"
		))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertSnapshotEvidencePolicyCase(
			jdbcTemplate,
			"snapshot-raw-policy-fragment-uri",
			"oci://evidence/snapshot-raw-policy-fragment-uri.json#token=secret",
			true,
			"2026-07-06 00:00:00"
		))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertSnapshotEvidencePolicyCase(
			jdbcTemplate,
			"snapshot-raw-policy-empty-bucket-uri",
			"s3:///snapshot-raw-policy-empty-bucket-uri.json",
			true,
			"2026-07-06 00:00:00"
		))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertSnapshotEvidencePolicyCase(
			jdbcTemplate,
			"snapshot-raw-policy-bucket-only-uri",
			"s3://evidence",
			true,
			"2026-07-06 00:00:00"
		))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertSnapshotEvidencePolicyCase(
			jdbcTemplate,
			"snapshot-raw-policy-expired-retention",
			"s3://evidence/snapshot-raw-policy-expired-retention.json",
			true,
			"2026-06-28 00:00:00"
		))
			.isInstanceOf(DataAccessException.class);
	}

	private void insertSnapshotEvidencePolicyCase(
		JdbcTemplate jdbcTemplate,
		String snapshotId,
		String rawObjectUri,
		boolean credentialRedacted,
		String rawRetentionExpiresAt
	) {
		jdbcTemplate.update("""
			INSERT INTO data_source_snapshots (
				snapshot_id, source_id, provider, retrieved_at, source_updated_at, row_count,
				raw_sha256, raw_object_uri, redacted_request_fingerprint, schema_fingerprint,
				snapshot_status, schema_status, license_status, fetch_status,
				redistribution_allowed, credential_redacted, previous_snapshot_id,
				diff_summary, freshness_expires_at, raw_retention_expires_at
			)
			VALUES (?, 'source-raw-policy', 'KRIC', '2026-06-29 00:00:00', NULL, 1,
				?, ?, ?, ?, 'LOCKED', 'PASS', 'PASS', 'SUCCESS',
				TRUE, ?, NULL, NULL, '2026-07-06 00:00:00', ?)
			""",
			snapshotId,
			"a".repeat(64),
			rawObjectUri,
			"b".repeat(64),
			"c".repeat(64),
			credentialRedacted,
			Timestamp.valueOf(rawRetentionExpiresAt)
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

	private void assertNormalizationRunGuards(JdbcTemplate jdbcTemplate) {
		insertSnapshot(jdbcTemplate, "normalization-snapshot-a", "normalization-source-a");
		insertSnapshot(jdbcTemplate, "normalization-snapshot-b", "normalization-source-b");
		insertNormalizationRun(jdbcTemplate, "normalization-ok", "normalization-source-a", "normalization-snapshot-a",
			10, 6, 2, 2, "COMPLETED", "2026-06-29 00:10:00");
		insertNormalizedOutput(jdbcTemplate, "normalization-output-accepted", "normalization-ok", "ACCEPTED_ROWS", 6);
		insertNormalizedOutput(jdbcTemplate, "normalization-output-schema-diff", "normalization-ok", "SCHEMA_DIFF", 0);

		assertThatThrownBy(() -> insertNormalizationRun(jdbcTemplate, "normalization-source-mismatch", "normalization-source-b", "normalization-snapshot-a",
			1, 1, 0, 0, "COMPLETED", "2026-06-29 00:10:00"))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertNormalizationRun(jdbcTemplate, "normalization-negative-count", "normalization-source-a", "normalization-snapshot-a",
			1, -1, 0, 0, "COMPLETED", "2026-06-29 00:10:00"))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertNormalizationRun(jdbcTemplate, "normalization-unfinished-completed", "normalization-source-a", "normalization-snapshot-a",
			1, 1, 0, 0, "COMPLETED", null))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertNormalizedOutput(jdbcTemplate, "normalization-output-bad-kind", "normalization-ok", "UNKNOWN", 1))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertNormalizedOutput(jdbcTemplate, "normalization-output-orphan", "missing-run", "ACCEPTED_ROWS", 1))
			.isInstanceOf(DataAccessException.class);
	}

	private void insertNormalizationRun(
		JdbcTemplate jdbcTemplate,
		String runId,
		String sourceId,
		String sourceSnapshotId,
		int normalizedCount,
		int acceptedCount,
		int quarantineCount,
		int aliasReviewCount,
		String status,
		String completedAt
	) {
		jdbcTemplate.update("""
			INSERT INTO datapack_normalization_runs (
				id, source_id, source_snapshot_id, normalized_count, accepted_count,
				quarantine_count, alias_review_count, schema_diff_sha256,
				schema_diff_summary, status, started_at, completed_at
			)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'schema fields unchanged', ?,
				'2026-06-29 00:00:00', ?)
			""",
			runId,
			sourceId,
			sourceSnapshotId,
			normalizedCount,
			acceptedCount,
			quarantineCount,
			aliasReviewCount,
			"7".repeat(64),
			status,
			completedAt == null ? null : Timestamp.valueOf(completedAt)
		);
	}

	private void insertNormalizedOutput(
		JdbcTemplate jdbcTemplate,
		String outputId,
		String normalizationRunId,
		String outputKind,
		int rowCount
	) {
		jdbcTemplate.update("""
			INSERT INTO datapack_normalized_outputs (
				id, normalization_run_id, output_kind, row_count, output_sha256,
				object_uri, created_at
			)
			VALUES (?, ?, ?, ?, ?, ?, '2026-06-29 00:00:00')
			""",
			outputId,
			normalizationRunId,
			outputKind,
			rowCount,
			"6".repeat(64),
			"s3://evidence/normalized/" + outputId + ".json"
		);
	}

	private void assertManualOverrideProductionGuards(JdbcTemplate jdbcTemplate) {
		insertManualOverride(jdbcTemplate, "override-ok", "facility-1", "APPROVED", "qa", "reviewer", false, null, null);
		insertManualOverride(jdbcTemplate, "override-strict-pending", "facility-pending", "PENDING", "qa", null, true, null, null);

		assertThatThrownBy(() ->
			insertManualOverride(jdbcTemplate, "override-self-approved", "facility-self", "APPROVED", "qa", "qa", false, null, null))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() ->
			insertManualOverride(jdbcTemplate, "override-strict-unsafe", "facility-strict", "APPROVED", "qa", "reviewer", true, null, null))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() ->
			insertManualOverride(jdbcTemplate, "override-duplicate", "facility-1", "APPROVED", "qa2", "reviewer2", false, null, null))
			.isInstanceOf(DataAccessException.class);
	}

	private void insertManualOverride(
		JdbcTemplate jdbcTemplate,
		String overrideId,
		String entityId,
		String approvalStatus,
		String requestedBy,
		String approvedBy,
		boolean strictRouteEligible,
		String routeSafetyApprovedBy,
		String supersededBy
	) {
		jdbcTemplate.update("""
			INSERT INTO manual_overrides (
				id, entity_type, entity_id, field_name, before_value, after_value,
				reason_code, reason, evidence_uri, evidence_hash, requested_by,
				approved_by, approved_at, route_safety_approved_by, approval_status,
				conflict_status, strict_route_eligible, effective_from, expires_at,
				superseded_by, created_at
			)
			VALUES (?, 'FACILITY', ?, 'operational_status', 'UNKNOWN', 'AVAILABLE',
				'FIELD_VERIFIED', '현장 검증 결과 반영', 's3://evidence/manual/1.json', ?,
				?, ?, '2026-06-29 01:00:00', ?, ?, 'NONE', ?,
				'2026-06-29 00:00:00', '2026-07-29 00:00:00', ?, '2026-06-29 00:00:00')
			""",
			overrideId,
			entityId,
			"f".repeat(64),
			requestedBy,
			approvedBy,
			routeSafetyApprovedBy,
			approvalStatus,
			strictRouteEligible,
			supersededBy
		);
	}

	private void assertFacilityEvidenceStrictRouteGuards(JdbcTemplate jdbcTemplate) {
		insertSnapshot(jdbcTemplate, "facility-snapshot-a", "facility-source-a");
		insertSnapshot(jdbcTemplate, "facility-snapshot-b", "facility-source-b");
		insertFacilityEvidence(jdbcTemplate, "facility-evidence-ok", "facility-source-a", "facility-snapshot-a",
			"EXISTS", "INSTALLED", "AVAILABLE", "OPERATOR_CONFIRMED", true, null);
		insertFacilityEvidence(jdbcTemplate, "facility-static-visible", "facility-source-a", "facility-snapshot-a",
			"EXISTS", "INSTALLED", "UNKNOWN", "STATIC_LOCATION", false, "OPERATIONAL_STATUS_UNKNOWN");

		assertThatThrownBy(() -> insertFacilityEvidence(jdbcTemplate, "facility-source-mismatch", "facility-source-b", "facility-snapshot-a",
			"EXISTS", "INSTALLED", "AVAILABLE", "OPERATOR_CONFIRMED", true, null))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertFacilityEvidence(jdbcTemplate, "facility-unknown-strict", "facility-source-a", "facility-snapshot-a",
			"UNKNOWN_PENDING_REVIEW", "UNKNOWN", "UNKNOWN", "STATIC_LOCATION", true, "UNKNOWN_PENDING_REVIEW"))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertFacilityEvidence(jdbcTemplate, "facility-static-strict", "facility-source-a", "facility-snapshot-a",
			"EXISTS", "INSTALLED", "UNKNOWN", "STATIC_LOCATION", true, "OPERATIONAL_STATUS_UNKNOWN"))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertFacilityEvidence(jdbcTemplate, "facility-orphan-override", "facility-source-a", "facility-snapshot-a",
			"EXISTS", "INSTALLED", "AVAILABLE", "OPERATOR_CONFIRMED", true, null, "missing-override"))
			.isInstanceOf(DataAccessException.class);
	}

	private void insertFacilityEvidence(
		JdbcTemplate jdbcTemplate,
		String evidenceId,
		String sourceId,
		String sourceSnapshotId,
		String evidenceKind,
		String installationStatus,
		String operationalStatus,
		String statusMeaning,
		boolean strictRouteEligible,
		String strictRouteEligibleReason
	) {
		insertFacilityEvidence(
			jdbcTemplate,
			evidenceId,
			sourceId,
			sourceSnapshotId,
			evidenceKind,
			installationStatus,
			operationalStatus,
			statusMeaning,
			strictRouteEligible,
			strictRouteEligibleReason,
			null
		);
	}

	private void insertFacilityEvidence(
		JdbcTemplate jdbcTemplate,
		String evidenceId,
		String sourceId,
		String sourceSnapshotId,
		String evidenceKind,
		String installationStatus,
		String operationalStatus,
		String statusMeaning,
		boolean strictRouteEligible,
		String strictRouteEligibleReason,
		String manualOverrideId
	) {
		jdbcTemplate.update("""
			INSERT INTO facility_evidence (
				id, station_id, line_id, facility_type, evidence_kind, source_id,
				source_snapshot_id, provider_record_hash, status_meaning,
				installation_status, operational_status, verified_at, retrieved_at,
				freshness_expires_at, confidence, strict_route_eligible,
				strict_route_eligible_reason, conflict_status, manual_override_id, created_at
			)
			VALUES (?, 'station-1', 'line-1', 'ELEVATOR', ?, ?, ?, ?, ?, ?, ?,
				'2026-06-29 00:00:00', '2026-06-29 00:00:00', '2026-07-06 00:00:00',
				90, ?, ?, 'NONE', ?, '2026-06-29 00:00:00')
			""",
			evidenceId,
			evidenceKind,
			sourceId,
			sourceSnapshotId,
			"8".repeat(64),
			statusMeaning,
			installationStatus,
			operationalStatus,
			strictRouteEligible,
			strictRouteEligibleReason,
			manualOverrideId
		);
	}

	private void assertRouteEdgeEvidenceStrictRouteGuards(JdbcTemplate jdbcTemplate) {
		insertSnapshot(jdbcTemplate, "route-snapshot-a", "route-source-a");
		insertSnapshot(jdbcTemplate, "route-snapshot-b", "route-source-b");
		insertRouteEdgeEvidence(jdbcTemplate, "route-edge-ok", "route-source-a", "route-snapshot-a",
			"ENTRY", "OFFICIAL_SOURCE", "VERIFIED", true, null);
		insertRouteEdgeEvidence(jdbcTemplate, "route-edge-generated-visible", "route-source-a", "route-snapshot-a",
			"GENERATED_CONNECTOR", "GENERATED", "GENERATED", false, "GENERATED_CONNECTOR_BLOCKED");

		assertThatThrownBy(() -> insertRouteEdgeEvidence(jdbcTemplate, "route-edge-source-mismatch", "route-source-b", "route-snapshot-a",
			"ENTRY", "OFFICIAL_SOURCE", "VERIFIED", true, null))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertRouteEdgeEvidence(jdbcTemplate, "route-edge-unknown-strict", "route-source-a", "route-snapshot-a",
			"EXIT", "OFFICIAL_SOURCE", "UNKNOWN", true, "UNKNOWN_PENDING_REVIEW"))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertRouteEdgeEvidence(jdbcTemplate, "route-edge-generated-strict", "route-source-a", "route-snapshot-a",
			"GENERATED_CONNECTOR", "GENERATED", "GENERATED", true, "GENERATED_CONNECTOR_BLOCKED"))
			.isInstanceOf(DataAccessException.class);
	}

	private void insertRouteEdgeEvidence(
		JdbcTemplate jdbcTemplate,
		String evidenceId,
		String sourceId,
		String sourceSnapshotId,
		String edgeType,
		String provenanceKind,
		String verificationStatus,
		boolean strictRouteEligible,
		String blockerReason
	) {
		jdbcTemplate.update("""
			INSERT INTO route_edge_evidence (
				id, station_id, line_id, edge_id, edge_type, source_id, source_snapshot_id,
				provenance_kind, verification_status, last_verified_at, evidence_hash,
				strict_route_eligible, blocker_reason, created_at
			)
			VALUES (?, 'station-1', 'line-1', ?, ?, ?, ?, ?, ?,
				'2026-06-29 00:00:00', ?, ?, ?, '2026-06-29 00:00:00')
			""",
			evidenceId,
			"edge-" + evidenceId,
			edgeType,
			sourceId,
			sourceSnapshotId,
			provenanceKind,
			verificationStatus,
			"9".repeat(64),
			strictRouteEligible,
			blockerReason
		);
	}
}
