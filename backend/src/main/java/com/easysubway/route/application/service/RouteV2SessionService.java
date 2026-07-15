package com.easysubway.route.application.service;

import com.easysubway.route.application.port.out.PlayIntegrityDecoder;
import com.easysubway.route.application.port.out.PlayIntegrityDecoder.PlayIntegrityVerdict;
import com.easysubway.route.application.port.out.RouteV2AccessStore;
import com.easysubway.route.application.port.out.RouteV2AccessStore.RouteV2Session;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("prod | staging | release | prod-like")
public class RouteV2SessionService {

	static final String PACKAGE_NAME = "com.easysubway.app";
	static final String SCOPE = "route:v2:itx";
	private static final Duration VERDICT_MAX_AGE = Duration.ofMinutes(2);
	private static final Duration SESSION_TTL = Duration.ofMinutes(10);
	private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();
	private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();

	private final PlayIntegrityDecoder decoder;
	private final RouteV2AccessStore store;
	private final Clock clock;
	private final SecureRandom secureRandom;
	private final String certificateDigest;

	@Autowired
	public RouteV2SessionService(
		PlayIntegrityDecoder decoder,
		RouteV2AccessStore store,
		@Value("${easysubway.route-v2.play-integrity.certificate-sha256:}") String certificateDigest
	) {
		this(decoder, store, Clock.systemUTC(), new SecureRandom(), certificateDigest);
	}

	RouteV2SessionService(
		PlayIntegrityDecoder decoder,
		RouteV2AccessStore store,
		Clock clock,
		String certificateDigest
	) {
		this(decoder, store, clock, new SecureRandom(), certificateDigest);
	}

	RouteV2SessionService(
		PlayIntegrityDecoder decoder,
		RouteV2AccessStore store,
		Clock clock,
		SecureRandom secureRandom,
		String certificateDigest
	) {
		this.decoder = decoder;
		this.store = store;
		this.clock = clock;
		this.secureRandom = secureRandom;
		this.certificateDigest = validatedCertificateDigest(certificateDigest);
	}

	public IssuedRouteV2Session issue(String integrityToken, String clientNonce) {
		validateNonce(clientNonce);
		Instant now = clock.instant();
		PlayIntegrityVerdict verdict;
		try {
			verdict = decoder.decode(integrityToken);
		} catch (RuntimeException exception) {
			throw new RouteSessionAttestationRejectedException(exception);
		}
		validateVerdict(verdict, clientNonce, now);
		byte[] tokenBytes = new byte[32];
		secureRandom.nextBytes(tokenBytes);
		String token = BASE64_URL.encodeToString(tokenBytes);
		Instant expiresAt = now.plus(SESSION_TTL);
		var session = new RouteV2Session(sha256Hex(token), SCOPE, now, expiresAt, 0);
		if (!store.claimNonceAndSaveSession(
			sha256Hex(clientNonce),
			now.plus(VERDICT_MAX_AGE),
			now,
			session
		)) {
			throw new RouteSessionAttestationRejectedException();
		}
		return new IssuedRouteV2Session(token, SCOPE, now, expiresAt);
	}

	static String canonicalRequest(String clientNonce) {
		return "{\"clientNonce\":\"" + clientNonce + "\",\"purpose\":\"route:v2:itx\",\"version\":1}";
	}

	static String requestHash(String clientNonce) {
		return BASE64_URL.encodeToString(sha256(canonicalRequest(clientNonce).getBytes(StandardCharsets.UTF_8)));
	}

	public static String tokenHash(String token) {
		return sha256Hex(token);
	}

	private void validateNonce(String clientNonce) {
		try {
			if (clientNonce == null
				|| !clientNonce.matches("^[A-Za-z0-9_-]{22}$")
				|| BASE64_URL_DECODER.decode(clientNonce).length != 16) {
				throw new RouteSessionAttestationRejectedException();
			}
		} catch (IllegalArgumentException exception) {
			throw new RouteSessionAttestationRejectedException(exception);
		}
	}

	private static String validatedCertificateDigest(String certificateDigest) {
		try {
			if (certificateDigest == null
				|| !certificateDigest.matches("^[A-Za-z0-9_-]{43}$")
				|| BASE64_URL_DECODER.decode(certificateDigest).length != 32) {
				throw new IllegalStateException("Play Integrity certificate SHA-256 is invalid");
			}
			return certificateDigest;
		} catch (IllegalArgumentException exception) {
			throw new IllegalStateException("Play Integrity certificate SHA-256 is invalid", exception);
		}
	}

	private void validateVerdict(PlayIntegrityVerdict verdict, String clientNonce, Instant now) {
		if (verdict == null
			|| !PACKAGE_NAME.equals(verdict.requestPackageName())
			|| !PACKAGE_NAME.equals(verdict.appPackageName())
			|| !"PLAY_RECOGNIZED".equals(verdict.appRecognitionVerdict())
			|| verdict.certificateSha256Digests() == null
			|| !verdict.certificateSha256Digests().contains(certificateDigest)
			|| !"LICENSED".equals(verdict.appLicensingVerdict())
			|| verdict.deviceRecognitionVerdicts() == null
			|| !verdict.deviceRecognitionVerdicts().contains("MEETS_DEVICE_INTEGRITY")
			|| verdict.requestTimestamp() == null
			|| verdict.requestTimestamp().isAfter(now)
			|| Duration.between(verdict.requestTimestamp(), now).compareTo(VERDICT_MAX_AGE) > 0
			|| !requestHashMatches(clientNonce, verdict.requestHash())) {
			throw new RouteSessionAttestationRejectedException();
		}
	}

	private boolean requestHashMatches(String clientNonce, String decodedRequestHash) {
		try {
			byte[] expected = sha256(canonicalRequest(clientNonce).getBytes(StandardCharsets.UTF_8));
			byte[] actual = BASE64_URL_DECODER.decode(decodedRequestHash);
			return MessageDigest.isEqual(expected, actual);
		} catch (IllegalArgumentException | NullPointerException exception) {
			return false;
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

	public record IssuedRouteV2Session(String token, String scope, Instant issuedAt, Instant expiresAt) {
	}
}
