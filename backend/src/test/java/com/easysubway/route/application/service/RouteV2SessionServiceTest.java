package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.easysubway.route.application.port.out.PlayIntegrityDecoder;
import com.easysubway.route.application.port.out.PlayIntegrityDecoder.PlayIntegrityVerdict;
import com.easysubway.route.application.port.out.PlayIntegrityProviderUnavailableException;
import com.easysubway.route.application.port.out.RouteV2AccessStore;
import com.easysubway.route.application.port.out.RouteV2AccessStore.RouteV2Session;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("Play Integrity Route V2 session")
class RouteV2SessionServiceTest {

	private static final Instant NOW = Instant.parse("2026-07-16T09:00:00Z");
	private static final String NONCE = "AAAAAAAAAAAAAAAAAAAAAA";
	private static final String REQUEST_HASH = "SVOaIn_B5rcm1TVIPIEozQ_iGimOCakTxKuH3iXlD18";
	private static final String CERTIFICATE_DIGEST = "A".repeat(43);

	private PlayIntegrityDecoder decoder;
	private RouteV2AccessStore store;
	private RouteV2SessionService service;

	@BeforeEach
	void setUp() {
		decoder = mock(PlayIntegrityDecoder.class);
		store = mock(RouteV2AccessStore.class);
		service = new RouteV2SessionService(
			decoder,
			store,
			Clock.fixed(NOW, ZoneOffset.UTC),
			CERTIFICATE_DIGEST
		);
		when(store.claimNonceAndSaveSession(any(), any(), any(), any())).thenReturn(true);
	}

	@Test
	@DisplayName("canonical JSON과 requestHash 표현을 byte-for-byte 고정한다")
	void canonicalRequestHashIsStable() {
		assertThat(RouteV2SessionService.canonicalRequest(NONCE))
			.isEqualTo("{\"clientNonce\":\"AAAAAAAAAAAAAAAAAAAAAA\",\"purpose\":\"route:v2:itx\",\"version\":1}");
		assertThat(RouteV2SessionService.requestHash(NONCE)).isEqualTo(REQUEST_HASH);
	}

	@Test
	@DisplayName("모든 verdict가 유효하면 256-bit token의 10분 session을 digest만 저장한다")
	void issuesTenMinuteSessionAndStoresOnlyDigest() {
		when(decoder.decode("integrity-token")).thenReturn(validVerdict());

		var issued = service.issue("integrity-token", NONCE);

		assertThat(issued.token()).matches("^[A-Za-z0-9_-]{43}$");
		assertThat(issued.scope()).isEqualTo("route:v2:itx");
		assertThat(issued.issuedAt()).isEqualTo(NOW);
		assertThat(issued.expiresAt()).isEqualTo(NOW.plusSeconds(600));
		var session = ArgumentCaptor.forClass(RouteV2Session.class);
		verify(store).claimNonceAndSaveSession(any(), eq(NOW.plusSeconds(120)), eq(NOW), session.capture());
		assertThat(session.getValue().tokenSha256()).matches("^[0-9a-f]{64}$");
		assertThat(session.getValue().tokenSha256()).doesNotContain(issued.token());
		assertThat(session.getValue().requestCount()).isZero();
	}

	@Test
	@DisplayName("requestDetails와 appIntegrity package name을 모두 검증한다")
	void rejectsEitherPackageNameMismatch() {
		assertRejected(verdict("wrong.package", "com.easysubway.app", REQUEST_HASH, NOW));
		assertRejected(verdict("com.easysubway.app", "wrong.package", REQUEST_HASH, NOW));
	}

	@Test
	@DisplayName("certificate·recognition·licensing·device verdict를 exact 정책으로 검증한다")
	void rejectsInvalidAppAndDeviceVerdicts() {
		assertRejected(new PlayIntegrityVerdict(
			"com.easysubway.app", REQUEST_HASH, NOW, "com.easysubway.app",
			"UNRECOGNIZED_VERSION", List.of(CERTIFICATE_DIGEST), "LICENSED", List.of("MEETS_DEVICE_INTEGRITY")
		));
		assertRejected(new PlayIntegrityVerdict(
			"com.easysubway.app", REQUEST_HASH, NOW, "com.easysubway.app",
			"PLAY_RECOGNIZED", List.of("wrong-certificate"), "LICENSED", List.of("MEETS_DEVICE_INTEGRITY")
		));
		assertRejected(new PlayIntegrityVerdict(
			"com.easysubway.app", REQUEST_HASH, NOW, "com.easysubway.app",
			"PLAY_RECOGNIZED", List.of(CERTIFICATE_DIGEST), "UNLICENSED", List.of("MEETS_DEVICE_INTEGRITY")
		));
		assertRejected(new PlayIntegrityVerdict(
			"com.easysubway.app", REQUEST_HASH, NOW, "com.easysubway.app",
			"PLAY_RECOGNIZED", List.of(CERTIFICATE_DIGEST), "LICENSED", List.of("MEETS_BASIC_INTEGRITY")
		));
	}

	@Test
	@DisplayName("2분보다 오래됐거나 미래인 verdict와 다른 requestHash를 거부한다")
	void rejectsStaleFutureOrMismatchedRequest() {
		assertRejected(verdict("com.easysubway.app", "com.easysubway.app", REQUEST_HASH, NOW.minusSeconds(121)));
		assertRejected(verdict("com.easysubway.app", "com.easysubway.app", REQUEST_HASH, NOW.plusMillis(1)));
		assertRejected(verdict("com.easysubway.app", "com.easysubway.app", "wrong-request-hash", NOW));
	}

	@Test
	@DisplayName("누락된 decoded requestHash를 attestation rejection으로 처리한다")
	void rejectsMissingDecodedRequestHash() {
		assertRejected(verdict("com.easysubway.app", "com.easysubway.app", null, NOW));
	}

	@Test
	@DisplayName("정확히 2분 된 verdict는 freshness 경계 안에서 허용한다")
	void acceptsVerdictAtTwoMinuteBoundary() {
		when(decoder.decode("integrity-token")).thenReturn(
			verdict("com.easysubway.app", "com.easysubway.app", REQUEST_HASH, NOW.minusSeconds(120))
		);

		assertThat(service.issue("integrity-token", NONCE).expiresAt()).isEqualTo(NOW.plusSeconds(600));
	}

	@Test
	@DisplayName("128-bit base64url nonce 형식과 2분 replay를 거부한다")
	void rejectsInvalidOrReplayedNonce() {
		when(decoder.decode("integrity-token")).thenReturn(validVerdict());
		assertThatThrownBy(() -> service.issue("integrity-token", "not-128-bit"))
			.isInstanceOf(RouteSessionAttestationRejectedException.class);

		when(store.claimNonceAndSaveSession(any(), eq(NOW.plusSeconds(120)), eq(NOW), any())).thenReturn(false);
		assertThatThrownBy(() -> service.issue("integrity-token", NONCE))
			.isInstanceOf(RouteSessionAttestationRejectedException.class);
	}

	@Test
	@DisplayName("provider 인증·권한·네트워크 장애는 invalid attestation과 구분한다")
	void exposesProviderUnavailabilityWithoutLeakingDetails() {
		when(decoder.decode("integrity-token"))
			.thenThrow(new PlayIntegrityProviderUnavailableException(new IllegalStateException("provider-secret")));

		assertThatThrownBy(() -> service.issue("integrity-token", NONCE))
			.isInstanceOf(RouteSessionAttestationUnavailableException.class)
			.hasMessageNotContaining("provider-secret");
	}

	private void assertRejected(PlayIntegrityVerdict verdict) {
		when(decoder.decode("integrity-token")).thenReturn(verdict);
		assertThatThrownBy(() -> service.issue("integrity-token", NONCE))
			.isInstanceOf(RouteSessionAttestationRejectedException.class);
	}

	private PlayIntegrityVerdict validVerdict() {
		return verdict("com.easysubway.app", "com.easysubway.app", REQUEST_HASH, NOW);
	}

	private PlayIntegrityVerdict verdict(
		String requestPackageName,
		String appPackageName,
		String requestHash,
		Instant timestamp
	) {
		return new PlayIntegrityVerdict(
			requestPackageName,
			requestHash,
			timestamp,
			appPackageName,
			"PLAY_RECOGNIZED",
			List.of(CERTIFICATE_DIGEST),
			"LICENSED",
			List.of("MEETS_DEVICE_INTEGRITY")
		);
	}
}
