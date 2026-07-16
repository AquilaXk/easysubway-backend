package com.easysubway.datapack.adapter.in.scheduler;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
class DatapackReleaseSchedulingConfiguration {

	@Bean("datapackReleaseTaskScheduler")
	ThreadPoolTaskScheduler datapackReleaseTaskScheduler() {
		var scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(1);
		scheduler.setThreadNamePrefix("datapack-release-reconciliation-");
		scheduler.setRemoveOnCancelPolicy(true);
		return scheduler;
	}
}
