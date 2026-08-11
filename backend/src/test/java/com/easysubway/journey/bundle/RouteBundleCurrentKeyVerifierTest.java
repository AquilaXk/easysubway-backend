package com.easysubway.journey.bundle;

import static com.easysubway.journey.bundle.RouteBundleCurrentKeyVerifier.Reason.CURRENT_KEY_CONFIG_INVALID;
import static com.easysubway.journey.bundle.RouteBundleCurrentKeyVerifier.Reason.CURRENT_KEY_ID_MISMATCH;
import static com.easysubway.journey.bundle.RouteBundleCurrentKeyVerifier.Reason.MANIFEST_SIGNATURE_INVALID;
import static com.easysubway.journey.bundle.RouteBundleCurrentKeyVerifier.Reason.SIGNING_INPUT_IDENTITY_MISMATCH;
import static com.easysubway.journey.bundle.RouteBundleCurrentKeyVerifier.Reason.SIGNING_INPUT_INVALID;
import static com.easysubway.journey.bundle.RouteBundleHandoffException.Reason.ACTIVATION_REQUEST_IDENTITY_INVALID;
import static com.easysubway.journey.bundle.RouteBundleHandoffException.Reason.HANDOFF_CANONICAL_BYTES_MISMATCH;
import static com.easysubway.journey.bundle.RouteBundleHandoffException.Reason.HANDOFF_UTF8_OR_JSON_INVALID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class RouteBundleCurrentKeyVerifierTest {

	private static final String ACTIVATION_REQUEST = "sha256:" + "e".repeat(64);

	@Test
	void verifiesExactCurrentKeyAndSigningInputWithoutIssuingACandidate() throws Exception {
		KeyPair keyPair = rsaKeyPair();
		var fixture = signedFixture(keyPair, "launch-2026");
		var currentKey = new RouteBundleCurrentKeyVerifier.CurrentKey(
			"launch-2026", pem(keyPair.getPublic()));

		var verified = RouteBundleCurrentKeyVerifier.verify(
			fixture.bytes(), ACTIVATION_REQUEST, fixture.signingInputBytes(), currentKey);

		assertEquals(fixture.handoffSha256(), verified.handoff().handoffSha256());
		assertEquals("launch-2026", verified.keyId());
		assertEquals("rsa-sha256-server-route-bundle-v1", verified.algorithm());
		assertEquals(sha256(keyPair.getPublic().getEncoded()), verified.publicKeySha256());
		assertEquals(sha256(fixture.signingInputBytes()), verified.signingInputSha256());
	}

	@Test
	void parsesRawHandoffBeforeRejectingMissingEmptyOrTamperedSigningInput() throws Exception {
		KeyPair keyPair = rsaKeyPair();
		var fixture = signedFixture(keyPair, "launch-2026");
		var currentKey = new RouteBundleCurrentKeyVerifier.CurrentKey(
			"launch-2026", pem(keyPair.getPublic()));

		assertReason(SIGNING_INPUT_INVALID,
			() -> RouteBundleCurrentKeyVerifier.verify(
				fixture.bytes(), ACTIVATION_REQUEST, null, currentKey));
		assertReason(SIGNING_INPUT_INVALID,
			() -> RouteBundleCurrentKeyVerifier.verify(
				fixture.bytes(), ACTIVATION_REQUEST, new byte[0], currentKey));
		assertReason(SIGNING_INPUT_IDENTITY_MISMATCH,
			() -> RouteBundleCurrentKeyVerifier.verify(
				fixture.bytes(), ACTIVATION_REQUEST, "{}".getBytes(StandardCharsets.UTF_8), currentKey));
		assertHandoffReason(HANDOFF_UTF8_OR_JSON_INVALID,
			() -> RouteBundleCurrentKeyVerifier.verify(
				null, ACTIVATION_REQUEST, fixture.signingInputBytes(), currentKey));
		assertHandoffReason(HANDOFF_CANONICAL_BYTES_MISMATCH,
			() -> RouteBundleCurrentKeyVerifier.verify(
				(new String(fixture.bytes(), StandardCharsets.UTF_8) + "\n").getBytes(StandardCharsets.UTF_8),
				ACTIVATION_REQUEST,
				fixture.signingInputBytes(),
				currentKey));
		assertHandoffReason(ACTIVATION_REQUEST_IDENTITY_INVALID,
			() -> RouteBundleCurrentKeyVerifier.verify(
				fixture.bytes(), " activation", fixture.signingInputBytes(), currentKey));
	}

	@Test
	void rejectsWrongMalformedOrNonRsaCurrentKeyWithoutTryingAnAlternate() throws Exception {
		KeyPair signingKey = rsaKeyPair();
		var fixture = signedFixture(signingKey, "launch-2026");

		assertReason(CURRENT_KEY_ID_MISMATCH,
			() -> RouteBundleCurrentKeyVerifier.verify(
				fixture.bytes(), ACTIVATION_REQUEST, fixture.signingInputBytes(),
				new RouteBundleCurrentKeyVerifier.CurrentKey("old-key", pem(signingKey.getPublic()))));
		assertReason(CURRENT_KEY_ID_MISMATCH,
			() -> RouteBundleCurrentKeyVerifier.verify(
				fixture.bytes(), ACTIVATION_REQUEST, fixture.signingInputBytes(),
				new RouteBundleCurrentKeyVerifier.CurrentKey("launch-2026 ", pem(signingKey.getPublic()))));
		assertReason(CURRENT_KEY_CONFIG_INVALID,
			() -> RouteBundleCurrentKeyVerifier.verify(
				fixture.bytes(), ACTIVATION_REQUEST, fixture.signingInputBytes(),
				new RouteBundleCurrentKeyVerifier.CurrentKey("launch-2026", "not-a-public-key")));
		assertReason(CURRENT_KEY_CONFIG_INVALID,
			() -> RouteBundleCurrentKeyVerifier.verify(
				fixture.bytes(), ACTIVATION_REQUEST, fixture.signingInputBytes(),
				new RouteBundleCurrentKeyVerifier.CurrentKey(
					"launch-2026", " " + pem(signingKey.getPublic()))));
		assertReason(CURRENT_KEY_CONFIG_INVALID,
			() -> RouteBundleCurrentKeyVerifier.verify(
				fixture.bytes(), ACTIVATION_REQUEST, fixture.signingInputBytes(),
				new RouteBundleCurrentKeyVerifier.CurrentKey(
					"launch-2026", pem(signingKey.getPublic()) + "\n")));

		KeyPairGenerator ecGenerator = KeyPairGenerator.getInstance("EC");
		ecGenerator.initialize(256);
		assertReason(CURRENT_KEY_CONFIG_INVALID,
			() -> RouteBundleCurrentKeyVerifier.verify(
				fixture.bytes(), ACTIVATION_REQUEST, fixture.signingInputBytes(),
				new RouteBundleCurrentKeyVerifier.CurrentKey(
					"launch-2026", pem(ecGenerator.generateKeyPair().getPublic()))));
	}

	@Test
	void rejectsFalseOrMalformedSignatureAndAcceptsLiteralNewlineTransport() throws Exception {
		KeyPair signingKey = rsaKeyPair();
		KeyPair otherKey = rsaKeyPair();
		var unsigned = RouteBundleConsumerHandoffParserTest.fixture("launch-2026", "AQID");
		var wrongSignature = RouteBundleConsumerHandoffParserTest.fixture(
			"launch-2026", sign(otherKey.getPrivate(), unsigned.signingInputBytes()));
		var currentKey = new RouteBundleCurrentKeyVerifier.CurrentKey(
			"launch-2026", pem(signingKey.getPublic()));

		assertReason(MANIFEST_SIGNATURE_INVALID,
			() -> RouteBundleCurrentKeyVerifier.verify(
				wrongSignature.bytes(), ACTIVATION_REQUEST, wrongSignature.signingInputBytes(), currentKey));
		var malformedSignature = RouteBundleConsumerHandoffParserTest.fixture("launch-2026", "AA");
		assertReason(MANIFEST_SIGNATURE_INVALID,
			() -> RouteBundleCurrentKeyVerifier.verify(
				malformedSignature.bytes(), ACTIVATION_REQUEST,
				malformedSignature.signingInputBytes(), currentKey));

		var valid = signedFixture(signingKey, "launch-2026");
		var escapedNewlines = new RouteBundleCurrentKeyVerifier.CurrentKey(
			"launch-2026", pem(signingKey.getPublic()).replace("\n", "\\n"));
		assertEquals(
			sha256(signingKey.getPublic().getEncoded()),
			RouteBundleCurrentKeyVerifier.verify(
				valid.bytes(), ACTIVATION_REQUEST, valid.signingInputBytes(), escapedNewlines)
				.publicKeySha256());
	}

	@Test
	void rejectsStaleSignedBytesAfterManifestAndUnkeyedEvidenceAreRebound() throws Exception {
		KeyPair signingKey = rsaKeyPair();
		var original = RouteBundleConsumerHandoffParserTest.fixture("launch-2026", "AQID");
		String staleSignature = sign(signingKey.getPrivate(), original.signingInputBytes());
		var rebound = RouteBundleConsumerHandoffParserTest.fixture("launch-2026", staleSignature, 8);
		var currentKey = new RouteBundleCurrentKeyVerifier.CurrentKey(
			"launch-2026", pem(signingKey.getPublic()));

		assertReason(SIGNING_INPUT_IDENTITY_MISMATCH,
			() -> RouteBundleCurrentKeyVerifier.verify(
				rebound.bytes(), ACTIVATION_REQUEST, original.signingInputBytes(), currentKey));
	}

	private static RouteBundleConsumerHandoffParserTest.Fixture signedFixture(
		KeyPair keyPair,
		String keyId) throws Exception {
		var unsigned = RouteBundleConsumerHandoffParserTest.fixture(keyId, "AQID");
		return RouteBundleConsumerHandoffParserTest.fixture(
			keyId, sign(keyPair.getPrivate(), unsigned.signingInputBytes()));
	}

	private static KeyPair rsaKeyPair() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		return generator.generateKeyPair();
	}

	private static String sign(PrivateKey privateKey, byte[] value) throws Exception {
		Signature signer = Signature.getInstance("SHA256withRSA");
		signer.initSign(privateKey);
		signer.update(value);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
	}

	private static String pem(PublicKey publicKey) {
		return "-----BEGIN PUBLIC KEY-----\n"
			+ Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(publicKey.getEncoded())
			+ "\n-----END PUBLIC KEY-----";
	}

	private static String sha256(byte[] value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
		} catch (Exception impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	private static void assertReason(
		RouteBundleCurrentKeyVerifier.Reason expected,
		org.junit.jupiter.api.function.Executable executable) {
		var failure = assertThrows(RouteBundleCurrentKeyVerifier.VerificationException.class, executable);
		assertEquals(expected, failure.reason());
	}

	private static void assertHandoffReason(
		RouteBundleHandoffException.Reason expected,
		org.junit.jupiter.api.function.Executable executable) {
		var failure = assertThrows(RouteBundleHandoffException.class, executable);
		assertEquals(expected, failure.reason());
	}
}
