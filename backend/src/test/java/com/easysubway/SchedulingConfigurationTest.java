package com.easysubway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("백엔드 scheduling 구성")
class SchedulingConfigurationTest {
	private static final String SCHEDULED_PROCESSOR =
		"org.springframework.context.annotation.internalScheduledAnnotationProcessor";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(EasySubwayBackendApplication.SchedulingConfiguration.class);

	@Test
	@DisplayName("기본값은 scheduling을 활성화한다")
	void enablesSchedulingByDefault() {
		contextRunner.run(context -> assertThat(context).hasBean(SCHEDULED_PROCESSOR));
	}

	@Test
	@DisplayName("운영 증거 컨테이너는 scheduling을 비활성화할 수 있다")
	void disablesSchedulingForEvidenceContainer() {
		contextRunner
			.withPropertyValues("easysubway.scheduling.enabled=false")
			.run(context -> assertThat(context).doesNotHaveBean(SCHEDULED_PROCESSOR));
	}

}
