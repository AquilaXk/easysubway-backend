package com.easysubway.journey.bundle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Route bundle startup 설정")
class RouteBundleStartupPropertiesTest {

	private static final byte[] DESCRIPTOR = "{}".getBytes(StandardCharsets.UTF_8);
	private static final String DESCRIPTOR_BASE64 = Base64.getEncoder().encodeToString(DESCRIPTOR);
	private static final String VALID_PUBLIC_KEY_PEM = """
		-----BEGIN PUBLIC KEY-----
		MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAtfRwZiXfeTubXwMUsnZ5
		e1exey2YoolJVU5LsmAaOuF/3umllVeK37fLxxRZdqLd6mwvbDKPZJv1mDklRtjK
		tMJAwfZ69oH3dTD/CSYtBN2mO/KPet6Ui4gLZua4MZy5HqMdNCVDj6Z4QwQptdR6
		AXqhwjj/fBFQCc/ONmWGCoZ76FGlxCbpbobhaJ/gWzjwAE8M20jalUewh9Yh/xHd
		hmc5+ufKoZ/OFwOGlyLP1N06k4yxQa49jJTM30w7N8KyyyBRXS1Sz2Ubmwf8EFZA
		FdCGzzwpSjEVrLth3kGrx8XgpddzBqIRmSH3s+WqpN+mXPbp2EYhaVlc0oHwSb5X
		sQIDAQAB
		-----END PUBLIC KEY-----""";

	@Test
	@DisplayName("canonical descriptor bytes와 immutable startup identity를 보존한다")
	void preservesCanonicalDescriptorAndStartupIdentity() {
		var properties = properties(DESCRIPTOR_BASE64);

		byte[] firstRead = properties.descriptorBytes();
		firstRead[0] = '!';

		assertArrayEquals(DESCRIPTOR, properties.descriptorBytes());
		assertEquals("sha256:" + "e".repeat(64), properties.activationRequestIdentity());
		assertEquals("https://objects.example.com", properties.trustedRawDescriptorBaseUrl());
		assertEquals("launch-2026", properties.currentKeyId());
		assertEquals(VALID_PUBLIC_KEY_PEM, properties.currentPublicKeyPem());
	}

	@Test
	@DisplayName("noncanonical·empty·oversized descriptor Base64를 거부한다")
	void rejectsInvalidDescriptorBase64() {
		assertThrows(IllegalArgumentException.class, () -> properties(null));
		assertThrows(IllegalArgumentException.class, () -> properties(""));
		assertThrows(IllegalArgumentException.class, () -> properties("e30"));
		assertThrows(IllegalArgumentException.class, () -> properties("e30=\n"));
		assertThrows(IllegalArgumentException.class, () -> properties("***="));
		assertThrows(IllegalArgumentException.class, () -> properties(
			Base64.getEncoder().encodeToString(new byte[1024 * 1024 + 1])));
	}

	@Test
	@DisplayName("missing·trimmed·unbounded required identity를 거부한다")
	void rejectsInvalidRequiredIdentity() {
		assertThrows(IllegalArgumentException.class, () -> new RouteBundleStartupProperties(
			DESCRIPTOR_BASE64, " ", "https://objects.example.com", "launch-2026", VALID_PUBLIC_KEY_PEM));
		assertThrows(IllegalArgumentException.class, () -> new RouteBundleStartupProperties(
			DESCRIPTOR_BASE64, "activation\nidentity", "https://objects.example.com", "launch-2026", VALID_PUBLIC_KEY_PEM));
		assertThrows(IllegalArgumentException.class, () -> new RouteBundleStartupProperties(
			DESCRIPTOR_BASE64, "activation", "", "launch-2026", VALID_PUBLIC_KEY_PEM));
		assertThrows(IllegalArgumentException.class, () -> new RouteBundleStartupProperties(
			DESCRIPTOR_BASE64, "activation", "https://objects.example.com", "old key", VALID_PUBLIC_KEY_PEM));
		assertThrows(IllegalArgumentException.class, () -> new RouteBundleStartupProperties(
			DESCRIPTOR_BASE64, "activation", "https://objects.example.com", "launch-2026", " "));
		assertThrows(IllegalArgumentException.class, () -> new RouteBundleStartupProperties(
			DESCRIPTOR_BASE64, "activation", "https://objects.example.com", "launch-2026", "synthetic-public-key"));
	}

	private static RouteBundleStartupProperties properties(String descriptorBase64) {
		return new RouteBundleStartupProperties(
			descriptorBase64,
			"sha256:" + "e".repeat(64),
			"https://objects.example.com",
			"launch-2026",
			VALID_PUBLIC_KEY_PEM);
	}
}
