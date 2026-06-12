package com.easysubway.health.application.service;

import com.easysubway.health.application.port.in.CheckHealthUseCase;
import com.easysubway.health.domain.HealthStatus;
import org.springframework.stereotype.Service;

@Service
public class HealthCheckService implements CheckHealthUseCase {

	private static final String SERVICE_NAME = "easysubway-backend";

	@Override
	public HealthStatus checkHealth() {
		return HealthStatus.up(SERVICE_NAME);
	}
}

