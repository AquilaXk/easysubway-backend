package com.easysubway.health.adapter.out.actuator;

import com.easysubway.health.application.port.in.CheckHealthUseCase;
import com.easysubway.health.domain.HealthStatus;
import java.util.Map;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
class BackendComponentHealthIndicator implements HealthIndicator {

	private final CheckHealthUseCase checkHealthUseCase;

	BackendComponentHealthIndicator(CheckHealthUseCase checkHealthUseCase) {
		this.checkHealthUseCase = checkHealthUseCase;
	}

	@Override
	public Health health() {
		HealthStatus health = checkHealthUseCase.checkHealth();
		return Health.status(health.status())
			.withDetail("summaryStatus", health.status())
			.withDetail("components", health.components().stream()
				.map(component -> Map.of(
					"name", component.name(),
					"status", component.status(),
					"reason", component.reason()
				))
				.toList())
			.build();
	}
}
