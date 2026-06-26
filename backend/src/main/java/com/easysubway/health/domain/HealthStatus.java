package com.easysubway.health.domain;

import java.util.List;

public record HealthStatus(String status, String service, List<HealthComponent> components) {

	public HealthStatus {
		components = List.copyOf(components);
	}

	public static HealthStatus of(String status, String service, List<HealthComponent> components) {
		return new HealthStatus(status, service, components);
	}

	public static HealthStatus up(String service) {
		return new HealthStatus("UP", service, List.of(new HealthComponent(
			"application",
			"UP",
			"애플리케이션 기동",
			"서비스 프로세스가 요청을 처리할 수 있습니다."
		)));
	}
}
