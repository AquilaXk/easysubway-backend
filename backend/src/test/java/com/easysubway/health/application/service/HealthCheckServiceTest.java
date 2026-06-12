package com.easysubway.health.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.health.domain.HealthStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("헬스체크 서비스")
class HealthCheckServiceTest {

	@Test
	@DisplayName("백엔드 상태와 서비스 이름을 반환한다")
	void checkHealthReturnsBackendStatus() {
		HealthStatus status = new HealthCheckService().checkHealth();

		assertThat(status.status()).isEqualTo("UP");
		assertThat(status.service()).isEqualTo("easysubway-backend");
	}
}
