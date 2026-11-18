package com.easysubway.train.adapter.in.scheduler;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
class TrainSearchSchedulingConfiguration {

	@Bean("trainSearchTaskScheduler")
	ThreadPoolTaskScheduler trainSearchTaskScheduler() {
		var scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(1);
		scheduler.setThreadNamePrefix("train-search-scheduler-");
		scheduler.setWaitForTasksToCompleteOnShutdown(false);
		return scheduler;
	}
}
