package com.easysubway.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("운영 영속 저장소 준비 상태")
class ProductionPersistenceReadinessConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(ProductionPersistenceReadinessConfiguration.class);

	@Test
	@DisplayName("운영 프로필은 영속 저장소 구현 전까지 명확한 오류로 시작을 막는다")
	void prodProfileFailsWithPersistenceReadinessMessage() {
		contextRunner
			.withPropertyValues("spring.profiles.active=prod")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure())
					.hasMessageContaining("운영 영속 저장소 구현이 필요합니다.");
			});
	}

	@Test
	@DisplayName("개발 프로필은 영속 저장소 준비 상태 검사 없이 시작한다")
	void devProfileDoesNotRunPersistenceReadinessGate() {
		contextRunner
			.withPropertyValues("spring.profiles.active=dev")
			.run(context -> assertThat(context).hasNotFailed());
	}
}
