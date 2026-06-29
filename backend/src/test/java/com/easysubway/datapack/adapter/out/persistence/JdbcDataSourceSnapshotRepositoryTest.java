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
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:datapack-source-snapshots;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		jdbcTemplate = new JdbcTemplate(dataSource);
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
				freshness_expires_at TIMESTAMP NOT NULL,
				raw_retention_expires_at TIMESTAMP NOT NULL
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

	@Test
	@DisplayName("raw evidence는 credential redaction과 credential 없는 object URI가 필요하다")
	void rawEvidenceRequiresRedactedCredentialAndObjectUri() {
		assertThatThrownBy(() -> repository.saveSnapshot(lockedSnapshot(
			"snapshot-unredacted",
			"a".repeat(64),
			13,
			"s3://easysubway-datapack-sources/kric-station-elevator/snapshot-unredacted.json",
			false
		)))
			.isInstanceOf(InvalidDataSourceSnapshotException.class)
			.hasMessageContaining("credentialRedacted");
		assertThatThrownBy(() -> repository.saveSnapshot(lockedSnapshot(
			"snapshot-uri-secret",
			"a".repeat(64),
			13,
			"s3://easysubway-datapack-sources/kric-station-elevator/snapshot-uri-secret.json?serviceKey=secret",
			true
		)))
			.isInstanceOf(InvalidDataSourceSnapshotException.class)
			.hasMessageContaining("rawObjectUri");
		assertThatThrownBy(() -> repository.saveSnapshot(lockedSnapshot(
			"snapshot-uri-userinfo",
			"a".repeat(64),
			13,
			"s3://access:secret@easysubway-datapack-sources/kric-station-elevator/snapshot-uri-userinfo.json",
			true
		)))
			.isInstanceOf(InvalidDataSourceSnapshotException.class)
			.hasMessageContaining("rawObjectUri");
		assertThatThrownBy(() -> repository.saveSnapshot(lockedSnapshot(
			"snapshot-uri-object-key-at",
			"a".repeat(64),
			13,
			"s3://easysubway-datapack-sources/kric-station-elevator/provider@example.json",
			true
		)))
			.isInstanceOf(InvalidDataSourceSnapshotException.class)
			.hasMessageContaining("rawObjectUri");
		assertThatThrownBy(() -> repository.saveSnapshot(lockedSnapshot(
			"snapshot-uri-fragment",
			"a".repeat(64),
			13,
			"oci://easysubway-datapack-sources/kric-station-elevator/snapshot-uri-fragment.json#token=secret",
			true
		)))
			.isInstanceOf(InvalidDataSourceSnapshotException.class)
			.hasMessageContaining("rawObjectUri");
		assertThatThrownBy(() -> repository.saveSnapshot(lockedSnapshot(
			"snapshot-uri-empty-bucket",
			"a".repeat(64),
			13,
			"s3:///kric-station-elevator/snapshot-uri-empty-bucket.json",
			true
		)))
			.isInstanceOf(InvalidDataSourceSnapshotException.class)
			.hasMessageContaining("rawObjectUri");
		assertThatThrownBy(() -> repository.saveSnapshot(lockedSnapshot(
			"snapshot-uri-bucket-only",
			"a".repeat(64),
			13,
			"s3://easysubway-datapack-sources",
			true
		)))
			.isInstanceOf(InvalidDataSourceSnapshotException.class)
			.hasMessageContaining("rawObjectUri");
	}

	@Test
	@DisplayName("staged constraint cleanup 전 legacy snapshot row도 조회할 수 있다")
	void loadSnapshotAllowsLegacyRowsBeforeConstraintValidation() {
		insertLegacyUnsafeSnapshot();

		var snapshot = repository.loadSnapshot("snapshot-legacy-unsafe");

		assertThat(snapshot).isPresent();
		assertThat(snapshot.get().credentialRedacted()).isFalse();
		assertThat(snapshot.get().rawObjectUri()).isEqualTo("s3:///legacy-unsafe.json");
		assertThat(snapshot.get().rawRetentionExpiresAt()).isEqualTo(snapshot.get().retrievedAt());
	}

	private void insertLegacyUnsafeSnapshot() {
		jdbcTemplate.update("""
			INSERT INTO data_source_snapshots (
				snapshot_id, source_id, provider, retrieved_at, source_updated_at, row_count,
				raw_sha256, raw_object_uri, redacted_request_fingerprint, schema_fingerprint,
				snapshot_status, schema_status, license_status, fetch_status, redistribution_allowed,
				credential_redacted, previous_snapshot_id, diff_summary, freshness_expires_at,
				raw_retention_expires_at
			)
			VALUES ('snapshot-legacy-unsafe', 'kric-station-elevator', '국가철도공단',
				'2026-06-29 03:00:00', NULL, 13, ?, 's3:///legacy-unsafe.json', ?, ?,
				'LOCKED', 'PASS', 'PASS', 'SUCCESS', TRUE, FALSE, NULL, 'legacy unsafe row',
				'2026-07-06 03:00:00', '2026-06-29 03:00:00')
			""",
			"a".repeat(64),
			"c".repeat(64),
			"d".repeat(64)
		);
	}

	private DataSourceSnapshot lockedSnapshot(String snapshotId, String rawSha256, int rowCount) {
		return lockedSnapshot(
			snapshotId,
			rawSha256,
			rowCount,
			"s3://easysubway-datapack-sources/kric-station-elevator/%s.json".formatted(snapshotId),
			true
		);
	}

	private DataSourceSnapshot lockedSnapshot(
		String snapshotId,
		String rawSha256,
		int rowCount,
		String rawObjectUri,
		boolean credentialRedacted
	) {
		return new DataSourceSnapshot(
			snapshotId,
			"kric-station-elevator",
			"국가철도공단",
			LocalDateTime.of(2026, 6, 29, 3, 0, 0, 123456789),
			LocalDateTime.of(2026, 6, 28, 0, 0, 0, 987654321),
			rowCount,
			rawSha256,
			rawObjectUri,
			"c".repeat(64),
			"d".repeat(64),
			"LOCKED",
			"PASS",
			"PASS",
			"SUCCESS",
			true,
			credentialRedacted,
			null,
			"initial snapshot",
			LocalDateTime.of(2026, 7, 6, 3, 0, 0, 555555555),
			LocalDateTime.of(2026, 9, 29, 3, 0, 0, 555555555)
		);
	}
}
