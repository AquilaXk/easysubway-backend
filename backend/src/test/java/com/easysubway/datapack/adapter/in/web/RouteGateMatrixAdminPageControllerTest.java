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
@DisplayName("관리자 데이터팩 route gate matrix 화면")
class RouteGateMatrixAdminPageControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM route_edge_evidence");
		jdbcTemplate.update("DELETE FROM facility_evidence");
		jdbcTemplate.update("DELETE FROM manual_overrides");
		jdbcTemplate.update("DELETE FROM source_quarantine_resolutions");
		jdbcTemplate.update("DELETE FROM source_quarantine_records");
		jdbcTemplate.update("DELETE FROM external_alias_approvals");
		jdbcTemplate.update("DELETE FROM datapack_normalization_runs");
		jdbcTemplate.update("DELETE FROM data_source_snapshots");
		insertSnapshot();
		insertVerifiedEntryEdge();
		insertGeneratedConnectorBlocker();
		insertStaleTransferBlocker();
	}

	@Test
	@DisplayName("datapack read 권한 관리자는 route gate matrix를 확인한다")
	void datapackReadAdminViewsRouteGateMatrix() throws Exception {
		String html = mockMvc.perform(get("/admin/datapack/route-gates/page")
				.with(user("datapack-viewer").authorities(new SimpleGrantedAuthority("admin.datapack.read"))))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("Route Gate Matrix")
			.contains("station-sangnoksu")
			.contains("line-4")
			.contains("ENTRY")
			.contains("VERIFIED")
			.contains("OFFICIAL_SOURCE")
			.contains("strict 가능")
			.contains("station-sadang")
			.contains("GENERATED_CONNECTOR")
			.contains("GENERATED")
			.contains("generated connector")
			.contains("strict 불가")
			.contains("station-transfer")
			.contains("TRANSFER")
			.contains("STALE")
			.contains("stale source")
			.doesNotContain("name=\"commandToken\"")
			.doesNotContain("수정 저장")
			.doesNotContain("serviceKey");
	}

	@Test
	@DisplayName("datapack read 권한이 없으면 route gate matrix에 접근할 수 없다")
	void routeGateMatrixPageRequiresDatapackReadPermission() throws Exception {
		mockMvc.perform(get("/admin/datapack/route-gates/page")
				.with(user("viewer").authorities(new SimpleGrantedAuthority("admin.view"))))
			.andExpect(status().isForbidden());
	}

	private void insertSnapshot() {
		jdbcTemplate.update("""
			INSERT INTO data_source_snapshots (
				snapshot_id, source_id, provider, retrieved_at, source_updated_at, row_count,
				raw_sha256, raw_object_uri, redacted_request_fingerprint, schema_fingerprint,
				snapshot_status, schema_status, license_status, fetch_status, redistribution_allowed,
				credential_redacted, previous_snapshot_id, diff_summary, freshness_expires_at,
				raw_retention_expires_at
			)
			VALUES ('snapshot-route-20260629', 'route-source-a', '수도권 운영기관',
				'2026-06-29 03:00:00', '2026-06-28 00:00:00', 400,
				?, 's3://easysubway-datapack-sources/route-source-a/snapshot-route-20260629.json',
				?, ?, 'LOCKED', 'PASS', 'PASS', 'SUCCESS', TRUE, TRUE, NULL,
				'route edge evidence +3 rows', '2026-07-06 03:00:00', '2026-09-29 03:00:00')
			""",
			"a".repeat(64),
			"b".repeat(64),
			"c".repeat(64)
		);
	}

	private void insertVerifiedEntryEdge() {
		insertRouteEdge(
			"route-edge-entry-1",
			"station-sangnoksu",
			"line-4",
			"edge-entry-1",
			"ENTRY",
			"OFFICIAL_SOURCE",
			"VERIFIED",
			true,
			null,
			"d".repeat(64),
			"2026-06-29 03:10:00"
		);
	}

	private void insertGeneratedConnectorBlocker() {
		insertRouteEdge(
			"route-edge-generated-1",
			"station-sadang",
			"line-2",
			"edge-generated-1",
			"GENERATED_CONNECTOR",
			"GENERATED",
			"GENERATED",
			false,
			"generated connector",
			"e".repeat(64),
			"2026-06-29 03:11:00"
		);
	}

	private void insertStaleTransferBlocker() {
		insertRouteEdge(
			"route-edge-stale-transfer",
			"station-transfer",
			"line-2",
			"edge-transfer-stale",
			"TRANSFER",
			"OPERATOR_CONFIRMED",
			"STALE",
			false,
			"stale source",
			"f".repeat(64),
			"2026-06-29 03:12:00"
		);
	}

	private void insertRouteEdge(
		String id,
		String stationId,
		String lineId,
		String edgeId,
		String edgeType,
		String provenanceKind,
		String verificationStatus,
		boolean strictRouteEligible,
		String blockerReason,
		String evidenceHash,
		String createdAt
	) {
		jdbcTemplate.update("""
			INSERT INTO route_edge_evidence (
				id, station_id, line_id, edge_id, edge_type, source_id,
				source_snapshot_id, provenance_kind, verification_status, last_verified_at,
				evidence_hash, strict_route_eligible, blocker_reason, created_at
			)
			VALUES (?, ?, ?, ?, ?, 'route-source-a', 'snapshot-route-20260629',
				?, ?, '2026-06-29 03:00:00', ?, ?, ?, ?)
			""",
			id,
			stationId,
			lineId,
			edgeId,
			edgeType,
			provenanceKind,
			verificationStatus,
			evidenceHash,
			strictRouteEligible,
			blockerReason,
			createdAt
		);
	}
}
