package com.easysubway.route.adapter.out.integrity;

import com.easysubway.route.application.port.out.PlayIntegrityDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("capacity-evidence")
final class CapacityEvidencePlayIntegrityDecoder implements PlayIntegrityDecoder {

	private static final String PACKAGE_NAME = "com.easysubway.app";
	private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();
	private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();

	private final byte[] attestationKey;
	private final String certificateDigest;
	private final Clock clock;

	@Autowired
	CapacityEvidencePlayIntegrityDecoder(
		@Value("${easysubway.route-v2.capacity-evidence.attestation-key:}") String attestationKey,
		@Value("${easysubway.route-v2.play-integrity.certificate-sha256:}") String certificateDigest
	) {
		this(attestationKey, certificateDigest, Clock.systemUTC());
	}

	CapacityEvidencePlayIntegrityDecoder(String attestationKey, String certificateDigest, Clock clock) {
		if (attestationKey == null || !attestationKey.matches("^[0-9a-f]{64}$")) {
			throw new IllegalStateException("capacity evidence attestation key is invalid");
		}
		this.attestationKey = HexFormat.of().parseHex(attestationKey);
		this.certificateDigest = certificateDigest;
		this.clock = clock;
	}

	@Override
	public PlayIntegrityVerdict decode(String integrityToken) {
		try {
			String[] parts = integrityToken.split("\\.", -1);
			if (parts.length != 2 || !parts[0].matches("^[A-Za-z0-9_-]{22}$")
				|| !MessageDigest.isEqual(hmac(parts[0]), BASE64_URL_DECODER.decode(parts[1]))) {
				return rejected();
			}
			return new PlayIntegrityVerdict(
				PACKAGE_NAME,
				requestHash(parts[0]),
				clock.instant(),
				PACKAGE_NAME,
				"PLAY_RECOGNIZED",
				List.of(certificateDigest),
				"LICENSED",
				List.of("MEETS_DEVICE_INTEGRITY")
			);
		} catch (IllegalArgumentException exception) {
			return rejected();
		}
	}

	private byte[] hmac(String nonce) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(attestationKey, "HmacSHA256"));
			return mac.doFinal(nonce.getBytes(StandardCharsets.UTF_8));
		} catch (Exception exception) {
			throw new IllegalStateException("HmacSHA256 is unavailable", exception);
		}
	}

	private static String requestHash(String nonce) {
		try {
			String canonical = "{\"clientNonce\":\"" + nonce + "\",\"purpose\":\"route:v2:itx\",\"version\":1}";
			return BASE64_URL.encodeToString(MessageDigest.getInstance("SHA-256")
				.digest(canonical.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static PlayIntegrityVerdict rejected() {
		return new PlayIntegrityVerdict(null, null, null, null, null, List.of(), null, List.of());
	}
}
