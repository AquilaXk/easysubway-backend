package com.easysubway.health.domain;

public record HealthComponent(
	String name,
	String status,
	String label,
	String reason
) {
}
