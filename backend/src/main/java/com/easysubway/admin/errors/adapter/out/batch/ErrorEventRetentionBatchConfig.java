package com.easysubway.admin.errors.adapter.out.batch;

import com.easysubway.admin.errors.application.port.out.ErrorEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** 오류 이벤트 90일 보존 삭제 Spring Batch Job. */
@Configuration
class ErrorEventRetentionBatchConfig {

	static final String JOB_NAME = "errorEventRetentionJob";
	static final String STEP_NAME = "purgeErrorEventsOlderThanRetentionStep";
	static final int RETENTION_DAYS = 90;

	private static final Logger log = LoggerFactory.getLogger(ErrorEventRetentionBatchConfig.class);

	@Bean
	Job errorEventRetentionJob(JobRepository jobRepository, Step purgeErrorEventsOlderThanRetentionStep) {
		return new JobBuilder(JOB_NAME, jobRepository)
			.start(purgeErrorEventsOlderThanRetentionStep)
			.build();
	}

	@Bean
	Step purgeErrorEventsOlderThanRetentionStep(
		JobRepository jobRepository,
		PlatformTransactionManager transactionManager,
		ErrorEventRepository errorEventRepository
	) {
		return new StepBuilder(STEP_NAME, jobRepository)
			.tasklet((contribution, chunkContext) -> {
				Instant cutoff = Clock.systemUTC().instant().minus(RETENTION_DAYS, ChronoUnit.DAYS);
				int deleted = errorEventRepository.deleteOlderThan(cutoff);
				log.info("error event retention purge completed deletedRows={} cutoff={}", deleted, cutoff);
				contribution.incrementWriteCount(deleted);
				return RepeatStatus.FINISHED;
			}, transactionManager)
			.build();
	}
}
