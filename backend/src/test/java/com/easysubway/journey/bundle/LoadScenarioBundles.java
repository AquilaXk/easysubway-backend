package com.easysubway.journey.bundle;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 부하·장애 시나리오 서버가 데이터팩 교체를 운영 경로({@link RouteBundleActivationRegistry}의 stage·activate)로 재현하도록
 * 검증이 끝난 후보를 만든다. {@link VerifiedRouteBundleCandidate}의 생성자는 이 패키지 안에서만 열려 있다.
 *
 * <p>팩 이름은 {@code a}·{@code b} 둘뿐이다. 이름 문자를 64번 반복한 값이 manifest sha256이고 시간표 digest이다.
 * 두 팩은 시간표만 다르므로 접근성 digest 등 나머지 식별자는 같다.</p>
 */
public final class LoadScenarioBundles {

	private static final Duration FRESH_BEFORE = Duration.ofHours(1);
	private static final Duration FRESH_AFTER = Duration.ofDays(30);
	private static final Duration VERIFIED_BEFORE = Duration.ofSeconds(1);
	private static final DateTimeFormatter KST_MILLIS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX");

	private LoadScenarioBundles() {
	}

	public static String manifestSha256(String label) {
		return requireLabel(label).repeat(64);
	}

	public static VerifiedRouteBundleCandidate candidate(String label, RouteBundleRuntimeView runtime, Instant now) {
		String marker = requireLabel(label);
		var identity = new RouteBundleIdentity(
			1, "server-route-bundle", "load-bundle-" + marker, 1,
			"0".repeat(64), "1".repeat(64), "2".repeat(64), marker.repeat(64), "4".repeat(64),
			"5".repeat(64), "6".repeat(64), "7".repeat(64),
			"Asia/Seoul", kstMillis(now.minus(FRESH_BEFORE)), kstMillis(now.plus(FRESH_AFTER)),
			new RouteBundleIdentity.SchemaCompatibility(3, 3),
			"load-key", new RouteBundleIdentity.Signature("rsa-sha256-server-route-bundle-v1", "AQID"));
		var evidence = new RouteBundleAdmissionEvidence(
			manifestSha256(marker), "final-" + marker, "promotion-" + marker, "receipt-" + marker, "activation-" + marker);
		return new VerifiedRouteBundleCandidate(
			identity, evidence, RouteBundleServingEvidence.unobservable(), runtime, now.minus(VERIFIED_BEFORE));
	}

	private static String requireLabel(String label) {
		if (!"a".equals(label) && !"b".equals(label)) throw new IllegalArgumentException("bundle label must be a or b");
		return label;
	}

	private static String kstMillis(Instant instant) {
		return KST_MILLIS.format(instant.atOffset(ZoneOffset.ofHours(9)));
	}
}
