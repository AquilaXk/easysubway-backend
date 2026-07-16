package com.easysubway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
public class EasySubwayBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(EasySubwayBackendApplication.class, args);
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(
		prefix = "easysubway.scheduling",
		name = "enabled",
		havingValue = "true",
		matchIfMissing = true
	)
	@EnableScheduling
	static class SchedulingConfiguration {
	}

}
