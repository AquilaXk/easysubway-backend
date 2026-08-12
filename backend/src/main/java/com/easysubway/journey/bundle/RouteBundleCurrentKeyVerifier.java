package com.easysubway.journey.bundle;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Verifies one handoff against one configured current key without issuing a candidate. */
public final class RouteBundleCurrentKeyVerifier {

	private static final String SIGNING_INPUT_PATH = "manifest.signing-input.json";
	private static final String SIGNATURE_ALGORITHM = "rsa-sha256-server-route-bundle-v1";
	private static final String JCA_SIGNATURE_ALGORITHM = "SHA256withRSA";
	private static final String PEM_HEADER = "-----BEGIN PUBLIC KEY-----";
	private static final String PEM_FOOTER = "-----END PUBLIC KEY-----";
	private static final Pattern PEM_BODY = Pattern.compile("[A-Za-z0-9+/]+={0,2}");

	private RouteBundleCurrentKeyVerifier() {
	}

	public static VerifiedSignature verify(
		byte[] handoffBytes,
		String activationRequestIdentity,
		byte[] signingInputBytes,
		CurrentKey currentKey) {
		RouteBundleConsumerHandoff handoff = RouteBundleConsumerHandoffParser.parse(
			handoffBytes, activationRequestIdentity);
		VerificationResult result = verifyParsed(
			handoff.identity(),
			handoff.objects(),
			RouteBundleConsumerHandoff.PublishedObject::path,
			RouteBundleConsumerHandoff.PublishedObject::sha256,
			signingInputBytes,
			currentKey);

		return new VerifiedSignature(
			handoff,
			result.keyId(),
			result.algorithm(),
			result.publicKeySha256(),
			result.signingInputSha256());
	}

	public static VerifiedPublicationDescriptorSignature verifyPublicationDescriptor(
		byte[] descriptorBytes,
		String activationRequestIdentity,
		byte[] signingInputBytes,
		CurrentKey currentKey) {
		RouteBundlePublicationDescriptor descriptor =
			RouteBundleConsumerHandoffParser.parsePublicationDescriptor(
				descriptorBytes, activationRequestIdentity);
		VerificationResult result = verifyParsed(
			descriptor.identity(),
			descriptor.objects(),
			RouteBundlePublicationDescriptor.PublishedObject::path,
			RouteBundlePublicationDescriptor.PublishedObject::sha256,
			signingInputBytes,
			currentKey);

		return new VerifiedPublicationDescriptorSignature(
			descriptor,
			result.keyId(),
			result.algorithm(),
			result.publicKeySha256(),
			result.signingInputSha256());
	}

	private static <T> VerificationResult verifyParsed(
		RouteBundleIdentity identity,
		List<T> objects,
		Function<T, String> path,
		Function<T, String> digest,
		byte[] signingInputBytes,
		CurrentKey currentKey) {
		if (signingInputBytes == null || signingInputBytes.length == 0) {
			throw failure(Reason.SIGNING_INPUT_INVALID, "manifest signing input bytes are required");
		}

		String signingInputSha256 = sha256(signingInputBytes);
		long matchingObjects = objects.stream()
			.filter(object -> SIGNING_INPUT_PATH.equals(path.apply(object)))
			.count();
		if (matchingObjects != 1) {
			throw failure(
				Reason.SIGNING_INPUT_IDENTITY_MISMATCH,
				"handoff must contain exactly one manifest signing input object");
		}
		String expectedSigningInputSha256 = objects.stream()
			.filter(object -> SIGNING_INPUT_PATH.equals(path.apply(object)))
			.map(digest)
			.findFirst()
			.orElseThrow();
		if (!signingInputSha256.equals(expectedSigningInputSha256)) {
			throw failure(
				Reason.SIGNING_INPUT_IDENTITY_MISMATCH,
				"manifest signing input digest does not match the handoff object");
		}

		if (currentKey == null
			|| currentKey.keyId() == null
			|| currentKey.keyId().isEmpty()
			|| !currentKey.keyId().equals(currentKey.keyId().strip())
			|| !currentKey.keyId().equals(identity.keyId())) {
			throw failure(Reason.CURRENT_KEY_ID_MISMATCH, "configured current key ID does not match manifest keyId");
		}

		PublicKey publicKey = parsePublicKey(currentKey.publicKeyPem());
		String algorithm = identity.signature().algorithm();
		if (!SIGNATURE_ALGORITHM.equals(algorithm)) {
			throw failure(Reason.MANIFEST_SIGNATURE_INVALID, "manifest signature algorithm is not current");
		}
		verifySignature(publicKey, signingInputBytes, identity.signature().value());

		return new VerificationResult(
			currentKey.keyId(),
			algorithm,
			sha256(publicKey.getEncoded()),
			signingInputSha256);
	}

	private static PublicKey parsePublicKey(String publicKeyPem) {
		if (publicKeyPem == null || publicKeyPem.isBlank()) {
			throw failure(Reason.CURRENT_KEY_CONFIG_INVALID, "current public key PEM is required");
		}
		String normalized = publicKeyPem
			.replace("\\n", "\n")
			.replace("\r\n", "\n");
		if (!normalized.startsWith(PEM_HEADER + "\n")
			|| !normalized.endsWith("\n" + PEM_FOOTER)) {
			throw failure(Reason.CURRENT_KEY_CONFIG_INVALID, "current public key must be an exact public-key PEM");
		}
		String body = normalized.substring(
			PEM_HEADER.length() + 1,
			normalized.length() - PEM_FOOTER.length() - 1);
		String compact = body.replace("\n", "");
		if (compact.isEmpty()
			|| !PEM_BODY.matcher(compact).matches()
			|| body.startsWith("\n")
			|| body.endsWith("\n")
			|| body.contains("\n\n")) {
			throw failure(Reason.CURRENT_KEY_CONFIG_INVALID, "current public key PEM body is invalid");
		}
		try {
			byte[] encoded = Base64.getDecoder().decode(compact.getBytes(StandardCharsets.US_ASCII));
			PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
			if (!"RSA".equals(publicKey.getAlgorithm())) {
				throw failure(Reason.CURRENT_KEY_CONFIG_INVALID, "current public key must be RSA");
			}
			return publicKey;
		} catch (VerificationException exception) {
			throw exception;
		} catch (java.security.GeneralSecurityException | IllegalArgumentException exception) {
			throw failure(Reason.CURRENT_KEY_CONFIG_INVALID, "current public key is malformed", exception);
		}
	}

	private static void verifySignature(PublicKey publicKey, byte[] signingInputBytes, String signatureValue) {
		try {
			Signature verifier = Signature.getInstance(JCA_SIGNATURE_ALGORITHM);
			verifier.initVerify(publicKey);
			verifier.update(signingInputBytes);
			byte[] signatureBytes = Base64.getUrlDecoder().decode(signatureValue);
			if (!verifier.verify(signatureBytes)) {
				throw failure(Reason.MANIFEST_SIGNATURE_INVALID, "manifest signature does not match current key");
			}
		} catch (VerificationException exception) {
			throw exception;
		} catch (java.security.GeneralSecurityException | IllegalArgumentException exception) {
			throw failure(Reason.MANIFEST_SIGNATURE_INVALID, "manifest signature is malformed", exception);
		}
	}

	private static String sha256(byte[] value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	private static VerificationException failure(Reason reason, String message) {
		return new VerificationException(reason, message, null);
	}

	private static VerificationException failure(Reason reason, String message, Throwable cause) {
		return new VerificationException(reason, message, cause);
	}

	private record VerificationResult(
		String keyId,
		String algorithm,
		String publicKeySha256,
		String signingInputSha256) {
	}

	public record CurrentKey(String keyId, String publicKeyPem) {
	}

	public enum Reason {
		SIGNING_INPUT_INVALID,
		SIGNING_INPUT_IDENTITY_MISMATCH,
		CURRENT_KEY_ID_MISMATCH,
		CURRENT_KEY_CONFIG_INVALID,
		MANIFEST_SIGNATURE_INVALID
	}

	public static final class VerificationException extends RuntimeException {
		private final Reason reason;

		private VerificationException(Reason reason, String message, Throwable cause) {
			super(message, cause);
			this.reason = Objects.requireNonNull(reason, "reason");
		}

		public Reason reason() {
			return reason;
		}
	}

	public static final class VerifiedSignature {
		private final RouteBundleConsumerHandoff handoff;
		private final String keyId;
		private final String algorithm;
		private final String publicKeySha256;
		private final String signingInputSha256;

		private VerifiedSignature(
			RouteBundleConsumerHandoff handoff,
			String keyId,
			String algorithm,
			String publicKeySha256,
			String signingInputSha256) {
			this.handoff = Objects.requireNonNull(handoff, "handoff");
			this.keyId = Objects.requireNonNull(keyId, "keyId");
			this.algorithm = Objects.requireNonNull(algorithm, "algorithm");
			this.publicKeySha256 = Objects.requireNonNull(publicKeySha256, "publicKeySha256");
			this.signingInputSha256 = Objects.requireNonNull(signingInputSha256, "signingInputSha256");
		}

		public RouteBundleConsumerHandoff handoff() {
			return handoff;
		}

		public String keyId() {
			return keyId;
		}

		public String algorithm() {
			return algorithm;
		}

		public String publicKeySha256() {
			return publicKeySha256;
		}

		public String signingInputSha256() {
			return signingInputSha256;
		}
	}

	public static final class VerifiedPublicationDescriptorSignature {
		private final RouteBundlePublicationDescriptor descriptor;
		private final String keyId;
		private final String algorithm;
		private final String publicKeySha256;
		private final String signingInputSha256;

		private VerifiedPublicationDescriptorSignature(
			RouteBundlePublicationDescriptor descriptor,
			String keyId,
			String algorithm,
			String publicKeySha256,
			String signingInputSha256) {
			this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
			this.keyId = Objects.requireNonNull(keyId, "keyId");
			this.algorithm = Objects.requireNonNull(algorithm, "algorithm");
			this.publicKeySha256 = Objects.requireNonNull(publicKeySha256, "publicKeySha256");
			this.signingInputSha256 = Objects.requireNonNull(signingInputSha256, "signingInputSha256");
		}

		public RouteBundlePublicationDescriptor descriptor() {
			return descriptor;
		}

		public String keyId() {
			return keyId;
		}

		public String algorithm() {
			return algorithm;
		}

		public String publicKeySha256() {
			return publicKeySha256;
		}

		public String signingInputSha256() {
			return signingInputSha256;
		}
	}
}
