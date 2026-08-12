package com.easysubway.journey.config;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "easysubway.journey.search", ignoreUnknownFields = false)
public record JourneySearchPolicyProperties(
	Duration timeout,
	int maxSearchesPerSession) {

	private static final int MAX_SEARCHES_PER_SESSION = 50;

	public JourneySearchPolicyProperties {
		Objects.requireNonNull(timeout, "timeout");
		if (timeout.isZero() || timeout.isNegative()) {
			throw new IllegalArgumentException("timeout must be positive");
		}
		if (maxSearchesPerSession < 1 || maxSearchesPerSession > MAX_SEARCHES_PER_SESSION) {
			throw new IllegalArgumentException("maxSearchesPerSession must be between 1 and 50");
		}
	}
}
