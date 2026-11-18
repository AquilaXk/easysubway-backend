package com.easysubway.realtime.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class RealtimeInfrastructureConfiguration {

	@Bean("realtimeArchiveExecutor")
	ThreadPoolTaskExecutor realtimeArchiveExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(1_024);
		executor.setThreadNamePrefix("realtime-archive-");
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(5);
		return executor;
	}
}
