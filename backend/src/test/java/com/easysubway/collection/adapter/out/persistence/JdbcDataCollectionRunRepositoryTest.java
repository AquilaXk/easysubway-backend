package com.easysubway.collection.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.collection.domain.DataCollectionRun;
import com.easysubway.collection.domain.DataCollectionRunStep;
import com.easysubway.collection.domain.DataCollectionSource;
import com.easysubway.collection.domain.DataCollectionStepStatus;
import com.easysubway.collection.domain.DataCollectionStatus;
import java.time.LocalDateTime;
import java.util.List;
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
		jdbcTemplate.execute("DROP TABLE IF EXISTS data_collection_run_steps");
		jdbcTemplate.execute("""
			CREATE TABLE data_collection_runs (
				run_id VARCHAR(80) PRIMARY KEY,
				source VARCHAR(40) NOT NULL,
				status VARCHAR(20) NOT NULL,
				requested_by VARCHAR(120) NOT NULL,
				started_at TIMESTAMP NOT NULL,
				completed_at TIMESTAMP NULL,
				collected_count INTEGER NOT NULL,
				failure_message VARCHAR(1000) NULL,
				retryable BOOLEAN NOT NULL,
				operator_action VARCHAR(500) NOT NULL
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE data_collection_run_steps (
				run_id VARCHAR(80) NOT NULL,
				step_order INTEGER NOT NULL,
				step_name VARCHAR(40) NOT NULL,
				status VARCHAR(30) NOT NULL,
				input_source VARCHAR(1000),
				artifact_reference VARCHAR(1000),
				checksum VARCHAR(64),
				record_count INTEGER NOT NULL,
				failure_message VARCHAR(1000),
				PRIMARY KEY (run_id, step_order)
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
			null,
			false,
			"수집이 완료되었습니다. 최근 데이터 품질 화면에서 반영 결과를 확인하세요.",
			List.of(new DataCollectionRunStep(
				"FETCH",
				DataCollectionStepStatus.COMPLETED,
				"fixture://source",
				"fixture://artifact",
				"0".repeat(64),
				13,
				null
			))
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
	@DisplayName("최신 완료 수집 실행 기록은 실패 기록보다 완료 시간이 늦은 완료 기록을 반환한다")
	void loadLatestCompletedRunReturnsLatestCompletedRunByCompletedAt() {
		repository.saveRun(completedRun("collection-old-completed", LocalDateTime.of(2026, 6, 16, 9, 0)));
		repository.saveRun(completedRun("collection-new-completed", LocalDateTime.of(2026, 6, 16, 10, 0)));
		repository.saveRun(failedRun("collection-newer-failed", LocalDateTime.of(2026, 6, 16, 11, 0)));

		var latestCompletedRun = repository.loadLatestCompletedRun(DataCollectionSource.TRANSIT_MASTER);

		assertThat(latestCompletedRun)
			.map(DataCollectionRun::runId)
			.contains("collection-new-completed");
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
			null,
			false,
			"수집이 완료되었습니다. 최근 데이터 품질 화면에서 반영 결과를 확인하세요."
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
			"loader down",
			true,
			"일시 오류일 수 있습니다. 실패 사유를 확인한 뒤 같은 수집 대상을 다시 실행하세요."
		);
	}
}
