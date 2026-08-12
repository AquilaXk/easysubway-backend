package com.easysubway.route.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.route.application.port.out.PlayIntegrityDecoder.PlayIntegrityVerdict;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Play Integrity verdict immutable list boundary")
class PlayIntegrityVerdictDefensiveCopyTest {

	@Test
	@DisplayName("constructor snapshots mutable integrity decision lists")
	void snapshotsMutableListInputs() {
		var certificateDigests = new ArrayList<>(List.of("certificate-digest"));
		var deviceVerdicts = new ArrayList<>(List.of("MEETS_DEVICE_INTEGRITY"));
		var verdict = verdict(certificateDigests, deviceVerdicts);

		certificateDigests.clear();
		deviceVerdicts.clear();

		assertThat(verdict.certificateSha256Digests()).containsExactly("certificate-digest");
		assertThat(verdict.deviceRecognitionVerdicts()).containsExactly("MEETS_DEVICE_INTEGRITY");
	}

	@Test
	@DisplayName("integrity decision list accessors are immutable")
	void exposesImmutableLists() {
		var verdict = verdict(
			new ArrayList<>(List.of("certificate-digest")),
			new ArrayList<>(List.of("MEETS_DEVICE_INTEGRITY"))
		);

		assertThatThrownBy(() -> verdict.certificateSha256Digests().clear())
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> verdict.deviceRecognitionVerdicts().clear())
			.isInstanceOf(UnsupportedOperationException.class);
	}

	private static PlayIntegrityVerdict verdict(
		List<String> certificateDigests,
		List<String> deviceVerdicts
	) {
		return new PlayIntegrityVerdict(
			"com.easysubway",
			"request-hash",
			Instant.parse("2026-08-13T00:00:00Z"),
			"com.easysubway",
			"PLAY_RECOGNIZED",
			certificateDigests,
			"LICENSED",
			deviceVerdicts
		);
	}
}
