package com.easysubway.journey.adapter.in.web;

import com.easysubway.journey.application.JourneySessionException;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = JourneySearchController.class)
@ConditionalOnProperty(name = "easysubway.journey-v3.search-web.enabled", havingValue = "true")
final class JourneySearchExceptionHandler {

	private static final String CONTRACT_VERSION = "JOURNEY_ERROR_V1";
	private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
	private static final long MAX_ULID_MILLIS = 0xffffffffffffL;

	private final Clock clock;
	private final SecureRandom secureRandom;

	JourneySearchExceptionHandler() {
		this(Clock.systemUTC(), new SecureRandom());
	}

	JourneySearchExceptionHandler(Clock clock, SecureRandom secureRandom) {
		this.clock = Objects.requireNonNull(clock, "clock");
		this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
	}

	@ExceptionHandler(JourneySessionException.class)
	ResponseEntity<JourneyError> handleSessionFailure() {
		return error(null, 401, "ROUTE_SESSION_REQUIRED");
	}

	@ExceptionHandler(JourneySearchController.JourneySearchWebException.class)
	ResponseEntity<JourneyError> handleSearchFailure(
		JourneySearchController.JourneySearchWebException exception
	) {
		return error(exception.requestId(), exception.httpStatus(), exception.machineCode());
	}

	private ResponseEntity<JourneyError> error(String requestId, int httpStatus, String machineCode) {
		Instant occurredAt = clock.instant();
		var response = ResponseEntity.status(httpStatus)
			.header(HttpHeaders.CACHE_CONTROL, "private, no-store");
		if (httpStatus == 401) response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
		return response.body(new JourneyError(
				CONTRACT_VERSION,
				requestId == null ? nextUlid(occurredAt.toEpochMilli()) : requestId,
				machineCode,
				false,
				occurredAt.toString()
			));
	}

	private String nextUlid(long millis) {
		if (millis < 0 || millis > MAX_ULID_MILLIS) {
			throw new IllegalStateException("current instant cannot be represented as a ULID");
		}
		byte[] value = new byte[16];
		value[0] = (byte) (millis >>> 40);
		value[1] = (byte) (millis >>> 32);
		value[2] = (byte) (millis >>> 24);
		value[3] = (byte) (millis >>> 16);
		value[4] = (byte) (millis >>> 8);
		value[5] = (byte) millis;
		byte[] entropy = new byte[10];
		secureRandom.nextBytes(entropy);
		System.arraycopy(entropy, 0, value, 6, entropy.length);

		BigInteger remaining = new BigInteger(1, value);
		char[] encoded = new char[26];
		for (int index = encoded.length - 1; index >= 0; index--) {
			encoded[index] = CROCKFORD[remaining.and(BigInteger.valueOf(31)).intValue()];
			remaining = remaining.shiftRight(5);
		}
		return new String(encoded);
	}

	private record JourneyError(
		String contractVersion,
		String requestId,
		String code,
		boolean retryable,
		String occurredAt
	) {
	}
}
