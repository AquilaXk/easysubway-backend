package com.easysubway.datapack.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.datapack.domain.DataSourceSnapshot;
import java.time.LocalDateTime;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@DisplayName("PostgreSQL 데이터팩 source snapshot 저장소")
class JdbcDataSourceSnapshotRepositoryContainerTest {

	@Container
	private static final PostgreSQLContainer<?> POSTGRES =
		new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

	@Test
	@DisplayName("후속 snapshot의 structured diff를 JSONB로 저장하고 재생한다")
	void savesStructuredDiffAsPostgresqlJsonb() {
		var dataSource = new DriverManagerDataSource(
			POSTGRES.getJdbcUrl(),
			POSTGRES.getUsername(),
			POSTGRES.getPassword()
		);
		Flyway.configure()
			.configuration(java.util.Map.of("flyway.postgresql.transactional.lock", "false"))
			.dataSource(dataSource)
			.locations("classpath:db/migration/postgresql")
			.load()
			.migrate();
		var repository = new JdbcDataSourceSnapshotRepository(dataSource);
		var root = snapshot("snapshot-a-1", null, null, null, 1);
		var child = snapshot(
			"snapshot-a-2",
			root.snapshotId(),
			"CHANGED",
			"{\"status\":\"CHANGED\",\"rowDelta\":1}",
			2
		);

		repository.saveSnapshot(root);
		repository.saveSnapshot(child);
		repository.saveSnapshot(child);

		assertThat(repository.loadSnapshot(child.snapshotId())).contains(child);
	}

	private DataSourceSnapshot snapshot(
		String snapshotId,
		String previousSnapshotId,
		String diffSummary,
		String diffSummaryJson,
		int day
	) {
		return new DataSourceSnapshot(
			snapshotId,
			"source-a",
			"provider-a",
			LocalDateTime.of(2026, 7, day, 0, 0),
			LocalDateTime.of(2026, 7, day, 0, 0),
			null,
			null,
			day,
			day,
			(day == 1 ? "a" : "d").repeat(64),
			"s3://bucket/" + snapshotId + ".json",
			"b".repeat(64),
			"c".repeat(64),
			"LOCKED",
			"PASS",
			"PASS",
			"SUCCESS",
			true,
			true,
			previousSnapshotId,
			diffSummary,
			diffSummaryJson,
			LocalDateTime.of(2026, 8, day, 0, 0),
			LocalDateTime.of(2026, 10, day, 0, 0),
			"2026-07-15",
			"e".repeat(64)
		);
	}
}
