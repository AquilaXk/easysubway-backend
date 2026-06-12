package com.easysubway.health.domain;

public record HealthStatus(String status, String service) {

	public static HealthStatus up(String service) {
		return new HealthStatus("UP", service);
	}
}

