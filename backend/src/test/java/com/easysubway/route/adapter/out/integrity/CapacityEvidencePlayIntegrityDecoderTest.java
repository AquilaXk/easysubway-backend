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
}
