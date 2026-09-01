package com.easysubway.journey.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.journey.application.JourneySessionException.Kind;
import com.easysubway.journey.application.JourneySessionIntegrityPort.ProviderUnavailableException;
import com.easysubway.journey.application.JourneySessionIntegrityPort.Verdict;
import com.easysubway.journey.application.JourneySessionStore.AuthorizationStatus;
import com.easysubway.journey.application.JourneySessionStore.SessionUse;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class JourneySessionServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");
	private static final String NONCE = "AAAAAAAAAAAAAAAAAAAAAA";
	private static final String REQUEST_HASH = "oiyD4z8SIUGWUKR8znsbTQ1Z26WO43JHm3RUZLuwErU";
	private static final String CERTIFICATE_DIGEST = "A".repeat(43);
	private static final int MAX_SEARCHES_PER_SESSION = 50;

	@Test
	void issuesAndAuthorizesOneV3SessionWithExactContractIdentity() {
		Fakes fakes = new Fakes();

		JourneySessionService.IssuedSession issued = fakes.service().issue("integrity-token", NONCE);

		assertThat(JourneySessionService.canonicalRequest(NONCE)).isEqualTo(
			"{\"clientNonce\":\"AAAAAAAAAAAAAAAAAAAAAA\",\"purpose\":\"journey:v3:session\",\"version\":1}"
		);
		assertThat(JourneySessionService.requestHash(NONCE)).isEqualTo(REQUEST_HASH);
		assertThat(fakes.decodeCalls).isEqualTo(1);
		assertThat(fakes.decodedToken).isEqualTo("integrity-token");
		assertThat(fakes.claimCalls).isEqualTo(1);
		assertThat(fakes.savedSession.scope()).isEqualTo("journey:v3");
		assertThat(fakes.savedSession.issuedAt()).isEqualTo(NOW);
		assertThat(fakes.savedSession.expiresAt()).isEqualTo(NOW.plusSeconds(600));
		assertThat(fakes.savedSession.tokenSha256()).matches("^[a-f0-9]{64}$");
		assertThat(fakes.savedNonceSha256).matches("^[a-f0-9]{64}$");
		assertThat(fakes.nonceExpiresAt).isEqualTo(NOW.plusSeconds(120));
		assertThat(issued.scope()).isEqualTo("journey:v3");
		assertThat(issued.issuedAt()).isEqualTo(NOW);
		assertThat(issued.expiresAt()).isEqualTo(NOW.plusSeconds(600));
		assertThat(issued.token()).doesNotContain(fakes.savedSession.tokenSha256());

		fakes.authorization = new SessionUse(
			AuthorizationStatus.VALID, "journey:v3", NOW.plusSeconds(600)
		);
		fakes.service().authorize(issued.token());
		assertThat(fakes.authorizeCalls).isEqualTo(1);
		assertThat(fakes.requiredScope).isEqualTo("journey:v3");
		assertThat(fakes.costUnits).isOne();
		assertThat(fakes.maxCostUnitsPerSession).isEqualTo(MAX_SEARCHES_PER_SESSION);
		assertThat(fakes.authorizedTokenSha256).isEqualTo(fakes.savedSession.tokenSha256());
	}

	@Test
	void delegatesExplicitPositiveAuthorizationCostWithoutChangingPointCost() {
		Fakes fakes = new Fakes();

		fakes.service().authorize("opaque-token", 3);

		assertThat(fakes.authorizeCalls).isEqualTo(1);
		assertThat(fakes.costUnits).isEqualTo(3);
		assertThat(fakes.maxCostUnitsPerSession).isEqualTo(MAX_SEARCHES_PER_SESSION);
		assertThatThrownBy(() -> fakes.service().authorize("opaque-token", 0))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("costUnits must be positive");
		assertThatThrownBy(() -> fakes.service().authorize("opaque-token", -1))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("costUnits must be positive");
		assertThat(fakes.authorizeCalls).isEqualTo(1);
	}

	@Test
	void rejectsMalformedIssuanceInputBeforeDecoderOrStore() {
		Fakes fakes = new Fakes();

		assertFailure(() -> fakes.service().issue("", NONCE), Kind.INVALID_REQUEST);
		assertFailure(() -> fakes.service().issue("x".repeat(16_385), NONCE), Kind.INVALID_REQUEST);
		assertFailure(() -> fakes.service().issue("integrity-token", "A".repeat(21) + "B"), Kind.INVALID_REQUEST);

		assertThat(fakes.decodeCalls).isZero();
		assertThat(fakes.claimCalls).isZero();
	}

	@Test
	void rejectsEveryClosedVerdictMismatchWithoutSessionMutation() {
		List<UnaryOperator<Verdict>> invalidVerdicts = List.of(
			verdict -> copy(verdict, null, verdict.requestHash(), verdict.requestTimestamp(),
				verdict.appPackageName(), verdict.appRecognitionVerdict(), verdict.certificateSha256Digests(),
				verdict.appLicensingVerdict(), verdict.deviceRecognitionVerdicts()),
			verdict -> copy(verdict, verdict.requestPackageName(), "wrong", verdict.requestTimestamp(),
				verdict.appPackageName(), verdict.appRecognitionVerdict(), verdict.certificateSha256Digests(),
				verdict.appLicensingVerdict(), verdict.deviceRecognitionVerdicts()),
			verdict -> copy(verdict, verdict.requestPackageName(), verdict.requestHash(), NOW.plusSeconds(1),
				verdict.appPackageName(), verdict.appRecognitionVerdict(), verdict.certificateSha256Digests(),
				verdict.appLicensingVerdict(), verdict.deviceRecognitionVerdicts()),
			verdict -> copy(verdict, verdict.requestPackageName(), verdict.requestHash(), NOW.minusSeconds(121),
				verdict.appPackageName(), verdict.appRecognitionVerdict(), verdict.certificateSha256Digests(),
				verdict.appLicensingVerdict(), verdict.deviceRecognitionVerdicts()),
			verdict -> copy(verdict, verdict.requestPackageName(), verdict.requestHash(), verdict.requestTimestamp(),
				"other.app", verdict.appRecognitionVerdict(), verdict.certificateSha256Digests(),
				verdict.appLicensingVerdict(), verdict.deviceRecognitionVerdicts()),
			verdict -> copy(verdict, verdict.requestPackageName(), verdict.requestHash(), verdict.requestTimestamp(),
				verdict.appPackageName(), "UNEVALUATED", verdict.certificateSha256Digests(),
				verdict.appLicensingVerdict(), verdict.deviceRecognitionVerdicts()),
			verdict -> copy(verdict, verdict.requestPackageName(), verdict.requestHash(), verdict.requestTimestamp(),
				verdict.appPackageName(), verdict.appRecognitionVerdict(), List.of("wrong"),
				verdict.appLicensingVerdict(), verdict.deviceRecognitionVerdicts()),
			verdict -> copy(verdict, verdict.requestPackageName(), verdict.requestHash(), verdict.requestTimestamp(),
				verdict.appPackageName(), verdict.appRecognitionVerdict(), verdict.certificateSha256Digests(),
				"UNLICENSED", verdict.deviceRecognitionVerdicts()),
			verdict -> copy(verdict, verdict.requestPackageName(), verdict.requestHash(), verdict.requestTimestamp(),
				verdict.appPackageName(), verdict.appRecognitionVerdict(), verdict.certificateSha256Digests(),
				verdict.appLicensingVerdict(), List.of("MEETS_BASIC_INTEGRITY"))
		);

		for (UnaryOperator<Verdict> invalid : invalidVerdicts) {
			Fakes fakes = new Fakes();
			fakes.verdict = invalid.apply(fakes.verdict);
			assertFailure(() -> fakes.service().issue("integrity-token", NONCE), Kind.ATTESTATION_REJECTED);
			assertThat(fakes.claimCalls).isZero();
		}
	}

	@Test
	void mapsProviderUnavailableAndDuplicateNonceWithoutRetry() {
		Fakes unavailable = new Fakes();
		unavailable.decoderFailure = new ProviderUnavailableException(new IllegalStateException("provider"));
		assertFailure(() -> unavailable.service().issue("integrity-token", NONCE), Kind.ATTESTATION_UNAVAILABLE);
		assertThat(unavailable.decodeCalls).isEqualTo(1);
		assertThat(unavailable.claimCalls).isZero();

		Fakes duplicate = new Fakes();
		duplicate.claimed = false;
		assertFailure(() -> duplicate.service().issue("integrity-token", NONCE), Kind.ATTESTATION_REJECTED);
		assertThat(duplicate.decodeCalls).isEqualTo(1);
		assertThat(duplicate.claimCalls).isEqualTo(1);
	}

	@Test
	void rejectsMissingExpiredOrWrongScopeAuthorization() {
		List<SessionUse> invalid = List.of(
			new SessionUse(AuthorizationStatus.MISSING, null, null),
			new SessionUse(AuthorizationStatus.EXPIRED, "journey:v3", NOW),
			new SessionUse(AuthorizationStatus.SCOPE_MISMATCH, "route:v2:itx", NOW.plusSeconds(60)),
			new SessionUse(AuthorizationStatus.VALID, "journey:v3", NOW)
		);

		for (SessionUse use : invalid) {
			Fakes fakes = new Fakes();
			fakes.authorization = use;
			assertFailure(() -> fakes.service().authorize("opaque-token"), Kind.SESSION_REQUIRED);
			assertThat(fakes.authorizeCalls).isEqualTo(1);
		}

		Fakes malformed = new Fakes();
		assertFailure(() -> malformed.service().authorize(" "), Kind.SESSION_REQUIRED);
		assertThat(malformed.authorizeCalls).isZero();

		Fakes unavailable = new Fakes();
		unavailable.authorizationFailure = new IllegalStateException("store details");
		assertFailure(() -> unavailable.service().authorize("opaque-token"), Kind.SESSION_REQUIRED);
		assertThat(unavailable.authorizeCalls).isEqualTo(1);

		Fakes absent = new Fakes();
		absent.authorization = null;
		assertFailure(() -> absent.service().authorize("opaque-token"), Kind.SESSION_REQUIRED);
		assertThat(absent.authorizeCalls).isEqualTo(1);
	}

	@Test
	void mapsOnlyConsumedSessionLimitToRateLimitedAndRequiresExplicitSafeLimit() {
		Fakes limited = new Fakes();
		limited.authorization = new SessionUse(
			AuthorizationStatus.LIMITED, "journey:v3", NOW.plusSeconds(600)
		);
		assertFailure(() -> limited.service().authorize("opaque-token"), Kind.RATE_LIMITED);
		assertThat(limited.authorizeCalls).isEqualTo(1);
		assertThat(limited.maxCostUnitsPerSession).isEqualTo(MAX_SEARCHES_PER_SESSION);

		for (int invalidLimit : List.of(0, 51)) {
			assertThatThrownBy(() -> limited.service(invalidLimit))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("maxSearchesPerSession must be between 1 and 50");
		}
	}

	private static Verdict validVerdict() {
		return new Verdict(
			"com.easysubway.app",
			REQUEST_HASH,
			NOW.minusSeconds(60),
			"com.easysubway.app",
			"PLAY_RECOGNIZED",
			List.of(CERTIFICATE_DIGEST),
			"LICENSED",
			List.of("MEETS_DEVICE_INTEGRITY")
		);
	}

	private static Verdict copy(
		Verdict ignored,
		String requestPackageName,
		String requestHash,
		Instant requestTimestamp,
		String appPackageName,
		String appRecognitionVerdict,
		List<String> certificateSha256Digests,
		String appLicensingVerdict,
		List<String> deviceRecognitionVerdicts
	) {
		return new Verdict(
			requestPackageName,
			requestHash,
			requestTimestamp,
			appPackageName,
			appRecognitionVerdict,
			certificateSha256Digests,
			appLicensingVerdict,
			deviceRecognitionVerdicts
		);
	}

	private static void assertFailure(Runnable action, Kind kind) {
		String expectedMachineCode = switch (kind) {
			case INVALID_REQUEST -> "INVALID_JOURNEY_SESSION_REQUEST";
			case ATTESTATION_REJECTED -> "ROUTE_SESSION_ATTESTATION_REJECTED";
			case ATTESTATION_UNAVAILABLE -> "ROUTE_SESSION_ATTESTATION_UNAVAILABLE";
			case SESSION_REQUIRED -> "ROUTE_SESSION_REQUIRED";
			case RATE_LIMITED -> "ROUTE_RATE_LIMITED";
		};
		int expectedHttpStatus = switch (kind) {
			case INVALID_REQUEST -> 400;
			case ATTESTATION_REJECTED -> 403;
			case ATTESTATION_UNAVAILABLE -> 503;
			case SESSION_REQUIRED -> 401;
			case RATE_LIMITED -> 429;
		};
		assertThatThrownBy(action::run)
			.isInstanceOfSatisfying(JourneySessionException.class, exception -> {
				assertThat(exception.kind()).isEqualTo(kind);
				assertThat(exception.machineCode()).isEqualTo(expectedMachineCode);
				assertThat(exception.httpStatus()).isEqualTo(expectedHttpStatus);
			});
	}

	private static final class Fakes {
		private Verdict verdict = validVerdict();
		private RuntimeException decoderFailure;
		private boolean claimed = true;
		private RuntimeException authorizationFailure;
		private SessionUse authorization = new SessionUse(
			AuthorizationStatus.VALID, "journey:v3", NOW.plusSeconds(600)
		);
		private int decodeCalls;
		private int claimCalls;
		private int authorizeCalls;
		private String decodedToken;
		private String savedNonceSha256;
		private Instant nonceExpiresAt;
		private JourneySessionStore.Session savedSession;
		private String authorizedTokenSha256;
		private String requiredScope;
		private int costUnits;
		private int maxCostUnitsPerSession;

		private JourneySessionService service() {
			return service(MAX_SEARCHES_PER_SESSION);
		}

		private JourneySessionService service(int maxSearches) {
			return new JourneySessionService(
				token -> {
					decodeCalls++;
					decodedToken = token;
					if (decoderFailure != null) throw decoderFailure;
					return verdict;
				},
				new JourneySessionStore() {
					@Override
					public boolean claimNonceAndSaveSession(
						String nonceSha256,
						Instant expiresAt,
						Instant now,
						Session session
					) {
						claimCalls++;
						savedNonceSha256 = nonceSha256;
						nonceExpiresAt = expiresAt;
						savedSession = session;
						return claimed;
					}

					@Override
					public SessionUse authorizeAndConsume(
						String tokenSha256,
						String scope,
						Instant now,
						int cost,
						int maxCostUnits
					) {
						authorizeCalls++;
						authorizedTokenSha256 = tokenSha256;
						requiredScope = scope;
						costUnits = cost;
						maxCostUnitsPerSession = maxCostUnits;
						if (authorizationFailure != null) throw authorizationFailure;
						return authorization;
					}
				},
				Clock.fixed(NOW, ZoneOffset.UTC),
				new FixedSecureRandom(),
				CERTIFICATE_DIGEST,
				maxSearches
			);
		}
	}

	private static final class FixedSecureRandom extends SecureRandom {
		@Override
		public void nextBytes(byte[] bytes) {
			java.util.Arrays.fill(bytes, (byte) 0);
		}
	}
}
