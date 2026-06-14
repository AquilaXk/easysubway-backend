package com.easysubway.auth.application.service;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Validated
@Component
@ConfigurationProperties(prefix = "easysubway.auth.rate-limit.anonymous")
public class AnonymousAuthRateLimitProperties {

	@Min(1)
	private int maxRequests = 20;
	@NotNull
	@DurationMin(millis = 1)
	private Duration window = Duration.ofMinutes(10);

	public int getMaxRequests() {
		return maxRequests;
	}

	public void setMaxRequests(int maxRequests) {
		this.maxRequests = maxRequests;
	}

	public Duration getWindow() {
		return window;
	}

	public void setWindow(Duration window) {
		this.window = window;
	}
}
