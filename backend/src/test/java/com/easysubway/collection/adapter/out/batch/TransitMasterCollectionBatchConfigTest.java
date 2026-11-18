package com.easysubway.collection.adapter.out.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.collection.adapter.out.persistence.InMemoryDataCollectionRunRepository;
import com.easysubway.collection.domain.DataCollectionSource;
import com.easysubway.collection.domain.DataCollectionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@DisplayName("도시철도 마스터 데이터 수집 배치")
class TransitMasterCollectionBatchConfigTest {

	@Autowired
	private JobLauncher jobLauncher;

	@Autowired
	@Qualifier(TransitMasterCollectionBatchConfig.JOB_NAME)
	private Job job;

	@Autowired
	private InMemoryDataCollectionRunRepository repository;

	@Test
	@DisplayName("배치 Job은 요청자 파라미터로 실행 기록을 남긴다")
	void transitMasterCollectionJobStoresRunWithRequesterParameter() throws Exception {
		var parameters = new JobParametersBuilder()
			.addString("runId", "collection-batch-test")
			.addString("requestedBy", "admin-batch")
			.addLong("run.id", System.nanoTime())
			.toJobParameters();

		var execution = jobLauncher.run(job, parameters);

		assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		var run = repository.loadRun("collection-batch-test").orElseThrow();
		assertThat(run.source()).isEqualTo(DataCollectionSource.TRANSIT_MASTER);
		assertThat(run.status()).isEqualTo(DataCollectionStatus.COMPLETED);
		assertThat(run.requestedBy()).isEqualTo("admin-batch");
		assertThat(execution.getStepExecutions())
			.extracting("stepName")
			.containsExactly(TransitMasterCollectionBatchConfig.STEP_NAME);
		assertThat(run.steps())
			.extracting("name")
			.containsExactly("FETCH", "ARCHIVE", "VALIDATE", "PARSE", "DIFF", "STAGE", "PUBLISH", "ACTIVATE");
		var stepExecution = execution.getStepExecutions().iterator().next();
		assertThat(stepExecution.getExecutionContext()
			.getString(TransitMasterCollectionBatchConfig.COLLECTION_SOURCE_VARIABLE))
			.as("bat-core EgovStepVariableListener가 수집 변수를 ExecutionContext에 시딩한다")
			.isEqualTo(TransitMasterCollectionBatchConfig.COLLECTION_SOURCE_VALUE);
	}

	@Test
	@DisplayName("완료된 배치 Job은 같은 파라미터로 중복 실행되지 않는다")
	void transitMasterCollectionJobIsIdempotentForCompletedParameters() throws Exception {
		var parameters = new JobParametersBuilder()
			.addString("runId", "collection-batch-idempotent")
			.addString("requestedBy", "admin-batch")
			.toJobParameters();

		var firstExecution = jobLauncher.run(job, parameters);

		assertThat(firstExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		assertThatThrownBy(() -> jobLauncher.run(job, parameters))
			.isInstanceOf(JobInstanceAlreadyCompleteException.class);
		assertThat(repository.loadRun("collection-batch-idempotent")).isPresent();
	}

	@Test
	@DisplayName("배치 Job은 요청자 파라미터가 없으면 실행 기록을 남기지 않는다")
	void transitMasterCollectionJobRequiresRequesterParameter() throws Exception {
		var parameters = new JobParametersBuilder()
			.addString("runId", "collection-missing-requester")
			.addLong("run.id", System.nanoTime())
			.toJobParameters();

		var execution = jobLauncher.run(job, parameters);

		assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
		assertThat(repository.loadRun("collection-missing-requester")).isEmpty();
	}

	@Test
	@DisplayName("배치 Job은 run ID 파라미터가 없으면 실패한다")
	void transitMasterCollectionJobRequiresRunIdParameter() throws Exception {
		var parameters = new JobParametersBuilder()
			.addString("requestedBy", "admin-batch")
			.addLong("run.id", System.nanoTime())
			.toJobParameters();

		var execution = jobLauncher.run(job, parameters);

		assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
	}

	@Test
	@DisplayName("실패한 배치 Job은 같은 instance에서 새 execution으로 재실행된다")
	void failedTransitMasterCollectionJobRestartsSameInstance() throws Exception {
		var parameters = new JobParametersBuilder()
			.addString("runId", "collection-restart-failed")
			.toJobParameters();

		var firstExecution = jobLauncher.run(job, parameters);
		var restartedExecution = jobLauncher.run(job, parameters);

		assertThat(firstExecution.getStatus()).isEqualTo(BatchStatus.FAILED);
		assertThat(restartedExecution.getStatus()).isEqualTo(BatchStatus.FAILED);
		assertThat(restartedExecution.getJobInstance().getInstanceId())
			.isEqualTo(firstExecution.getJobInstance().getInstanceId());
		assertThat(restartedExecution.getId()).isNotEqualTo(firstExecution.getId());
	}
}
