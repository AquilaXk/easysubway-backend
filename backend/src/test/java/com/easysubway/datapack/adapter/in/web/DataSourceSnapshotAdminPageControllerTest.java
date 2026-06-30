package com.easysubway.datapack.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

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
		jdbcTemplate.update("DELETE FROM datapack_source_snapshot_events");
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
			.contains("name=\"commandToken\"")
			.contains("LOCKED snapshot 저장")
			.doesNotContain("serviceKey");
	}

	@Test
	@DisplayName("source run 권한 관리자는 redacted LOCKED snapshot을 저장한다")
	void sourceRunAdminCreatesLockedSnapshot() throws Exception {
		mockMvc.perform(post("/admin/datapack/source-snapshots")
				.with(csrf())
				.with(commandToken("/admin/datapack/source-snapshots/snapshot-kric-20260629/page"))
				.with(user("source-runner").authorities(
					new SimpleGrantedAuthority("admin.datapack.read"),
					new SimpleGrantedAuthority("admin.datapack.source.run")
				))
				.param("snapshotId", "snapshot-kric-20260630")
				.param("sourceId", "kric-station-elevator")
				.param("provider", "국가철도공단")
				.param("retrievedAt", "2026-06-30T03:00:00")
				.param("sourceUpdatedAt", "2026-06-29T00:00:00")
				.param("rowCount", "12357")
				.param("rawSha256", "d".repeat(64))
				.param("rawObjectUri", "s3://easysubway-datapack-sources/kric-station-elevator/snapshot-kric-20260630.json")
				.param("redactedRequestFingerprint", "e".repeat(64))
				.param("schemaFingerprint", "f".repeat(64))
				.param("schemaStatus", "PASS")
				.param("licenseStatus", "PASS")
				.param("fetchStatus", "SUCCESS")
				.param("redistributionAllowed", "true")
				.param("credentialRedacted", "true")
				.param("previousSnapshotId", "snapshot-kric-20260629")
				.param("diffSummary", "이전 snapshot 대비 +12 rows")
				.param("freshnessExpiresAt", "2026-07-07T03:00:00")
				.param("rawRetentionExpiresAt", "2026-09-30T03:00:00")
				.param("reason", "official source refresh")
				.param("idempotencyKey", "source-snapshot-1162-20260630"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/admin/datapack/source-snapshots/snapshot-kric-20260630/page"));

		assertThat(snapshotValue("snapshot-kric-20260630", "snapshot_status")).isEqualTo("LOCKED");
		assertThat(snapshotValue("snapshot-kric-20260630", "row_count")).isEqualTo(12357);
		assertThat(snapshotValue("snapshot-kric-20260630", "credential_redacted")).isEqualTo(true);
		assertThat(eventValue("source-snapshot-1162-20260630", "requested_by")).isEqualTo("source-runner");
		assertThat(eventValue("source-snapshot-1162-20260630", "reason")).isEqualTo("official source refresh");
	}

	@Test
	@DisplayName("source run 권한 없이 source snapshot command를 실행할 수 없다")
	void sourceSnapshotCommandRequiresSourceRunPermission() throws Exception {
		mockMvc.perform(post("/admin/datapack/source-snapshots")
				.with(csrf())
				.with(commandToken("/admin/datapack/source-snapshots/snapshot-kric-20260629/page"))
				.with(user("datapack-viewer").authorities(new SimpleGrantedAuthority("admin.datapack.read")))
				.param("snapshotId", "snapshot-denied")
				.param("sourceId", "kric-station-elevator")
				.param("provider", "국가철도공단")
				.param("retrievedAt", "2026-06-30T03:00:00")
				.param("rowCount", "1")
				.param("rawSha256", "d".repeat(64))
				.param("rawObjectUri", "s3://bucket/snapshot-denied.json")
				.param("redactedRequestFingerprint", "e".repeat(64))
				.param("schemaFingerprint", "f".repeat(64))
				.param("schemaStatus", "PASS")
				.param("licenseStatus", "PASS")
				.param("fetchStatus", "SUCCESS")
				.param("redistributionAllowed", "true")
				.param("credentialRedacted", "true")
				.param("freshnessExpiresAt", "2026-07-07T03:00:00")
				.param("rawRetentionExpiresAt", "2026-09-30T03:00:00")
				.param("reason", "denied")
				.param("idempotencyKey", "source-snapshot-denied"))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("datapack read 권한이 없으면 source snapshot 화면에 접근할 수 없다")
	void sourceSnapshotPageRequiresDatapackReadPermission() throws Exception {
		mockMvc.perform(get("/admin/datapack/source-snapshots/page")
				.with(user("viewer").authorities(new SimpleGrantedAuthority("admin.view"))))
			.andExpect(status().isForbidden());
	}

	private Object snapshotValue(String snapshotId, String column) {
		return jdbcTemplate.queryForObject(
			"SELECT " + column + " FROM data_source_snapshots WHERE snapshot_id = ?",
			Object.class,
			snapshotId
		);
	}

	private Object eventValue(String idempotencyKey, String column) {
		return jdbcTemplate.queryForObject(
			"SELECT " + column + " FROM datapack_source_snapshot_events WHERE source_id = ? AND idempotency_key = ?",
			Object.class,
			"kric-station-elevator",
			idempotencyKey
		);
	}

	private RequestPostProcessor commandToken(String pagePath) {
		return request -> {
			MockHttpSession session = (MockHttpSession) request.getSession();
			request.addParameter("commandToken", commandTokenFrom(getAdminHtml(pagePath, session)));
			return request;
		};
	}

	private String getAdminHtml(String pagePath, MockHttpSession session) {
		try {
			return mockMvc.perform(get(pagePath)
					.session(session)
					.with(user("datapack-viewer").authorities(new SimpleGrantedAuthority("admin.datapack.read"))))
				.andReturn()
				.getResponse()
				.getContentAsString();
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static String commandTokenFrom(String html) {
		var matcher = Pattern.compile("name=\"commandToken\" value=\"([^\"]+)\"").matcher(html);
		if (!matcher.find()) {
			throw new IllegalStateException("commandToken missing");
		}
		return matcher.group(1);
	}
}
