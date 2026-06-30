package com.easysubway.datapack.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-test-password"
})
@AutoConfigureMockMvc
@DisplayName("관리자 데이터팩 release blocker 요약")
class DatapackReleaseBlockerSummaryAdminPageTest {

	private static final String SHA_A = "a".repeat(64);
	private static final String SHA_B = "b".repeat(64);
	private static final String SHA_C = "c".repeat(64);
	private static final String SHA_D = "d".repeat(64);
	private static final String SHA_E = "e".repeat(64);
	private static final String SHA_F = "f".repeat(64);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		clearDatapackTables();
		insertSourceSnapshot();
		insertPreviousCandidate();
		insertCandidate();
		insertEvidenceBundle();
		insertProductionChannel();
		insertAliasQuarantineOverride();
		insertEvidenceBlockers();
	}

	@Test
	@DisplayName("통합 대시보드는 데이터팩 release blocker 요약을 보여준다")
	void dashboardShowsDatapackReleaseBlockerSummary() throws Exception {
		String html = getAdminHtml("/admin/dashboard/page");

		assertThat(html)
			.contains("데이터팩 출시 준비")
			.contains("FAIL")
			.contains("candidate-release-blocked")
			.contains(SHA_A)
			.contains("https://github.com/AquilaXk/easysubway/actions/runs/1164?redacted")
			.contains("candidate-previous-stable")
			.contains("production promote 차단: blocker 9건")
			.contains("전체 blocker 9건")
			.contains("alias 1")
			.contains("quarantine 1")
			.contains("manual override 1")
			.contains("route gate 1")
			.contains("manifest signature")
			.doesNotContain("name=\"commandToken\"")
			.doesNotContain("serviceKey");
	}

	@Test
	@DisplayName("evidence bundle 상태가 실패하면 candidate gate가 PASS여도 production promote를 차단한다")
	void dashboardBlocksProductionPromoteWhenEvidenceBundleStatusFails() throws Exception {
		jdbcTemplate.update("""
			UPDATE datapack_candidates
			SET coverage_status = 'PASS',
				route_regression_status = 'PASS',
				android_evidence_status = 'PASS'
			WHERE id = 'candidate-release-blocked'
			""");
		jdbcTemplate.update("""
			UPDATE datapack_release_evidence_bundles
			SET route_regression_status = 'FAIL',
				manifest_signature_status = 'PASS'
			WHERE candidate_id = 'candidate-release-blocked'
			""");
		jdbcTemplate.update("DELETE FROM external_alias_approvals");
		jdbcTemplate.update("DELETE FROM source_quarantine_records");
		jdbcTemplate.update("DELETE FROM manual_overrides");
		jdbcTemplate.update("DELETE FROM facility_evidence");
		jdbcTemplate.update("DELETE FROM route_edge_evidence");

		String dashboardHtml = getAdminHtml("/admin/dashboard/page");
		String qualityHtml = getAdminHtml("/admin/data-quality/page");

		assertThat(dashboardHtml)
			.contains("production promote 차단: blocker 1건")
			.contains("전체 blocker 1건")
			.doesNotContain("production promote 가능")
			.doesNotContain("READY");
		assertThat(qualityHtml)
			.contains("Route gate")
			.contains("ENTRY/EXIT/TRANSFER and generated connector gates");
	}

	@Test
	@DisplayName("datapack read 권한이 없으면 통합 대시보드 release blocker 요약을 숨긴다")
	void dashboardHidesDatapackReleaseBlockerSummaryWithoutDatapackRead() throws Exception {
		String html = getAdminHtmlWithoutDatapackRead("/admin/dashboard/page");

		assertThat(html)
			.doesNotContain("데이터팩 출시 준비")
			.doesNotContain("candidate-release-blocked")
			.doesNotContain("전체 blocker 9건");
	}

	@Test
	@DisplayName("datapack read 권한이 없으면 데이터 품질 release readiness matrix를 숨긴다")
	void qualityDashboardHidesReleaseReadinessWithoutDatapackRead() throws Exception {
		String html = getAdminHtmlWithoutDatapackRead("/admin/data-quality/page");

		assertThat(html)
			.doesNotContain("데이터팩 Release readiness")
			.doesNotContain("candidate-release-blocked")
			.doesNotContain("Manifest signature");
	}

	@Test
	@DisplayName("datapack read 권한이 없으면 역 상세 release readiness를 숨긴다")
	void stationDetailHidesReleaseReadinessWithoutDatapackRead() throws Exception {
		String html = getAdminHtmlWithoutDatapackRead("/admin/stations/station-sangnoksu/page");

		assertThat(html)
			.doesNotContain("Release readiness")
			.doesNotContain("상록수역 release blocker")
			.doesNotContain("확인 필요 2건");
	}

	@Test
	@DisplayName("데이터팩 근거가 없으면 release readiness는 집계 전 상태를 보여준다")
	void releaseReadinessFallsBackWhenDatapackEvidenceIsEmpty() throws Exception {
		clearDatapackTables();

		String dashboardHtml = getAdminHtml("/admin/dashboard/page");
		String qualityHtml = getAdminHtml("/admin/data-quality/page");
		String stationHtml = getAdminHtml("/admin/stations/station-sangnoksu/page");

		assertThat(dashboardHtml)
			.contains("데이터팩 출시 준비")
			.contains("확인 필요")
			.contains("전체 blocker 1건")
			.doesNotContain("FAIL")
			.doesNotContain("PASS");
		assertThat(qualityHtml)
			.contains("데이터팩 Release readiness")
			.contains("Source coverage")
			.contains("확인 필요");
		assertThat(stationHtml)
			.contains("Release readiness")
			.contains("상록수역 release blocker")
			.contains("확인 필요 0건")
			.contains("집계 전")
			.doesNotContain("PASS 0건");
	}

	@Test
	@DisplayName("대체된 manual override 이력은 release blocker로 집계하지 않는다")
	void supersededManualOverrideHistoryIsNotReleaseBlocker() throws Exception {
		insertSupersededManualOverrideHistory();

		String html = getAdminHtml("/admin/dashboard/page");

		assertThat(html)
			.contains("전체 blocker 9건")
			.contains("manual override 1")
			.doesNotContain("전체 blocker 10건")
			.doesNotContain("manual override 2");
	}

	@Test
	@DisplayName("데이터 품질 화면은 release readiness matrix를 보여준다")
	void qualityDashboardShowsReleaseReadinessMatrix() throws Exception {
		String html = getAdminHtml("/admin/data-quality/page");

		assertThat(html)
			.contains("데이터팩 Release readiness")
			.contains("Source coverage")
			.contains("Validator")
			.contains("Facility evidence")
			.contains("Route gate")
			.contains("Android evidence")
			.contains("Manifest signature")
			.contains("Manual override")
			.contains("FAIL")
			.contains("확인 필요");
	}

	@Test
	@DisplayName("역 상세 화면은 역 단위 release readiness를 보여준다")
	void stationDetailShowsStationReleaseReadiness() throws Exception {
		String html = getAdminHtml("/admin/stations/station-sangnoksu/page");

		assertThat(html)
			.contains("Release readiness")
			.contains("상록수역 release blocker")
			.contains("Facility evidence")
			.contains("Route gate")
			.contains("확인 필요 2건");
	}

	private String getAdminHtml(String path) throws Exception {
		return mockMvc.perform(get(path)
				.with(user("viewer").authorities(
					new SimpleGrantedAuthority("admin.view"),
					new SimpleGrantedAuthority("admin.datapack.read")
				)))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	private String getAdminHtmlWithoutDatapackRead(String path) throws Exception {
		return mockMvc.perform(get(path)
				.with(user("viewer").authorities(new SimpleGrantedAuthority("admin.view"))))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	private void clearDatapackTables() {
		jdbcTemplate.update("DELETE FROM datapack_release_channel_events");
		jdbcTemplate.update("DELETE FROM datapack_release_channels");
		jdbcTemplate.update("DELETE FROM datapack_release_evidence_bundles");
		jdbcTemplate.update("DELETE FROM datapack_candidate_inputs");
		jdbcTemplate.update("DELETE FROM datapack_candidates");
		jdbcTemplate.update("DELETE FROM route_edge_evidence");
		jdbcTemplate.update("DELETE FROM facility_evidence");
		jdbcTemplate.update("DELETE FROM manual_overrides");
		jdbcTemplate.update("DELETE FROM source_quarantine_resolutions");
		jdbcTemplate.update("DELETE FROM source_quarantine_records");
		jdbcTemplate.update("DELETE FROM external_alias_approvals");
		jdbcTemplate.update("DELETE FROM data_source_snapshots");
	}

	private void insertSourceSnapshot() {
		jdbcTemplate.update("""
			INSERT INTO data_source_snapshots (
				snapshot_id, source_id, provider, retrieved_at, source_updated_at,
				row_count, raw_sha256, raw_object_uri, redacted_request_fingerprint,
				schema_fingerprint, snapshot_status, schema_status, license_status,
				fetch_status, redistribution_allowed, credential_redacted,
				freshness_expires_at, raw_retention_expires_at
			)
			VALUES (
				'snapshot-release-blocked', 'kric-station-elevator', 'KRIC',
				'2026-06-29 03:00:00', '2026-06-28 03:00:00', 10, ?, 's3://raw/snapshot',
				?, ?, 'LOCKED', 'PASS', 'PASS', 'SUCCESS', TRUE, TRUE,
				'2026-07-06 03:00:00', '2026-09-29 03:00:00'
			)
			""", SHA_A, SHA_B, SHA_C);
	}

	private void insertCandidate() {
		jdbcTemplate.update("""
			INSERT INTO datapack_candidates (
				id, scope_id, artifact_kind, version, source_snapshot_set_hash,
				override_set_hash, build_spec_sha256, source_inventory_sha256,
				sqlite_sha256, gzip_sha256, manifest_sha256, coverage_status,
				validator_status, route_regression_status, android_evidence_status,
				approval_status, created_at
			)
			VALUES ('candidate-release-blocked', 'capital_pilot_android_v1', 'DATAPACK',
				'2026.06.30-cand.blocked', ?, ?, ?, ?, ?, ?, ?,
				'FAIL', 'PASS', 'FAIL', 'PENDING', 'READY_FOR_APPROVAL',
				'2026-06-29 03:30:00')
			""", SHA_A, SHA_B, SHA_C, SHA_D, SHA_E, SHA_F, "1".repeat(64));
	}

	private void insertPreviousCandidate() {
		jdbcTemplate.update("""
			INSERT INTO datapack_candidates (
				id, scope_id, artifact_kind, version, source_snapshot_set_hash,
				override_set_hash, build_spec_sha256, source_inventory_sha256,
				sqlite_sha256, gzip_sha256, manifest_sha256, coverage_status,
				validator_status, route_regression_status, android_evidence_status,
				approval_status, created_at
			)
			VALUES ('candidate-previous-stable', 'capital_pilot_android_v1', 'DATAPACK',
				'2026.06.29-cand.previous', ?, ?, ?, ?, ?, ?, ?,
				'PASS', 'PASS', 'PASS', 'PASS', 'PROMOTED',
				'2026-06-29 02:30:00')
			""", SHA_A, SHA_B, SHA_C, SHA_D, SHA_E, SHA_F, "2".repeat(64));
	}

	private void insertEvidenceBundle() {
		jdbcTemplate.update("""
			INSERT INTO datapack_release_evidence_bundles (
				id, candidate_id, evidence_bundle_sha256, workflow_run_url,
				validator_status, route_regression_status, manifest_signature_status,
				android_evidence_status, created_at
			)
			VALUES ('evidence-bundle-blocked', 'candidate-release-blocked', ?,
				'https://github.com/AquilaXk/easysubway/actions/runs/1164?serviceKey=secret',
				'PASS', 'PASS', 'FAIL', 'PASS', '2026-06-29 03:36:00')
			""", "3".repeat(64));
	}

	private void insertProductionChannel() {
		jdbcTemplate.update("""
			INSERT INTO datapack_release_channels (
				channel, candidate_id, manifest_url, manifest_sha256,
				previous_stable_candidate_id, previous_manifest_sha256,
				rollback_available, last_operation_type, last_operation_status,
				requested_by, approved_by, reason, idempotency_key, updated_at
			)
			VALUES ('production', 'candidate-release-blocked',
				'https://datapack.example.com/production/current.json', ?,
				'candidate-previous-stable', ?, TRUE, 'PROMOTE', 'PENDING',
				'data-operator', 'release-approver', 'release readiness blocked',
				'idempotency-readiness-1164', '2026-06-29 03:37:00')
			""", "1".repeat(64), "2".repeat(64));
	}

	private void insertAliasQuarantineOverride() {
		jdbcTemplate.update("""
			INSERT INTO external_alias_approvals (
				id, source_id, source_snapshot_id, provider_entity_type,
				provider_entity_id, canonical_entity_type, canonical_entity_id,
				confidence, match_method, approval_status, requested_by,
				evidence_hash, created_at
			)
			VALUES ('alias-pending', 'kric-station-elevator', 'snapshot-release-blocked',
				'STATION', 'provider-station', 'STATION', 'station-sangnoksu',
				73, 'AUTO', 'PENDING', 'data-operator', ?, '2026-06-29 03:31:00')
			""", SHA_D);
		jdbcTemplate.update("""
			INSERT INTO source_quarantine_records (
				id, source_id, source_snapshot_id, provider_record_hash, reason_code,
				severity, redacted_excerpt, resolution_status, created_at
			)
			VALUES ('quarantine-open', 'kric-station-elevator', 'snapshot-release-blocked',
				?, 'MISSING_FIELD', 'HIGH', 'station redacted', 'OPEN',
				'2026-06-29 03:32:00')
			""", SHA_E);
		jdbcTemplate.update("""
			INSERT INTO manual_overrides (
				id, entity_type, entity_id, field_name, before_value, after_value,
				reason_code, reason, evidence_uri, evidence_hash, requested_by,
				approval_status, conflict_status, strict_route_eligible,
				effective_from, expires_at, created_at
			)
			VALUES ('override-pending', 'FACILITY', 'facility-sangnoksu-elevator-1',
				'operational_status', 'UNKNOWN', 'AVAILABLE', 'FIELD_CHECK',
				'현장 확인 필요', 's3://evidence/override', ?, 'data-operator',
				'PENDING', 'NONE', FALSE, '2026-06-29 03:33:00',
				'2026-07-06 03:33:00', '2026-06-29 03:33:00')
			""", SHA_F);
	}

	private void insertSupersededManualOverrideHistory() {
		jdbcTemplate.update("""
			INSERT INTO manual_overrides (
				id, entity_type, entity_id, field_name, before_value, after_value,
				reason_code, reason, evidence_uri, evidence_hash, requested_by,
				approved_by, approved_at, approval_status, conflict_status,
				strict_route_eligible, effective_from, expires_at, created_at
			)
			VALUES ('override-replacement', 'FACILITY', 'facility-superseded',
				'operational_status', 'UNKNOWN', 'AVAILABLE', 'FIELD_CHECK',
				'대체 override 승인', 's3://evidence/replacement', ?, 'data-operator',
				'release-approver', '2026-06-29 03:40:00', 'APPROVED', 'NONE',
				FALSE, '2026-06-29 03:40:00', '2026-07-06 03:40:00',
				'2026-06-29 03:40:00')
			""", SHA_A);
		jdbcTemplate.update("""
			INSERT INTO manual_overrides (
				id, entity_type, entity_id, field_name, before_value, after_value,
				reason_code, reason, evidence_uri, evidence_hash, requested_by,
				approval_status, conflict_status, strict_route_eligible,
				effective_from, expires_at, superseded_by, created_at
			)
			VALUES ('override-superseded-history', 'FACILITY', 'facility-superseded',
				'operational_status', 'UNKNOWN', 'AVAILABLE', 'FIELD_CHECK',
				'대체된 override 이력', 's3://evidence/superseded', ?, 'data-operator',
				'SUPERSEDED', 'UNRESOLVED', TRUE, '2026-06-29 03:39:00',
				'2026-07-06 03:39:00', 'override-replacement',
				'2026-06-29 03:39:00')
			""", SHA_B);
	}

	private void insertEvidenceBlockers() {
		jdbcTemplate.update("""
			INSERT INTO facility_evidence (
				id, station_id, line_id, facility_type, evidence_kind, source_id,
				source_snapshot_id, provider_record_hash, status_meaning,
				installation_status, operational_status, verified_at, retrieved_at,
				freshness_expires_at, confidence, strict_route_eligible,
				strict_route_eligible_reason, conflict_status, created_at
			)
			VALUES ('facility-blocker', 'station-sangnoksu', 'line-4',
				'WHEELCHAIR_LIFT', 'UNKNOWN_PENDING_REVIEW', 'kric-station-elevator',
				'snapshot-release-blocked', ?, 'STATIC_LOCATION', 'UNKNOWN',
				'UNKNOWN', '2026-06-29 03:34:00', '2026-06-29 03:34:00',
				'2026-07-06 03:34:00', 40, FALSE, 'UNKNOWN_PENDING_REVIEW',
				'NONE', '2026-06-29 03:34:00')
			""", SHA_A);
		jdbcTemplate.update("""
			INSERT INTO route_edge_evidence (
				id, station_id, line_id, edge_id, edge_type, source_id,
				source_snapshot_id, provenance_kind, verification_status,
				last_verified_at, evidence_hash, strict_route_eligible,
				blocker_reason, created_at
			)
			VALUES ('route-blocker', 'station-sangnoksu', 'line-4', 'edge-generated',
				'GENERATED_CONNECTOR', 'kric-station-elevator', 'snapshot-release-blocked',
				'GENERATED', 'GENERATED', '2026-06-29 03:35:00', ?, FALSE,
				'GENERATED_CONNECTOR', '2026-06-29 03:35:00')
			""", SHA_B);
	}
}
