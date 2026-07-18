package com.easysubway.route.adapter.out.integrity;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Capacity evidence Play Integrity decode adapter")
class CapacityEvidencePlayIntegrityDecoderTest {

	@Test
	@DisplayName("capacity evidence profile은 HMAC으로 통제된 verdict만 발급한다")
	void decodesCapacityEvidenceToken() throws Exception {
		String key = "ab".repeat(32);
		String nonce = "AAAAAAAAAAAAAAAAAAAAAA";
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(java.util.HexFormat.of().parseHex(key), "HmacSHA256"));
		String signature = Base64.getUrlEncoder().withoutPadding()
			.encodeToString(mac.doFinal(nonce.getBytes(StandardCharsets.UTF_8)));
		var decoder = new CapacityEvidencePlayIntegrityDecoder(
			key,
			"A".repeat(43),
			Clock.fixed(Instant.parse("2026-07-17T03:00:00Z"), ZoneOffset.UTC)
		);

		assertThat(decoder.decode(nonce + "." + signature).appRecognitionVerdict()).isEqualTo("PLAY_RECOGNIZED");
		assertThat(decoder.decode(nonce + ".invalid").requestPackageName()).isNull();
	}

	@Test
	@DisplayName("verdict의 requestTimestamp는 caller now보다 TIMESTAMP_SAFETY_MARGIN만큼 과거다")
	void verdictTimestampIsBackdatedBySafetyMargin() throws Exception {
		// RouteV2SessionService.issue()는 decoder.decode() 호출 전에 자신의 now를
		// 캡처한다. verdict의 requestTimestamp가 그 now보다 뒤처지면(과거가 아니면)
		// isAfter(now)가 참이 되어 매 세션이 무조건 거부된다(#2095 run 29639318566).
		// 이 테스트는 그 불변식을 fixed Clock으로 정확히 고정한다 — margin이 0으로
		// 되돌아가거나 .plus로 뒤집혀도 여기서 즉시 실패해야 한다.
		String key = "cd".repeat(32);
		String nonce = "BBBBBBBBBBBBBBBBBBBBBB";
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(java.util.HexFormat.of().parseHex(key), "HmacSHA256"));
		String signature = Base64.getUrlEncoder().withoutPadding()
			.encodeToString(mac.doFinal(nonce.getBytes(StandardCharsets.UTF_8)));
		Instant fixedNow = Instant.parse("2026-07-17T03:00:00Z");
		var decoder = new CapacityEvidencePlayIntegrityDecoder(
			key,
			"B".repeat(43),
			Clock.fixed(fixedNow, ZoneOffset.UTC)
		);

		Instant requestTimestamp = decoder.decode(nonce + "." + signature).requestTimestamp();

		assertThat(requestTimestamp).isEqualTo(fixedNow.minus(CapacityEvidencePlayIntegrityDecoder.TIMESTAMP_SAFETY_MARGIN));
		assertThat(requestTimestamp.isAfter(fixedNow)).isFalse();
	}
}
