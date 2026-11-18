package com.easysubway.journey.adapter.out.integrity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.journey.application.JourneySessionIntegrityPort.ProviderUnavailableException;
import com.easysubway.route.application.port.out.PlayIntegrityDecoder;
import com.easysubway.route.application.port.out.PlayIntegrityProviderUnavailableException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Journey V3 Play Integrity adapter")
class JourneyPlayIntegrityAdapterTest {

	@Test
	@DisplayName("bounded provider verdict를 한 번만 exact 변환하고 provider 장애를 보존한다")
	void mapsVerdictAndProviderUnavailability() {
		var calls = new AtomicInteger();
		var adapter = new JourneyPlayIntegrityAdapter(token -> {
			calls.incrementAndGet();
			return new PlayIntegrityDecoder.PlayIntegrityVerdict(
				"com.easysubway.app",
				"request-hash",
				Instant.parse("2026-08-12T00:00:00Z"),
				"com.easysubway.app",
				"PLAY_RECOGNIZED",
				List.of("certificate-digest"),
				"LICENSED",
				List.of("MEETS_DEVICE_INTEGRITY")
			);
		});

		var verdict = adapter.decode("integrity-token");

		assertThat(calls).hasValue(1);
		assertThat(verdict.requestPackageName()).isEqualTo("com.easysubway.app");
		assertThat(verdict.requestHash()).isEqualTo("request-hash");
		assertThat(verdict.requestTimestamp()).isEqualTo("2026-08-12T00:00:00Z");
		assertThat(verdict.appPackageName()).isEqualTo("com.easysubway.app");
		assertThat(verdict.appRecognitionVerdict()).isEqualTo("PLAY_RECOGNIZED");
		assertThat(verdict.certificateSha256Digests()).containsExactly("certificate-digest");
		assertThat(verdict.appLicensingVerdict()).isEqualTo("LICENSED");
		assertThat(verdict.deviceRecognitionVerdicts()).containsExactly("MEETS_DEVICE_INTEGRITY");

		var unavailable = new JourneyPlayIntegrityAdapter(token -> {
			throw new PlayIntegrityProviderUnavailableException(new IllegalStateException("provider down"));
		});
		assertThatThrownBy(() -> unavailable.decode("integrity-token"))
			.isInstanceOf(ProviderUnavailableException.class)
			.hasCauseInstanceOf(PlayIntegrityProviderUnavailableException.class);
	}
}
