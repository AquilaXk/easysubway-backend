package com.easysubway.route.application.port.out;

import java.time.Instant;
import java.util.Optional;

public interface RouteV2AccessStore {

	void saveSession(RouteV2Session session);

	SessionUse consumeSession(String tokenSha256, Instant now);

	boolean claimNonce(String nonceSha256, Instant expiresAt, Instant now);

	boolean claimNonceAndSaveSession(
		String nonceSha256,
		Instant nonceExpiresAt,
		Instant now,
		RouteV2Session session
	);

	void saveState(RouteV2State state);

	Optional<RouteV2State> loadState(String routeStateId, Instant now);

	int purgeExpired(Instant now);

	record RouteV2Session(
		String tokenSha256,
		String scope,
		Instant issuedAt,
		Instant expiresAt,
		int requestCount
	) {
	}

	record SessionUse(SessionStatus status, String scope, Instant expiresAt) {
	}

	enum SessionStatus {
		VALID,
		MISSING,
		EXPIRED,
		LIMITED
	}

	record RouteV2State(
		String routeStateId,
		String originStationId,
		String destinationStationId,
		String transportScope,
		Instant requestedDepartureAt,
		String itineraryJson,
		String timetableArtifactId,
		Instant createdAt,
		Instant plannedArrivalAt,
		Instant expiresAt
	) {
	}
}
