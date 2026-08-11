package com.easysubway.journey.application;

import com.easysubway.journey.application.JourneySessionException.Kind;
import com.easysubway.journey.application.JourneySessionIntegrityPort.ProviderUnavailableException;
import com.easysubway.journey.application.JourneySessionIntegrityPort.Verdict;
import com.easysubway.journey.application.JourneySessionStore.AuthorizationStatus;
import com.easysubway.journey.application.JourneySessionStore.Session;
import com.easysubway.journey.application.JourneySessionStore.SessionUse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

public final class JourneySessionService {
	static final String PACKAGE_NAME = "com.easysubway.app";
	static final String SCOPE = "journey:v3";
	private static final int MAX_INTEGRITY_TOKEN_LENGTH = 16_384;
	private static final Duration VERDICT_MAX_AGE = Duration.ofSeconds(120);
	private static final Duration NONCE_CLAIM_TTL = Duration.ofSeconds(120);
	private static final Duration SESSION_TTL = Duration.ofSeconds(600);
	private static final Pattern NONCE = Pattern.compile("^[A-Za-z0-9_-]{21}[AQgw]$");
	private static final Pattern CERTIFICATE_DIGEST = Pattern.compile("^[A-Za-z0-9_-]{43}$");
	private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();
	private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();

	private final JourneySessionIntegrityPort integrityPort;
	private final JourneySessionStore store;
	private final Clock clock;
	private final SecureRandom secureRandom;
	private final String certificateDigest;

	public JourneySessionService(
		JourneySessionIntegrityPort integrityPort,
		JourneySessionStore store,
		Clock clock,
		SecureRandom secureRandom,
		String certificateDigest
	) {
		this.integrityPort = Objects.requireNonNull(integrityPort, "integrityPort");
		this.store = Objects.requireNonNull(store, "store");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
		this.certificateDigest = validateCertificateDigest(certificateDigest);
	}

	public IssuedSession issue(String integrityToken, String clientNonce) {
		validateIssuanceInput(integrityToken, clientNonce);
		Instant now = clock.instant();
		Verdict verdict;
		try {
			verdict = integrityPort.decode(integrityToken);
		} catch (ProviderUnavailableException exception) {
			throw failure(Kind.ATTESTATION_UNAVAILABLE);
		} catch (RuntimeException exception) {
			throw failure(Kind.ATTESTATION_REJECTED);
		}
		validateVerdict(verdict, clientNonce, now);

		byte[] tokenBytes = new byte[32];
		secureRandom.nextBytes(tokenBytes);
		String token = BASE64_URL.encodeToString(tokenBytes);
		Instant expiresAt = now.plus(SESSION_TTL);
		var session = new Session(sha256Hex(token), SCOPE, now, expiresAt);
		boolean claimed;
		try {
			claimed = store.claimNonceAndSaveSession(
				sha256Hex(clientNonce), now.plus(NONCE_CLAIM_TTL), now, session
			);
		} catch (RuntimeException exception) {
			throw failure(Kind.ATTESTATION_UNAVAILABLE);
		}
		if (!claimed) throw failure(Kind.ATTESTATION_REJECTED);
		return new IssuedSession(token, SCOPE, now, expiresAt);
	}

	public AuthorizedSession authorize(String token) {
		if (token == null || token.isBlank()) throw failure(Kind.SESSION_REQUIRED);
		Instant now = clock.instant();
		SessionUse use = store.authorize(sha256Hex(token), SCOPE, now);
		if (use == null
			|| use.status() != AuthorizationStatus.VALID
			|| !SCOPE.equals(use.scope())
			|| use.expiresAt() == null
			|| !use.expiresAt().isAfter(now)) {
			throw failure(Kind.SESSION_REQUIRED);
		}
		return new AuthorizedSession(use.scope(), use.expiresAt());
	}

	static String canonicalRequest(String clientNonce) {
		return "{\"clientNonce\":\"" + clientNonce + "\",\"purpose\":\"journey:v3:session\",\"version\":1}";
	}

	static String requestHash(String clientNonce) {
		return BASE64_URL.encodeToString(sha256(canonicalRequest(clientNonce).getBytes(StandardCharsets.UTF_8)));
	}

	private void validateIssuanceInput(String integrityToken, String clientNonce) {
		if (integrityToken == null
			|| integrityToken.isBlank()
			|| integrityToken.codePointCount(0, integrityToken.length()) > MAX_INTEGRITY_TOKEN_LENGTH
			|| !validNonce(clientNonce)) {
			throw failure(Kind.INVALID_REQUEST);
		}
	}

	private boolean validNonce(String clientNonce) {
		try {
			return clientNonce != null
				&& NONCE.matcher(clientNonce).matches()
				&& BASE64_URL_DECODER.decode(clientNonce).length == 16
				&& BASE64_URL.encodeToString(BASE64_URL_DECODER.decode(clientNonce)).equals(clientNonce);
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	private void validateVerdict(Verdict verdict, String clientNonce, Instant now) {
		if (verdict == null
			|| !PACKAGE_NAME.equals(verdict.requestPackageName())
			|| !requestHashMatches(clientNonce, verdict.requestHash())
			|| verdict.requestTimestamp() == null
			|| verdict.requestTimestamp().isAfter(now)
			|| Duration.between(verdict.requestTimestamp(), now).compareTo(VERDICT_MAX_AGE) > 0
			|| !PACKAGE_NAME.equals(verdict.appPackageName())
			|| !"PLAY_RECOGNIZED".equals(verdict.appRecognitionVerdict())
			|| verdict.certificateSha256Digests() == null
			|| !verdict.certificateSha256Digests().contains(certificateDigest)
			|| !"LICENSED".equals(verdict.appLicensingVerdict())
			|| verdict.deviceRecognitionVerdicts() == null
			|| !verdict.deviceRecognitionVerdicts().contains("MEETS_DEVICE_INTEGRITY")) {
			throw failure(Kind.ATTESTATION_REJECTED);
		}
	}

	private boolean requestHashMatches(String clientNonce, String actualHash) {
		if (actualHash == null) return false;
		try {
			byte[] expected = sha256(canonicalRequest(clientNonce).getBytes(StandardCharsets.UTF_8));
			byte[] actual = BASE64_URL_DECODER.decode(actualHash);
			return MessageDigest.isEqual(expected, actual);
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	private static String validateCertificateDigest(String value) {
		try {
			if (value == null
				|| !CERTIFICATE_DIGEST.matcher(value).matches()
				|| BASE64_URL_DECODER.decode(value).length != 32
				|| !BASE64_URL.encodeToString(BASE64_URL_DECODER.decode(value)).equals(value)) {
				throw new IllegalArgumentException("certificateDigest is invalid");
			}
			return value;
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("certificateDigest is invalid", exception);
		}
	}

	private static String sha256Hex(String value) {
		return HexFormat.of().formatHex(sha256(value.getBytes(StandardCharsets.UTF_8)));
	}

	private static byte[] sha256(byte[] value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static JourneySessionException failure(Kind kind) {
		return new JourneySessionException(kind);
	}

	public record IssuedSession(String token, String scope, Instant issuedAt, Instant expiresAt) {
		public IssuedSession {
			token = requireText(token, "token");
			scope = requireText(scope, "scope");
			issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
			expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
			if (!expiresAt.isAfter(issuedAt)) throw new IllegalArgumentException("expiresAt must be after issuedAt");
		}
	}

	public record AuthorizedSession(String scope, Instant expiresAt) {
		public AuthorizedSession {
			scope = requireText(scope, "scope");
			expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
		}
	}

	private static String requireText(String value, String name) {
		Objects.requireNonNull(value, name);
		if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}
}
