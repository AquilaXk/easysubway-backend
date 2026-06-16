package com.easysubway.collection.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.collection.domain.DataCollectionRun;
import com.easysubway.collection.domain.DataCollectionSource;
import com.easysubway.collection.domain.DataCollectionStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@DisplayName("JDBC 데이터 수집 실행 기록 저장소")
class JdbcDataCollectionRunRepositoryTest {

	private JdbcDataCollectionRunRepository repository;

	@BeforeEach
	void setUp() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:collection-runs;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		var jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.execute("DROP TABLE IF EXISTS data_collection_runs");
		jdbcTemplate.execute("""
			CREATE TABLE data_collection_runs (
				run_id VARCHAR(80) PRIMARY KEY,
				source VARCHAR(40) NOT NULL,
				status VARCHAR(20) NOT NULL,
				requested_by VARCHAR(120) NOT NULL,
				started_at TIMESTAMP NOT NULL,
				completed_at TIMESTAMP NULL,
				collected_count INTEGER NOT NULL,
				failure_message VARCHAR(1000) NULL
			)
			""");
		repository = new JdbcDataCollectionRunRepository(jdbcTemplate);
	}

	@Test
	@DisplayName("완료된 수집 실행 기록을 저장하고 식별자로 조회한다")
	void saveRunAndLoadRunById() {
		var run = new DataCollectionRun(
			"collection-completed",
			DataCollectionSource.TRANSIT_MASTER,
			DataCollectionStatus.COMPLETED,
			"admin-user",
			LocalDateTime.of(2026, 6, 16, 10, 0),
			LocalDateTime.of(2026, 6, 16, 10, 1),
			13,
			null
		);

		repository.saveRun(run);

		assertThat(repository.loadRun("collection-completed")).contains(run);
	}

	@Test
	@DisplayName("최근 수집 실행 기록은 시작 시간이 늦은 순서와 제한 개수를 지킨다")
	void loadRecentRunsReturnsLatestRunsFirstWithLimit() {
		repository.saveRun(completedRun("collection-old", LocalDateTime.of(2026, 6, 16, 9, 0)));
		repository.saveRun(completedRun("collection-new", LocalDateTime.of(2026, 6, 16, 11, 0)));
		repository.saveRun(failedRun("collection-failed", LocalDateTime.of(2026, 6, 16, 10, 0)));

		var recentRuns = repository.loadRecentRuns(2);

		assertThat(recentRuns)
			.extracting(DataCollectionRun::runId)
			.containsExactly("collection-new", "collection-failed");
	}

	@Test
	@DisplayName("실패한 수집 실행 기록도 실패 사유와 함께 저장한다")
	void saveFailedRunWithFailureMessage() {
		var run = failedRun("collection-failed", LocalDateTime.of(2026, 6, 16, 10, 0));

		repository.saveRun(run);

		assertThat(repository.loadRun("collection-failed")).contains(run);
	}

	private DataCollectionRun completedRun(String runId, LocalDateTime startedAt) {
		return new DataCollectionRun(
			runId,
			DataCollectionSource.TRANSIT_MASTER,
			DataCollectionStatus.COMPLETED,
			"admin-user",
			startedAt,
			startedAt.plusMinutes(1),
			13,
			null
		);
	}

	private DataCollectionRun failedRun(String runId, LocalDateTime startedAt) {
		return new DataCollectionRun(
			runId,
			DataCollectionSource.TRANSIT_MASTER,
			DataCollectionStatus.FAILED,
			"admin-user",
			startedAt,
			startedAt.plusMinutes(1),
			0,
			"loader down"
		);
	}
}
