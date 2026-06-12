package com.easysubway.health.application.port.in;

import com.easysubway.health.domain.HealthStatus;

public interface CheckHealthUseCase {

	HealthStatus checkHealth();
}

