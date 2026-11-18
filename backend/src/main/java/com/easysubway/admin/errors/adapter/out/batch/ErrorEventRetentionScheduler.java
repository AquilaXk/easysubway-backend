package com.easysubway.admin.errors.adapter.out.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 오류 이벤트 보존 잡을 일 1회 기동한다. */
@Component
class ErrorEventRetentionScheduler {

	private static final Logger log = LoggerFactory.getLogger(ErrorEventRetentionScheduler.class);

	private final JobLauncher jobLauncher;
	private final Job errorEventRetentionJob;

	ErrorEventRetentionScheduler(
		JobLauncher jobLauncher,
		@Qualifier(ErrorEventRetentionBatchConfig.JOB_NAME) Job errorEventRetentionJob
	) {
		this.jobLauncher = jobLauncher;
		this.errorEventRetentionJob = errorEventRetentionJob;
	}

	@Scheduled(
		cron = "${easysubway.error-events.retention.cron:0 30 3 * * *}",
		zone = "UTC"
	)
	void launchRetentionJob() {
		try {
			jobLauncher.run(
				errorEventRetentionJob,
				new JobParametersBuilder()
					.addLong("run.id", System.currentTimeMillis())
					.toJobParameters()
			);
		}
		catch (Exception failure) {
			log.warn("error event retention job launch failed", failure);
		}
	}
}
