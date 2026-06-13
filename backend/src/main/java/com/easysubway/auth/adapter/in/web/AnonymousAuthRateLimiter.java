package com.easysubway.auth.adapter.in.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AnonymousAuthRateLimiter {

	static final int MAX_ISSUE_REQUESTS_PER_CLIENT = 20;
	private static final Duration ISSUE_WINDOW = Duration.ofMinutes(10);
	private static final int MAX_TRACKED_CLIENTS = 10_000;

	private final Clock clock;
	private final int maxRequests;
	private final Duration window;
	private final int maxTrackedClients;
	private final Map<String, RequestWindow> windowsByClientKey = new LinkedHashMap<>();

	public AnonymousAuthRateLimiter() {
		this(Clock.systemDefaultZone(), MAX_ISSUE_REQUESTS_PER_CLIENT, ISSUE_WINDOW, MAX_TRACKED_CLIENTS);
	}

	AnonymousAuthRateLimiter(
		Clock clock,
		int maxRequests,
		Duration window,
		int maxTrackedClients
	) {
		this.clock = clock;
		this.maxRequests = maxRequests;
		this.window = window;
		this.maxTrackedClients = maxTrackedClients;
	}

	synchronized void check(String clientKey) {
		Instant now = Instant.now(clock);
		String normalizedClientKey = normalizeClientKey(clientKey);
		evictExpiredWindows(now);
		evictOldestWindowIfNeeded(normalizedClientKey);

		RequestWindow windowForClient = windowsByClientKey.get(normalizedClientKey);
		if (windowForClient == null || windowForClient.isExpiredAt(now)) {
			windowsByClientKey.put(normalizedClientKey, RequestWindow.first(now.plus(window)));
			return;
		}
		if (windowForClient.count() >= maxRequests) {
			throw new AnonymousAuthRateLimitExceededException("잠시 후 다시 시도해 주세요.");
		}

		windowsByClientKey.put(normalizedClientKey, windowForClient.increment());
	}

	private String normalizeClientKey(String clientKey) {
		if (clientKey == null || clientKey.isBlank()) {
			return "unknown";
		}
		return clientKey.trim();
	}

	private void evictExpiredWindows(Instant now) {
		windowsByClientKey.entrySet().removeIf(entry -> entry.getValue().isExpiredAt(now));
	}

	private void evictOldestWindowIfNeeded(String clientKey) {
		if (windowsByClientKey.containsKey(clientKey) || windowsByClientKey.size() < maxTrackedClients) {
			return;
		}
		String oldestClientKey = windowsByClientKey.keySet().iterator().next();
		windowsByClientKey.remove(oldestClientKey);
	}

	private record RequestWindow(Instant resetAt, int count) {

		private static RequestWindow first(Instant resetAt) {
			return new RequestWindow(resetAt, 1);
		}

		private boolean isExpiredAt(Instant now) {
			return !now.isBefore(resetAt);
		}

		private RequestWindow increment() {
			return new RequestWindow(resetAt, count + 1);
		}
	}
}

class AnonymousAuthRateLimitExceededException extends RuntimeException {

	AnonymousAuthRateLimitExceededException(String message) {
		super(message);
	}
}
