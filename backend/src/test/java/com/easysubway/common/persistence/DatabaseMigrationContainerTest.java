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
				"facility_evidence",
				"manual_overrides",
				"route_edge_evidence",
				"transit_master_overrides",
				"transit_master_override_audits"
			);
		assertThat(successfulMigrationVersions(jdbcTemplate)).contains("1", "14", "16", "17", "18", "19", "20");
		assertThat(foreignKeyNames(jdbcTemplate))
			.contains(
				"fk_facility_report_review_audits_report",
				"fk_data_source_snapshots_previous",
				"fk_external_alias_approvals_snapshot_source",
				"fk_external_alias_approvals_superseded",
				"fk_source_quarantine_records_snapshot_source",
				"fk_source_quarantine_resolutions_record",
				"fk_facility_evidence_manual_override",
				"fk_facility_evidence_snapshot_source",
				"fk_manual_overrides_superseded",
				"fk_route_edge_evidence_snapshot_source"
			);
		assertThat(checkConstraintNames(jdbcTemplate))
			.contains(
				"chk_external_alias_approvals_confidence",
				"chk_external_alias_approvals_approved_state",
				"chk_source_quarantine_records_resolution_state",
				"chk_source_quarantine_resolutions_status",
				"chk_facility_evidence_strict_route",
				"chk_manual_overrides_approval_state",
				"chk_manual_overrides_effective_window",
				"chk_manual_overrides_route_safety",
				"chk_route_edge_evidence_strict_route"
			);
		assertSnapshotSourceForeignKeysRejectMismatch(jdbcTemplate);
		assertFacilityEvidenceStrictRouteGuards(jdbcTemplate);
		assertManualOverrideProductionGuards(jdbcTemplate);
		assertRouteEdgeEvidenceStrictRouteGuards(jdbcTemplate);
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
