package com.easysubway.journey.application;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

public interface JourneySessionStore {

	boolean claimNonceAndSaveSession(
		String nonceSha256,
		Instant nonceExpiresAt,
		Instant now,
		Session session
	);

	SessionUse authorizeAndConsume(
		String tokenSha256,
		String requiredScope,
		Instant now,
		int maxSearchesPerSession
	);

	record Session(String tokenSha256, String scope, Instant issuedAt, Instant expiresAt) {
		private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");

		public Session {
			if (tokenSha256 == null || !SHA256.matcher(tokenSha256).matches()) {
				throw new IllegalArgumentException("tokenSha256 must be lowercase SHA-256");
			}
			scope = requireText(scope, "scope");
			issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
			expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
			if (!expiresAt.isAfter(issuedAt)) {
				throw new IllegalArgumentException("expiresAt must be after issuedAt");
			}
		}
	}

	record SessionUse(AuthorizationStatus status, String scope, Instant expiresAt) {
	}

	enum AuthorizationStatus {
		VALID,
		LIMITED,
		MISSING,
		EXPIRED,
		SCOPE_MISMATCH
	}

	private static String requireText(String value, String name) {
		Objects.requireNonNull(value, name);
		if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}
}
