package com.easysubway.datapack.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@DisplayName("datapack_release_request 스키마")
class DatapackReleaseRequestSchemaTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	@DisplayName("insert/select이 모든 컬럼을 왕복한다")
	void roundTripsAllColumns() {
		jdbcTemplate.update("DELETE FROM datapack_release_request");
		jdbcTemplate.update(
			"INSERT INTO datapack_release_request ("
				+ "approval_id, candidate_id, scope_id, target_channel, build_spec_sha256,"
				+ " source_snapshot_set_hash, approved_ledger_hash, requested_by, status,"
				+ " created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
			"appr-1", "cand-1", "scope-1", "staging", "a".repeat(64),
			"b".repeat(64), "c".repeat(64), "requester", "REQUESTED",
			java.sql.Timestamp.valueOf("2026-07-06 00:00:00"), java.sql.Timestamp.valueOf("2026-07-06 00:00:00"));

		var row = jdbcTemplate.queryForMap(
			"SELECT candidate_id, scope_id, target_channel, build_spec_sha256, source_snapshot_set_hash,"
				+ " approved_ledger_hash, requested_by, status FROM datapack_release_request WHERE approval_id = ?",
			"appr-1");
		assertThat(row.get("candidate_id")).isEqualTo("cand-1");
		assertThat(row.get("scope_id")).isEqualTo("scope-1");
		assertThat(row.get("target_channel")).isEqualTo("staging");
		assertThat(row.get("build_spec_sha256")).isEqualTo("a".repeat(64));
		assertThat(row.get("source_snapshot_set_hash")).isEqualTo("b".repeat(64));
		assertThat(row.get("approved_ledger_hash")).isEqualTo("c".repeat(64));
		assertThat(row.get("requested_by")).isEqualTo("requester");
		assertThat(row.get("status")).isEqualTo("REQUESTED");
	}
}
