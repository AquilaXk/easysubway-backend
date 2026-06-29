package com.easysubway.datapack.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.datapack.domain.DataSourceSnapshot;
import com.easysubway.datapack.domain.InvalidDataSourceSnapshotException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@DisplayName("JDBC 데이터팩 source snapshot 저장소")
class JdbcDataSourceSnapshotRepositoryTest {

	private JdbcDataSourceSnapshotRepository repository;

	@BeforeEach
	void setUp() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:datapack-source-snapshots;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		var jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.execute("DROP TABLE IF EXISTS data_source_snapshots");
		jdbcTemplate.execute("""
			CREATE TABLE data_source_snapshots (
				snapshot_id VARCHAR(120) PRIMARY KEY,
				source_id VARCHAR(120) NOT NULL,
				provider VARCHAR(120) NOT NULL,
				retrieved_at TIMESTAMP NOT NULL,
				source_updated_at TIMESTAMP,
				row_count INTEGER NOT NULL,
				raw_sha256 VARCHAR(64) NOT NULL,
				raw_object_uri VARCHAR(1000) NOT NULL,
				redacted_request_fingerprint VARCHAR(64) NOT NULL,
				schema_fingerprint VARCHAR(64) NOT NULL,
				snapshot_status VARCHAR(30) NOT NULL,
				schema_status VARCHAR(30) NOT NULL,
				license_status VARCHAR(30) NOT NULL,
				fetch_status VARCHAR(30) NOT NULL,
				redistribution_allowed BOOLEAN NOT NULL,
				credential_redacted BOOLEAN NOT NULL,
				previous_snapshot_id VARCHAR(120),
				diff_summary VARCHAR(1000),
				freshness_expires_at TIMESTAMP NOT NULL
			)
			""");
		repository = new JdbcDataSourceSnapshotRepository(jdbcTemplate);
	}

	@Test
	@DisplayName("source snapshot을 저장하고 식별자로 조회한다")
	void saveAndLoadSnapshot() {
		var snapshot = lockedSnapshot("snapshot-1", "a".repeat(64), 13);

		repository.saveSnapshot(snapshot);

		assertThat(repository.loadSnapshot("snapshot-1")).contains(snapshot);
	}

	@Test
	@DisplayName("LOCKED source snapshot의 raw hash, object URI, row count, schema fingerprint는 바꿀 수 없다")
	void lockedSnapshotImmutableFieldsCannotChange() {
		repository.saveSnapshot(lockedSnapshot("snapshot-1", "a".repeat(64), 13));
		var changedRawHash = lockedSnapshot("snapshot-1", "b".repeat(64), 13);

		assertThatThrownBy(() -> repository.saveSnapshot(changedRawHash))
			.isInstanceOf(InvalidDataSourceSnapshotException.class)
			.hasMessageContaining("LOCKED source snapshot");
	}

	@Test
	@DisplayName("DB timestamp 정밀도보다 작은 nano 값이 있어도 같은 snapshot 재저장은 허용한다")
	void sameSnapshotWithNanosecondTimestampIsIdempotent() {
		var snapshot = lockedSnapshot("snapshot-1", "a".repeat(64), 13);

		repository.saveSnapshot(snapshot);

		assertThat(repository.saveSnapshot(snapshot)).isEqualTo(snapshot);
	}

	private DataSourceSnapshot lockedSnapshot(String snapshotId, String rawSha256, int rowCount) {
		return new DataSourceSnapshot(
			snapshotId,
			"kric-station-elevator",
			"국가철도공단",
			LocalDateTime.of(2026, 6, 29, 3, 0, 0, 123456789),
			LocalDateTime.of(2026, 6, 28, 0, 0, 0, 987654321),
			rowCount,
			rawSha256,
			"s3://easysubway-datapack-sources/kric-station-elevator/%s.json".formatted(snapshotId),
			"c".repeat(64),
			"d".repeat(64),
			"LOCKED",
			"PASS",
			"PASS",
			"SUCCESS",
			true,
			true,
			null,
			"initial snapshot",
			LocalDateTime.of(2026, 7, 6, 3, 0, 0, 555555555)
		);
	}
}
