package com.easysubway.datapack.domain;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.regex.Pattern;

public record DatapackReleaseDelivery(
	String idempotencyKey,
	String releaseRequestId,
	long releaseSequence,
	String manifestSha256,
	String channel,
	String candidateId,
	String payloadSha256,
	String signatureSha256,
	State state,
	int attempts,
	LocalDateTime nextAttemptAt,
	LocalDateTime reconcileDeadline,
	LocalDateTime deadLetterDeadline,
	String httpClass,
	String sanitizedDetail,
	LocalDateTime claimedAt,
	String claimOwner,
	LocalDateTime createdAt,
	LocalDateTime updatedAt
) {
	private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
	private static final Duration RECONCILE_AFTER = Duration.ofMinutes(10);
	private static final Duration DEAD_LETTER_AFTER = Duration.ofMinutes(70);

	public DatapackReleaseDelivery {
		idempotencyKey = required(idempotencyKey, "idempotencyKey");
		releaseRequestId = required(releaseRequestId, "releaseRequestId");
		candidateId = required(candidateId, "candidateId");
		if (state == null) throw new IllegalArgumentException("state must not be null");
		if (!java.util.Set.of("dev", "staging", "production").contains(channel)) {
			throw new IllegalArgumentException("channel is invalid");
		}
		manifestSha256 = sha(manifestSha256, "manifestSha256");
		payloadSha256 = payloadSha256 == null ? null : sha(payloadSha256, "payloadSha256");
		signatureSha256 = sha(signatureSha256, "signatureSha256");
		if (releaseSequence < 1) throw new IllegalArgumentException("releaseSequence must be positive");
		if (!idempotencyKey.equals(releaseRequestId + ":" + releaseSequence + ":" + manifestSha256)) {
			throw new IllegalArgumentException("idempotencyKey must match release identity");
		}
		if (attempts < 0) throw new IllegalArgumentException("attempts must not be negative");
	}

	public static DatapackReleaseDelivery pending(String releaseRequestId, long releaseSequence,
		String manifestSha256, String channel, String candidateId, String payloadSha256,
		String signatureSha256, LocalDateTime now) {
		var normalizedManifestSha256 = sha(manifestSha256, "manifestSha256");
		return new DatapackReleaseDelivery(
			releaseRequestId + ":" + releaseSequence + ":" + normalizedManifestSha256,
			releaseRequestId, releaseSequence, normalizedManifestSha256, channel, candidateId,
			payloadSha256, signatureSha256, State.PENDING, 0, now,
			now.plus(RECONCILE_AFTER), now.plus(DEAD_LETTER_AFTER), null, null, null, null, now, now);
	}

	private static String required(String value, String name) {
		if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}

	private static String sha(String value, String name) {
		if (value == null || !SHA256.matcher(value).matches()) {
			throw new IllegalArgumentException(name + " must be a sha256 hex string");
		}
		return value.toLowerCase(Locale.ROOT);
	}

	public enum State {
		PENDING, DELIVERED, RETRY_SCHEDULED, RECONCILIATION_REQUIRED, DEAD_LETTER
	}
}
