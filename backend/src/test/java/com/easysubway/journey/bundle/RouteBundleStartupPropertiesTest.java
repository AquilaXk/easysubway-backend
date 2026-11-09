package com.easysubway.journey.bundle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
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
	private static final String NEXT_PUBLIC_KEY_PEM = """
		-----BEGIN PUBLIC KEY-----
		MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA0zmSgGBKMdUDlRBxxj+K
		jX+cDPXbvKEq7ax/WwTHthf4Bf+eF3MC8/wBSFF5bDRYVYx6QnH0pi+zgZGB9qLG
		YJ1yv9v8q65wXL5MAzHJ47BqdeE/Sl10HNLLVleSKhMpTs4FD9beTF60zwa370sM
		lGGuxyCALaGzFudlATQgqkKh9lubB6+Sae94rGwW+mRYILuRMZ5QGpsrGLdx94ED
		tEjDSshXKy7sBbXfwjxQaJunf9+IujRWfMf/KMib4AQ3bQN1XGPaNuG7quc+ikcL
		kRDg51f/sDHgKYxJeKsKkM6Ip4jwE9AMLeaNrJ50dvjUpPyahBEPJPeIyC/nYSOs
		NQIDAQAB
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
		assertNull(properties.nextKeyId());
		assertNull(properties.nextPublicKeyPem());
		assertEquals(
			List.of(new RouteBundleCurrentKeyVerifier.CurrentKey("launch-2026", VALID_PUBLIC_KEY_PEM)),
			properties.trustedKeys());

		var blankNext = new RouteBundleStartupProperties(
			DESCRIPTOR_BASE64,
			"sha256:" + "e".repeat(64),
			"https://objects.example.com",
			"launch-2026",
			VALID_PUBLIC_KEY_PEM,
			"",
			"   ");
		assertNull(blankNext.nextKeyId());
		assertNull(blankNext.nextPublicKeyPem());
		assertEquals(
			List.of(new RouteBundleCurrentKeyVerifier.CurrentKey("launch-2026", VALID_PUBLIC_KEY_PEM)),
			blankNext.trustedKeys());
	}

	@Test
	@DisplayName("차기 서명 키가 함께 설정되면 primary와 next 순서로 trustedKeys를 반환한다")
	void returnsPrimaryAndNextTrustedKeysInOrder() {
		var properties = new RouteBundleStartupProperties(
			DESCRIPTOR_BASE64,
			"sha256:" + "e".repeat(64),
			"https://objects.example.com",
			"launch-2026",
			VALID_PUBLIC_KEY_PEM,
			"launch-2027",
			NEXT_PUBLIC_KEY_PEM);

		assertEquals("launch-2027", properties.nextKeyId());
		assertEquals(NEXT_PUBLIC_KEY_PEM, properties.nextPublicKeyPem());
		assertEquals(
			List.of(
				new RouteBundleCurrentKeyVerifier.CurrentKey("launch-2026", VALID_PUBLIC_KEY_PEM),
				new RouteBundleCurrentKeyVerifier.CurrentKey("launch-2027", NEXT_PUBLIC_KEY_PEM)),
			properties.trustedKeys());
		assertThrows(UnsupportedOperationException.class, () -> properties.trustedKeys().clear());
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

	@Test
	@DisplayName("부분 설정·중복 keyId·잘못된 형식의 차기 서명 키를 거부한다")
	void rejectsPartialDuplicateOrInvalidNextKey() {
		assertThrows(IllegalArgumentException.class, () -> new RouteBundleStartupProperties(
			DESCRIPTOR_BASE64, "activation", "https://objects.example.com", "launch-2026", VALID_PUBLIC_KEY_PEM,
			"launch-2027", null));
		assertThrows(IllegalArgumentException.class, () -> new RouteBundleStartupProperties(
			DESCRIPTOR_BASE64, "activation", "https://objects.example.com", "launch-2026", VALID_PUBLIC_KEY_PEM,
			"launch-2027", " "));
		assertThrows(IllegalArgumentException.class, () -> new RouteBundleStartupProperties(
			DESCRIPTOR_BASE64, "activation", "https://objects.example.com", "launch-2026", VALID_PUBLIC_KEY_PEM,
			null, NEXT_PUBLIC_KEY_PEM));
		assertThrows(IllegalArgumentException.class, () -> new RouteBundleStartupProperties(
			DESCRIPTOR_BASE64, "activation", "https://objects.example.com", "launch-2026", VALID_PUBLIC_KEY_PEM,
			"", NEXT_PUBLIC_KEY_PEM));
		assertThrows(IllegalArgumentException.class, () -> new RouteBundleStartupProperties(
			DESCRIPTOR_BASE64, "activation", "https://objects.example.com", "launch-2026", VALID_PUBLIC_KEY_PEM,
			"launch-2026", NEXT_PUBLIC_KEY_PEM));
		assertThrows(IllegalArgumentException.class, () -> new RouteBundleStartupProperties(
			DESCRIPTOR_BASE64, "activation", "https://objects.example.com", "launch-2026", VALID_PUBLIC_KEY_PEM,
			"next key", NEXT_PUBLIC_KEY_PEM));
		assertThrows(IllegalArgumentException.class, () -> new RouteBundleStartupProperties(
			DESCRIPTOR_BASE64, "activation", "https://objects.example.com", "launch-2026", VALID_PUBLIC_KEY_PEM,
			"launch-2027", "synthetic-next-public-key"));
		assertThrows(IllegalArgumentException.class, () -> new RouteBundleStartupProperties(
			DESCRIPTOR_BASE64, "activation", "https://objects.example.com", "launch-2026", VALID_PUBLIC_KEY_PEM,
			"launch-2027", NEXT_PUBLIC_KEY_PEM + " "));
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
