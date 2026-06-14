package com.easysubway.auth.application.service;

import com.easysubway.auth.application.port.in.AnonymousAuthRateLimitUseCase;
import com.easysubway.auth.application.port.out.ConsumeAnonymousAuthRateLimitPort;
import com.easysubway.auth.domain.AnonymousAuthRateLimitExceededException;
import org.springframework.stereotype.Service;

@Service
public class AnonymousAuthRateLimitService implements AnonymousAuthRateLimitUseCase {

	private final AnonymousAuthRateLimitProperties properties;
	private final ConsumeAnonymousAuthRateLimitPort consumeAnonymousAuthRateLimitPort;

	public AnonymousAuthRateLimitService(
		AnonymousAuthRateLimitProperties properties,
		ConsumeAnonymousAuthRateLimitPort consumeAnonymousAuthRateLimitPort
	) {
		this.properties = properties;
		this.consumeAnonymousAuthRateLimitPort = consumeAnonymousAuthRateLimitPort;
	}

	@Override
	public void check(String clientKey) {
		long count = consumeAnonymousAuthRateLimitPort.consume(normalizeClientKey(clientKey), properties.getWindow());
		if (count > properties.getMaxRequests()) {
			throw new AnonymousAuthRateLimitExceededException("잠시 후 다시 시도해 주세요.");
		}
	}

	private String normalizeClientKey(String clientKey) {
		if (clientKey == null || clientKey.isBlank()) {
			return "unknown";
		}
		return clientKey.trim();
	}
}
