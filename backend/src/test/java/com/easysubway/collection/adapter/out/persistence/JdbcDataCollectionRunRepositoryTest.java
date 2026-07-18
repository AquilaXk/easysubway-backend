package com.easysubway.collection.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.easysubway.collection.application.port.in.RunDataCollectionCommand;
import com.easysubway.collection.application.service.DataCollectionService;
import com.easysubway.collection.domain.DataCollectionRun;
import com.easysubway.collection.domain.DataCollectionRunStep;
import com.easysubway.collection.domain.DataCollectionSource;
import com.easysubway.collection.domain.DataCollectionStepStatus;
import com.easysubway.collection.domain.DataCollectionStatus;
import com.easysubway.collection.domain.InvalidDataCollectionException;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@DisplayName("JDBC 데이터 수집 실행 기록 저장소")
@SpringJUnitConfig(JdbcDataCollectionRunRepositoryTest.TransactionConfig.class)
class JdbcDataCollectionRunRepositoryTest {

	@Autowired
	private JdbcDataCollectionRunRepository repository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.execute("DROP TABLE IF EXISTS BATCH_JOB_EXECUTION_PARAMS");
		jdbcTemplate.execute("DROP TABLE IF EXISTS BATCH_JOB_EXECUTION");
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
				operator_action VARCHAR(500) NOT NULL,
				active_source VARCHAR(40) NULL
			)
			""");
		jdbcTemplate.execute("""
			CREATE UNIQUE INDEX ux_data_collection_runs_active_source
				ON data_collection_runs (active_source)
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
		jdbcTemplate.execute("""
			CREATE TABLE BATCH_JOB_EXECUTION (
				JOB_EXECUTION_ID BIGINT PRIMARY KEY,
				CREATE_TIME TIMESTAMP NOT NULL,
				START_TIME TIMESTAMP NULL,
				STATUS VARCHAR(10),
				LAST_UPDATED TIMESTAMP NULL
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE BATCH_JOB_EXECUTION_PARAMS (
				JOB_EXECUTION_ID BIGINT NOT NULL,
				PARAMETER_NAME VARCHAR(100) NOT NULL,
				PARAMETER_VALUE VARCHAR(2500)
			)
			""");
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
	@DisplayName("최근 수집 실행 기록은 offset 이후 기록부터 조회한다")
	void loadRecentRunsSupportsOffset() {
		repository.saveRun(completedRun("collection-old", LocalDateTime.of(2026, 6, 16, 9, 0)));
		repository.saveRun(completedRun("collection-new", LocalDateTime.of(2026, 6, 16, 11, 0)));
		repository.saveRun(failedRun("collection-failed", LocalDateTime.of(2026, 6, 16, 10, 0)));

		var recentRuns = repository.loadRecentRuns(1, 1);

		assertThat(recentRuns)
			.extracting(DataCollectionRun::runId)
			.containsExactly("collection-failed");
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

	@Test
	@DisplayName("같은 source의 RUNNING claim은 하나만 저장한다")
	void saveRunRejectsSecondRunningClaimForSameSource() {
		repository.saveRun(runningRun("collection-running"));

		assertThatThrownBy(() -> repository.saveRun(runningRun("collection-next")))
			.isInstanceOf(InvalidDataCollectionException.class)
			.hasMessage("같은 수집 대상이 이미 실행 중입니다.");
	}

	@Test
	@DisplayName("PostgreSQL RUNNING source partial index 충돌을 동시 실행 거부로 분류한다")
	void recognizesPostgresRunningSourceIndexConflict() {
		var exception = new DataIntegrityViolationException(
			"save failed",
			new SQLException("duplicate key violates ux_data_collection_runs_running_source")
		);

		assertThat(JdbcDataCollectionRunRepository.isActiveSourceConflict(exception)).isTrue();
	}

	@Test
	@DisplayName("RUNNING을 terminal 상태로 갱신하면 같은 source를 다시 claim할 수 있다")
	void terminalUpdateReleasesRunningClaim() {
		DataCollectionRun running = runningRun("collection-running");
		repository.saveRun(running);
		repository.saveRun(new DataCollectionRun(
			running.runId(),
			running.source(),
			DataCollectionStatus.COMPLETED,
			running.requestedBy(),
			running.startedAt(),
			running.startedAt().plusMinutes(1),
			1,
			null,
			false,
			"수집 완료"
		));

		repository.saveRun(runningRun("collection-next"));

		assertThat(repository.loadRun("collection-next"))
			.get()
			.extracting(DataCollectionRun::status)
			.isEqualTo(DataCollectionStatus.RUNNING);
	}

	@Test
	@DisplayName("구버전이 남긴 terminal active_source는 RUNNING claim으로 조회하지 않는다")
	void ignoresStaleLegacyActiveSourceOnTerminalRun() {
		DataCollectionRun completed = completedRun(
			"collection-legacy-completed",
			LocalDateTime.of(2026, 7, 18, 11, 0)
		);
		repository.saveRun(completed);
		jdbcTemplate.update(
			"UPDATE data_collection_runs SET active_source = source WHERE run_id = ?",
			completed.runId()
		);

		assertThat(repository.loadRunningRun(DataCollectionSource.TRANSIT_MASTER)).isEmpty();
	}

	@Test
	@DisplayName("stale RUNNING claim은 batch execution이 없으면 원자적으로 FAILED로 재조정한다")
	void reconcilesStaleRunningClaimWithoutBatchExecution() {
		DataCollectionRun orphan = runningRun("collection-orphaned");
		repository.saveRun(orphan);
		LocalDateTime cutoff = orphan.startedAt().plusHours(1);

		boolean reconciled = repository.failOrphanedRunningRun(
			DataCollectionSource.TRANSIT_MASTER,
			cutoff,
			cutoff.plusMinutes(1),
			"배치 실행 소유권이 만료되어 고아 실행으로 정리되었습니다.",
			"이전 실행이 비정상 종료되었습니다. 새 실행 결과를 확인하세요."
		);

		assertThat(reconciled).isTrue();
		assertThat(repository.loadRun(orphan.runId())).get()
			.extracting(DataCollectionRun::status)
			.isEqualTo(DataCollectionStatus.FAILED);
	}

	@Test
	@DisplayName("최근 갱신된 STARTED batch execution의 RUNNING claim은 재조정하지 않는다")
	void preservesRunningClaimWithFreshStartedBatchExecution() {
		DataCollectionRun running = runningRun("collection-live");
		repository.saveRun(running);
		LocalDateTime cutoff = running.startedAt().plusHours(1);
		jdbcTemplate.update("""
			INSERT INTO BATCH_JOB_EXECUTION (
				JOB_EXECUTION_ID, CREATE_TIME, START_TIME, STATUS, LAST_UPDATED
			) VALUES (1, ?, ?, 'STARTED', ?)
			""", running.startedAt(), running.startedAt(), cutoff.plusMinutes(1));
		jdbcTemplate.update("""
			INSERT INTO BATCH_JOB_EXECUTION_PARAMS (
				JOB_EXECUTION_ID, PARAMETER_NAME, PARAMETER_VALUE
			) VALUES (1, 'runId', ?)
			""", running.runId());

		boolean reconciled = repository.failOrphanedRunningRun(
			DataCollectionSource.TRANSIT_MASTER,
			cutoff,
			cutoff.plusMinutes(2),
			"배치 실행 소유권이 만료되어 고아 실행으로 정리되었습니다.",
			"이전 실행이 비정상 종료되었습니다. 새 실행 결과를 확인하세요."
		);

		assertThat(reconciled).isFalse();
		assertThat(repository.loadRunningRun(DataCollectionSource.TRANSIT_MASTER)).contains(running);
	}

	@Test
	@DisplayName("step 저장 실패 시 run row와 active claim과 기존 steps를 함께 rollback한다")
	void saveRunRollsBackEntireAggregateWhenStepInsertFails() {
		DataCollectionRun running = new DataCollectionRun(
			"collection-transaction",
			DataCollectionSource.TRANSIT_MASTER,
			DataCollectionStatus.RUNNING,
			"admin-user",
			LocalDateTime.of(2026, 7, 18, 12, 0),
			null,
			0,
			null,
			false,
			"수집 실행 중입니다.",
			List.of(new DataCollectionRunStep(
				"CLAIM",
				DataCollectionStepStatus.COMPLETED,
				null,
				null,
				null,
				0,
				null
			))
		);
		repository.saveRun(running);
		DataCollectionRun invalidTerminal = new DataCollectionRun(
			running.runId(),
			running.source(),
			DataCollectionStatus.FAILED,
			running.requestedBy(),
			running.startedAt(),
			running.startedAt().plusMinutes(1),
			0,
			"loader down",
			true,
			"재실행하세요.",
			List.of(new DataCollectionRunStep(
				"x".repeat(41),
				DataCollectionStepStatus.FAILED,
				null,
				null,
				null,
				0,
				"loader down"
			))
		);

		assertThatThrownBy(() -> repository.saveRun(invalidTerminal))
			.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

		assertThat(repository.loadRunningRun(DataCollectionSource.TRANSIT_MASTER))
			.contains(running);
		assertThat(repository.loadRun(running.runId()))
			.get()
			.extracting(DataCollectionRun::steps)
			.isEqualTo(running.steps());
	}

	@Test
	@DisplayName("길고 민감한 FAILED JobExecution은 안전한 사유로 JDBC claim을 해제해 재실행을 허용한다")
	void sensitiveLongFailedJobExecutionStoresSafeDetailAndAllowsRerun() {
		var idSequence = new AtomicInteger();
		var launchCount = new AtomicInteger();
		String rawFailure = "api key: sk-live-example password value hunter2 client secret is example "
			+ "jdbc:postgresql://admin:secret-value@db.example/prod "
			+ "upstream returned {\"customer\":\"raw provider payload\"} "
			+ "x".repeat(1_100);
		assertThat(rawFailure.length()).isGreaterThan(1_000);
		JobLauncher launcher = (job, parameters) -> {
			launchCount.incrementAndGet();
			JobExecution execution = mock(JobExecution.class);
			when(execution.getStatus()).thenReturn(BatchStatus.FAILED);
			when(execution.getAllFailureExceptions())
				.thenReturn(List.of(new IllegalStateException(rawFailure)));
			return execution;
		};
		var service = new DataCollectionService(
			repository,
			repository,
			() -> "collection-jdbc-failed-" + idSequence.incrementAndGet(),
			launcher,
			mock(Job.class)
		);

		for (int attempt = 0; attempt < 2; attempt++) {
			assertThatThrownBy(() -> service.runCollection(
				new RunDataCollectionCommand(DataCollectionSource.TRANSIT_MASTER, "admin-user")
			))
				.isInstanceOf(InvalidDataCollectionException.class)
				.hasMessage("데이터 수집 배치를 실행하지 못했습니다.");
			assertThat(repository.loadRunningRun(DataCollectionSource.TRANSIT_MASTER)).isEmpty();
		}

		assertThat(launchCount).hasValue(2);
		DataCollectionRun failedRun = repository.loadRun("collection-jdbc-failed-2").orElseThrow();
		assertThat(failedRun.status()).isEqualTo(DataCollectionStatus.FAILED);
		assertThat(failedRun.failureMessage())
			.hasSizeLessThanOrEqualTo(500)
			.contains("보호 정책")
			.doesNotContain(
				"sk-live-example",
				"hunter2",
				"client secret is",
				"admin:secret-value",
				"raw provider payload"
			);
	}

	@Test
	@DisplayName("active source 충돌이 아닌 run row integrity 오류는 원래 예외를 보존한다")
	void saveRunPreservesNonClaimIntegrityViolation() {
		DataCollectionRun running = runningRun("collection-integrity");
		repository.saveRun(running);
		DataCollectionRun invalidTerminal = new DataCollectionRun(
			running.runId(),
			running.source(),
			DataCollectionStatus.FAILED,
			running.requestedBy(),
			running.startedAt(),
			running.startedAt().plusMinutes(1),
			0,
			"x".repeat(1_001),
			true,
			"재실행하세요."
		);

		assertThatThrownBy(() -> repository.saveRun(invalidTerminal))
			.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
			.isNotInstanceOf(InvalidDataCollectionException.class);
		assertThat(repository.loadRunningRun(DataCollectionSource.TRANSIT_MASTER)).contains(running);
	}

	@Configuration
	@EnableTransactionManagement(proxyTargetClass = true)
	static class TransactionConfig {

		@Bean
		DriverManagerDataSource dataSource() {
			return new DriverManagerDataSource(
				"jdbc:h2:mem:collection-runs;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
				"sa",
				""
			);
		}

		@Bean
		JdbcTemplate jdbcTemplate(DriverManagerDataSource dataSource) {
			return new JdbcTemplate(dataSource);
		}

		@Bean
		JdbcDataCollectionRunRepository repository(DriverManagerDataSource dataSource) {
			return new JdbcDataCollectionRunRepository(dataSource);
		}

		@Bean
		PlatformTransactionManager transactionManager(DriverManagerDataSource dataSource) {
			return new DataSourceTransactionManager(dataSource);
		}
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

	private DataCollectionRun runningRun(String runId) {
		return new DataCollectionRun(
			runId,
			DataCollectionSource.TRANSIT_MASTER,
			DataCollectionStatus.RUNNING,
			"admin-user",
			LocalDateTime.of(2026, 7, 18, 12, 0),
			null,
			0,
			null,
			false,
			"수집 실행 중입니다."
		);
	}
}
