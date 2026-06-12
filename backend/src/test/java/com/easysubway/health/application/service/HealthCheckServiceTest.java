package com.easysubway.health.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.health.domain.HealthStatus;
import org.junit.jupiter.api.Test;

class HealthCheckServiceTest {

	@Test
	void checkHealthReturnsBackendStatus() {
		HealthStatus status = new HealthCheckService().checkHealth();

		assertThat(status.status()).isEqualTo("UP");
		assertThat(status.service()).isEqualTo("easysubway-backend");
	}
}

