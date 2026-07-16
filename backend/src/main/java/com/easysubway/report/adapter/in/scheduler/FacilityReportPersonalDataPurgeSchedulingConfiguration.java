package com.easysubway.report.adapter.in.scheduler;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
class FacilityReportPersonalDataPurgeSchedulingConfiguration {

	@Bean("facilityReportPersonalDataPurgeTaskScheduler")
	ThreadPoolTaskScheduler facilityReportPersonalDataPurgeTaskScheduler() {
		var scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(1);
		scheduler.setThreadNamePrefix("facility-report-personal-data-purge-");
		scheduler.setRemoveOnCancelPolicy(true);
		return scheduler;
	}
}
