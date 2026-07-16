package com.easysubway.route.application.service;

import java.time.Duration;
import java.time.Instant;

public final class RouteV2EphemeralStateService {

	private static final Duration MINIMUM_TTL = Duration.ofMinutes(30);
	private static final Duration ARRIVAL_GRACE = Duration.ofMinutes(30);
	private static final Duration MAXIMUM_TTL = Duration.ofHours(6);

	private RouteV2EphemeralStateService() {
	}

	public static Instant expiresAt(Instant createdAt, Instant plannedArrivalAt) {
		Instant minimumExpiry = createdAt.plus(MINIMUM_TTL);
		Instant arrivalExpiry = plannedArrivalAt.plus(ARRIVAL_GRACE);
		Instant desiredExpiry = minimumExpiry.isAfter(arrivalExpiry) ? minimumExpiry : arrivalExpiry;
		Instant maximumExpiry = createdAt.plus(MAXIMUM_TTL);
		return desiredExpiry.isBefore(maximumExpiry) ? desiredExpiry : maximumExpiry;
	}
}
