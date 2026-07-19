package com.easysubway.collection.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.easysubway.collection.adapter.out.persistence.InMemoryDataCollectionRunRepository;
import com.easysubway.collection.application.port.in.RunDataCollectionCommand;
import com.easysubway.collection.domain.DataCollectionRun;
import com.easysubway.collection.domain.DataCollectionRunStep;
import com.easysubway.collection.domain.DataCollectionSource;
import com.easysubway.collection.domain.DataCollectionStepStatus;
import com.easysubway.collection.domain.DataCollectionStatus;
import com.easysubway.collection.domain.InvalidDataCollectionException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.launch.JobLauncher;

@DisplayName("데이터 수집 서비스")
class DataCollectionServiceTest {

	@Test
	@DisplayName("같은 source 실행이 RUNNING이면 새 배치를 launch하지 않는다")
	void runningSourceRejectsSecondLaunch() {
		var repository = new InMemoryDataCollectionRunRepository();
		repository.saveRun(runningRun("collection-running"));
		var launchCount = new AtomicInteger();
		JobLauncher launcher = (job, parameters) -> {
			launchCount.incrementAndGet();
			return mock(JobExecution.class);
		};
		var service = new DataCollectionService(
			repository,
			repository,
			() -> "collection-next",
			launcher,
			mock(Job.class)
		);

		assertThatThrownBy(() -> service.runCollection(
			new RunDataCollectionCommand(DataCollectionSource.TRANSIT_MASTER, "admin-user")
		))
			.isInstanceOf(InvalidDataCollectionException.class)
			.hasMessage("같은 수집 대상이 이미 실행 중입니다.");
		assertThat(launchCount).hasValue(0);
	}

	@Test
	@DisplayName("24시간이 지난 고아 RUNNING claim은 실패로 재조정하고 새 실행을 허용한다")
	void orphanedRunningClaimIsReconciledBeforeNewLaunch() {
		var repository = new InMemoryDataCollectionRunRepository();
		DataCollectionRun orphan = new DataCollectionRun(
			"collection-orphaned",
			DataCollectionSource.TRANSIT_MASTER,
			DataCollectionStatus.RUNNING,
			"stopped-instance",
			LocalDateTime.now().minusHours(25),
			null,
			0,
			null,
			false,
			"수집 실행 중입니다."
		);
		repository.saveRun(orphan);
		JobLauncher launcher = (job, parameters) -> {
			String runId = parameters.getString("runId");
			repository.saveRun(new DataCollectionRun(
				runId,
				DataCollectionSource.TRANSIT_MASTER,
				DataCollectionStatus.COMPLETED,
				"admin-user",
				LocalDateTime.now(),
				LocalDateTime.now(),
				1,
				null,
				false,
				"수집 완료"
			));
			JobExecution execution = mock(JobExecution.class);
			org.mockito.Mockito.when(execution.getStatus()).thenReturn(BatchStatus.COMPLETED);
			return execution;
		};
		var service = new DataCollectionService(
			repository,
			repository,
			() -> "collection-recovered",
			launcher,
			mock(Job.class)
		);

		DataCollectionRun recovered = service.runCollection(
			new RunDataCollectionCommand(DataCollectionSource.TRANSIT_MASTER, "admin-user")
		);

		assertThat(recovered.status()).isEqualTo(DataCollectionStatus.COMPLETED);
		assertThat(repository.loadRun(orphan.runId())).get()
			.extracting(DataCollectionRun::status, DataCollectionRun::failureMessage)
			.containsExactly(
				DataCollectionStatus.FAILED,
				"배치 실행 소유권이 만료되어 고아 실행으로 정리되었습니다."
			);
	}

	@Test
	@DisplayName("배치 launch 실패는 사전 저장한 같은 실행을 FAILED로 갱신한다")
	void launchFailureMarksClaimedRunAsFailed() {
		var repository = new InMemoryDataCollectionRunRepository();
		JobLauncher launcher = (job, parameters) -> {
			throw new JobParametersInvalidException("launch down");
		};
		var service = new DataCollectionService(
			repository,
			repository,
			() -> "collection-failed",
			launcher,
			mock(Job.class)
		);

		assertThatThrownBy(() -> service.runCollection(
			new RunDataCollectionCommand(DataCollectionSource.TRANSIT_MASTER, "admin-user")
		))
			.isInstanceOf(InvalidDataCollectionException.class)
			.hasMessage("데이터 수집 배치를 실행하지 못했습니다.");

		assertThat(repository.loadRun("collection-failed")).get()
			.extracting(DataCollectionRun::status, DataCollectionRun::failureMessage)
			.satisfies(values -> {
				assertThat(values.get(0)).isEqualTo(DataCollectionStatus.FAILED);
				assertThat(values.get(1).toString())
					.contains("JobParametersInvalidException", "보호 정책")
					.doesNotContain("launch down");
			});
	}

	@Test
	@DisplayName("배치 launch의 unchecked 실패도 claim을 해제해 같은 source를 다시 실행할 수 있다")
	void uncheckedLaunchFailureMarksClaimAsFailedAndAllowsRerun() {
		var repository = new InMemoryDataCollectionRunRepository();
		var idSequence = new AtomicInteger();
		var launchCount = new AtomicInteger();
		JobLauncher launcher = (job, parameters) -> {
			launchCount.incrementAndGet();
			throw new IllegalStateException("executor rejected secret-shaped payload");
		};
		var service = new DataCollectionService(
			repository,
			repository,
			() -> "collection-runtime-failed-" + idSequence.incrementAndGet(),
			launcher,
			mock(Job.class)
		);

		for (int attempt = 0; attempt < 2; attempt++) {
			assertThatThrownBy(() -> service.runCollection(
				new RunDataCollectionCommand(DataCollectionSource.TRANSIT_MASTER, "admin-user")
			))
				.isInstanceOf(InvalidDataCollectionException.class)
				.hasMessage("데이터 수집 배치를 실행하지 못했습니다.")
				.hasCauseInstanceOf(IllegalStateException.class);
			assertThat(repository.loadRunningRun(DataCollectionSource.TRANSIT_MASTER)).isEmpty();
		}

		assertThat(launchCount).hasValue(2);
		assertThat(repository.loadRun("collection-runtime-failed-2")).get()
			.extracting(DataCollectionRun::status, DataCollectionRun::failureMessage)
			.satisfies(values -> {
				assertThat(values.get(0)).isEqualTo(DataCollectionStatus.FAILED);
				assertThat(values.get(1).toString())
					.contains("IllegalStateException", "보호 정책")
					.doesNotContain("executor rejected secret-shaped payload");
			});
	}

	@Test
	@DisplayName("배치가 FAILED로 반환되면 claim을 해제하고 같은 source를 다시 실행할 수 있다")
	void failedJobExecutionMarksClaimAsFailedAndAllowsRerun() {
		var repository = new InMemoryDataCollectionRunRepository();
		var idSequence = new AtomicInteger();
		var launchCount = new AtomicInteger();
		JobLauncher launcher = (job, parameters) -> {
			launchCount.incrementAndGet();
			JobExecution execution = mock(JobExecution.class);
			org.mockito.Mockito.when(execution.getStatus()).thenReturn(BatchStatus.FAILED);
			org.mockito.Mockito.when(execution.getAllFailureExceptions())
				.thenReturn(java.util.List.of(new IllegalStateException("loader down")));
			return execution;
		};
		var service = new DataCollectionService(
			repository,
			repository,
			() -> "collection-failed-" + idSequence.incrementAndGet(),
			launcher,
			mock(Job.class)
		);

		assertThatThrownBy(() -> service.runCollection(
			new RunDataCollectionCommand(DataCollectionSource.TRANSIT_MASTER, "admin-user")
		))
			.isInstanceOf(InvalidDataCollectionException.class)
			.hasMessage("데이터 수집 배치를 실행하지 못했습니다.");
		assertThat(repository.loadRun("collection-failed-1")).get()
			.extracting(DataCollectionRun::status, DataCollectionRun::failureMessage)
			.satisfies(values -> {
				assertThat(values.get(0)).isEqualTo(DataCollectionStatus.FAILED);
				assertThat(values.get(1).toString())
					.contains("IllegalStateException", "보호 정책")
					.doesNotContain("loader down");
			});
		assertThat(repository.loadRunningRun(DataCollectionSource.TRANSIT_MASTER)).isEmpty();

		assertThatThrownBy(() -> service.runCollection(
			new RunDataCollectionCommand(DataCollectionSource.TRANSIT_MASTER, "admin-user")
		))
			.isInstanceOf(InvalidDataCollectionException.class);
		assertThat(launchCount).hasValue(2);
	}

	@Test
	@DisplayName("tasklet이 이미 저장한 terminal 실패 단계는 FAILED JobExecution fallback이 덮지 않는다")
	void failedJobExecutionPreservesRecordedFailureSteps() {
		var repository = new InMemoryDataCollectionRunRepository();
		JobLauncher launcher = (job, parameters) -> {
			String runId = parameters.getString("runId");
			repository.saveRun(new DataCollectionRun(
				runId,
				DataCollectionSource.TRANSIT_MASTER,
				DataCollectionStatus.FAILED,
				"admin-user",
				LocalDateTime.of(2026, 7, 18, 12, 0),
				LocalDateTime.of(2026, 7, 18, 12, 1),
				0,
				"IllegalStateException: 상세 오류는 보호 정책에 따라 생략되었습니다.",
				true,
				"실패 단계를 확인하세요.",
				List.of(new DataCollectionRunStep(
					"FETCH",
					DataCollectionStepStatus.FAILED,
					null,
					null,
					null,
					0,
					"IllegalStateException: 상세 오류는 보호 정책에 따라 생략되었습니다."
				))
			));
			JobExecution execution = mock(JobExecution.class);
			org.mockito.Mockito.when(execution.getStatus()).thenReturn(BatchStatus.FAILED);
			org.mockito.Mockito.when(execution.getAllFailureExceptions())
				.thenReturn(List.of(new IllegalStateException("raw provider failure")));
			return execution;
		};
		var service = new DataCollectionService(
			repository,
			repository,
			() -> "collection-recorded-failed",
			launcher,
			mock(Job.class)
		);

		assertThatThrownBy(() -> service.runCollection(
			new RunDataCollectionCommand(DataCollectionSource.TRANSIT_MASTER, "admin-user")
		))
			.isInstanceOf(InvalidDataCollectionException.class)
			.hasMessage("데이터 수집 배치를 실행하지 못했습니다.");

		assertThat(repository.loadRun("collection-recorded-failed")).get()
			.extracting(DataCollectionRun::steps)
			.satisfies(steps -> assertThat(steps)
				.singleElement()
				.extracting(
					DataCollectionRunStep::name,
					DataCollectionRunStep::status,
					DataCollectionRunStep::failureMessage
				)
				.containsExactly(
					"FETCH",
					DataCollectionStepStatus.FAILED,
					"IllegalStateException: 상세 오류는 보호 정책에 따라 생략되었습니다."
				));
	}

	@Test
	@DisplayName("JobExecution 완료 뒤 recorder가 완료 상태를 저장하지 않으면 claim을 FAILED로 해제한다")
	void completedJobExecutionRequiresRecordedCompletedRun() {
		var repository = new InMemoryDataCollectionRunRepository();
		JobLauncher launcher = (job, parameters) -> {
			JobExecution execution = mock(JobExecution.class);
			org.mockito.Mockito.when(execution.getStatus()).thenReturn(BatchStatus.COMPLETED);
			return execution;
		};
		var service = new DataCollectionService(
			repository,
			repository,
			() -> "collection-unrecorded-completed",
			launcher,
			mock(Job.class)
		);

		assertThatThrownBy(() -> service.runCollection(
			new RunDataCollectionCommand(DataCollectionSource.TRANSIT_MASTER, "admin-user")
		))
			.isInstanceOf(InvalidDataCollectionException.class)
			.hasMessage("데이터 수집 실행 기록을 완료 상태로 확정하지 못했습니다.");

		assertThat(repository.loadRun("collection-unrecorded-completed")).get()
			.satisfies(run -> {
				assertThat(run.status()).isEqualTo(DataCollectionStatus.FAILED);
				assertThat(run.failureMessage()).contains("보호 정책");
			});
		assertThat(repository.loadRunningRun(DataCollectionSource.TRANSIT_MASTER)).isEmpty();
	}

	@Test
	@DisplayName("recorder 완료 뒤 JobExecution 실패는 단계 이력을 보존한 FAILED로 정합화한다")
	void failedJobExecutionReconcilesRecordedCompletedRun() {
		var repository = new InMemoryDataCollectionRunRepository();
		JobLauncher launcher = (job, parameters) -> {
			String runId = parameters.getString("runId");
			repository.saveRun(new DataCollectionRun(
				runId,
				DataCollectionSource.TRANSIT_MASTER,
				DataCollectionStatus.COMPLETED,
				"admin-user",
				LocalDateTime.of(2026, 7, 18, 12, 0),
				LocalDateTime.of(2026, 7, 18, 12, 1),
				7,
				null,
				false,
				"수집이 완료되었습니다.",
				List.of(new DataCollectionRunStep(
					"FETCH",
					DataCollectionStepStatus.COMPLETED,
					null,
					null,
					null,
					7,
					null
				))
			));
			JobExecution execution = mock(JobExecution.class);
			org.mockito.Mockito.when(execution.getStatus()).thenReturn(BatchStatus.FAILED);
			org.mockito.Mockito.when(execution.getAllFailureExceptions())
				.thenReturn(List.of(new IllegalStateException("raw listener failure")));
			return execution;
		};
		var service = new DataCollectionService(
			repository,
			repository,
			() -> "collection-recorded-completed",
			launcher,
			mock(Job.class)
		);

		assertThatThrownBy(() -> service.runCollection(
			new RunDataCollectionCommand(DataCollectionSource.TRANSIT_MASTER, "admin-user")
		))
			.isInstanceOf(InvalidDataCollectionException.class)
			.hasMessage("데이터 수집 배치를 실행하지 못했습니다.");

		assertThat(repository.loadRun("collection-recorded-completed")).get()
			.satisfies(run -> {
				assertThat(run.status()).isEqualTo(DataCollectionStatus.FAILED);
				assertThat(run.collectedCount()).isEqualTo(7);
				assertThat(run.failureMessage())
					.contains("IllegalStateException", "보호 정책")
					.doesNotContain("raw listener failure");
				assertThat(run.steps()).singleElement()
					.extracting(DataCollectionRunStep::name, DataCollectionRunStep::status)
					.containsExactly("FETCH", DataCollectionStepStatus.COMPLETED);
			});
	}

	private static DataCollectionRun runningRun(String runId) {
		return new DataCollectionRun(
			runId,
			DataCollectionSource.TRANSIT_MASTER,
			DataCollectionStatus.RUNNING,
			"admin-user",
			LocalDateTime.now().minusMinutes(1),
			null,
			0,
			null,
			false,
			"수집 실행 중입니다."
		);
	}
}
