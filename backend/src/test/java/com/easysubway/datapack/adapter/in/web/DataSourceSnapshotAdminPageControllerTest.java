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
@DisplayName("관리자 데이터팩 source snapshot 화면")
class DataSourceSnapshotAdminPageControllerTest {

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
		jdbcTemplate.update("""
			INSERT INTO data_source_snapshots (
				snapshot_id, source_id, provider, retrieved_at, source_updated_at, row_count,
				raw_sha256, raw_object_uri, redacted_request_fingerprint, schema_fingerprint,
				snapshot_status, schema_status, license_status, fetch_status, redistribution_allowed,
				credential_redacted, previous_snapshot_id, diff_summary, freshness_expires_at,
				raw_retention_expires_at
			)
			VALUES ('snapshot-kric-20260629', 'kric-station-elevator', '국가철도공단',
				'2026-06-29 03:00:00', '2026-06-28 00:00:00', 12345,
				?, 's3://easysubway-datapack-sources/kric-station-elevator/snapshot-kric-20260629.json',
				?, ?, 'LOCKED', 'PASS', 'PASS', 'SUCCESS', TRUE, TRUE, NULL,
				'이전 snapshot 대비 +12 rows', '2026-07-06 03:00:00', '2026-09-29 03:00:00')
			""",
			"a".repeat(64),
			"b".repeat(64),
			"c".repeat(64)
		);
	}

	@Test
	@DisplayName("datapack read 권한 관리자는 source snapshot 목록을 확인한다")
	void datapackReadAdminViewsSourceSnapshotList() throws Exception {
		String html = mockMvc.perform(get("/admin/datapack/source-snapshots/page")
				.with(user("datapack-viewer").authorities(new SimpleGrantedAuthority("admin.datapack.read"))))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("Source Snapshots")
			.contains("kric-station-elevator")
			.contains("국가철도공단")
			.contains("12345")
			.contains("LOCKED")
			.contains("PASS")
			.contains("SUCCESS")
			.contains("이전 snapshot 대비 +12 rows")
			.contains("/admin/datapack/source-snapshots/snapshot-kric-20260629/page")
			.doesNotContain("serviceKey");
	}

	@Test
	@DisplayName("source snapshot 상세는 raw 전문 없이 metadata와 evidence 위치만 보여준다")
	void datapackReadAdminViewsSourceSnapshotDetail() throws Exception {
		String html = mockMvc.perform(get("/admin/datapack/source-snapshots/snapshot-kric-20260629/page")
				.with(user("datapack-viewer").authorities(new SimpleGrantedAuthority("admin.datapack.read"))))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("snapshot-kric-20260629")
			.contains("raw sha256")
			.contains("s3://easysubway-datapack-sources/kric-station-elevator/snapshot-kric-20260629.json")
			.contains("credential redacted")
			.contains("raw retention")
			.doesNotContain("name=\"commandToken\"")
			.doesNotContain("수집 실행")
			.doesNotContain("serviceKey");
	}

	@Test
	@DisplayName("datapack read 권한이 없으면 source snapshot 화면에 접근할 수 없다")
	void sourceSnapshotPageRequiresDatapackReadPermission() throws Exception {
		mockMvc.perform(get("/admin/datapack/source-snapshots/page")
				.with(user("viewer").authorities(new SimpleGrantedAuthority("admin.view"))))
			.andExpect(status().isForbidden());
	}
}
