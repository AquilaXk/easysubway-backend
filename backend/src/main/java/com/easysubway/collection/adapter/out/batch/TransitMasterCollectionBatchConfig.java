package com.easysubway.collection.adapter.out.batch;

import com.easysubway.collection.application.service.DataCollectionRunRecorder;
import com.easysubway.collection.domain.InvalidDataCollectionException;
import java.util.Properties;
import java.util.UUID;
import org.egovframe.rte.bat.support.EgovJobVariableListener;
import org.egovframe.rte.bat.support.EgovStepVariableListener;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 도시철도 마스터 수집 배치의 control-plane 구성.
 *
 * <p>eGovFrame bat-core의 배치 변수 리스너(job/step)를 채택해 수집 실행 변수를
 * {@code ExecutionContext}에 표준 시딩하고 배치 생명주기 로깅을 얻는다. Spring Batch
 * {@code JobRepository}·restart·멱등성 시맨틱과 job/step 이름은 그대로 유지한다.
 * eGov batch 타입은 이 어댑터 패키지 밖으로 새지 않는다(control-plane 한정).
 */
@Configuration
class TransitMasterCollectionBatchConfig {

	static final String JOB_NAME = "transitMasterCollectionJob";
	static final String STEP_NAME = "recordTransitMasterCollectionStep";
	static final String COLLECTION_SOURCE_VARIABLE = "collection.source";
	static final String COLLECTION_SOURCE_VALUE = "TRANSIT_MASTER";

	@Bean
	EgovJobVariableListener transitMasterCollectionJobVariableListener() {
		var listener = new EgovJobVariableListener();
		listener.setPros(collectionBatchVariables());
		return listener;
	}

	@Bean
	EgovStepVariableListener transitMasterCollectionStepVariableListener() {
		var listener = new EgovStepVariableListener();
		listener.setPros(collectionBatchVariables());
		return listener;
	}

	@Bean
	Job transitMasterCollectionJob(
		JobRepository jobRepository,
		Step recordTransitMasterCollectionStep,
		EgovJobVariableListener transitMasterCollectionJobVariableListener
	) {
		return new JobBuilder(JOB_NAME, jobRepository)
			.listener(transitMasterCollectionJobVariableListener)
			.start(recordTransitMasterCollectionStep)
			.build();
	}

	@Bean
	Step recordTransitMasterCollectionStep(
		JobRepository jobRepository,
		PlatformTransactionManager transactionManager,
		DataCollectionRunRecorder dataCollectionRunRecorder,
		EgovStepVariableListener transitMasterCollectionStepVariableListener
	) {
		return new StepBuilder(STEP_NAME, jobRepository)
			.listener(transitMasterCollectionStepVariableListener)
			.tasklet((contribution, chunkContext) -> {
				String runId = (String) chunkContext.getStepContext()
					.getJobParameters()
					.getOrDefault("runId", "collection-" + UUID.randomUUID());
				Object requestedByParameter = chunkContext.getStepContext()
					.getJobParameters()
					.get("requestedBy");
				if (!(requestedByParameter instanceof String requestedBy) || requestedBy.isBlank()) {
					throw new InvalidDataCollectionException("요청자 식별자가 필요합니다.");
				}
				dataCollectionRunRecorder.recordTransitMasterRun(runId, requestedBy);
				return RepeatStatus.FINISHED;
			}, transactionManager)
			.build();
	}

	private static Properties collectionBatchVariables() {
		var properties = new Properties();
		properties.setProperty(COLLECTION_SOURCE_VARIABLE, COLLECTION_SOURCE_VALUE);
		return properties;
	}
}
