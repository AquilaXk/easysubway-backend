package com.easysubway.journey.bundle;

import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "easysubway.journey-v3.route-bundle-startup", ignoreUnknownFields = false)
public record RouteBundleStartupProperties(
	String descriptorBase64,
	String activationRequestIdentity,
	String trustedRawDescriptorBaseUrl,
	String currentKeyId,
	String currentPublicKeyPem,
	String nextKeyId,
	String nextPublicKeyPem) {

	private static final int MAX_DESCRIPTOR_BYTES = 1024 * 1024;
	private static final int MAX_DESCRIPTOR_BASE64_LENGTH = ((MAX_DESCRIPTOR_BYTES + 2) / 3) * 4;
	private static final int MAX_ACTIVATION_IDENTITY_LENGTH = 512;
	private static final int MAX_TRUSTED_ORIGIN_LENGTH = 2048;
	private static final int MAX_PUBLIC_KEY_LENGTH = 16 * 1024;
	private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

	@ConstructorBinding
	public RouteBundleStartupProperties {
		decodeDescriptor(descriptorBase64);
		requireBoundedRaw(
			activationRequestIdentity,
			MAX_ACTIVATION_IDENTITY_LENGTH,
			"activationRequestIdentity");
		requireBoundedRaw(
			trustedRawDescriptorBaseUrl,
			MAX_TRUSTED_ORIGIN_LENGTH,
			"trustedRawDescriptorBaseUrl");
		requireKeyId(currentKeyId, "currentKeyId");
		requirePublicKeyPem(currentPublicKeyPem, "currentPublicKeyPem");
		nextKeyId = normalizeOptional(nextKeyId);
		nextPublicKeyPem = normalizeOptional(nextPublicKeyPem);
		if ((nextKeyId == null) != (nextPublicKeyPem == null)) {
			throw new IllegalArgumentException("nextKeyId and nextPublicKeyPem must be configured together");
		}
		if (nextKeyId != null) {
			requireKeyId(nextKeyId, "nextKeyId");
			if (currentKeyId.equals(nextKeyId)) {
				throw new IllegalArgumentException("nextKeyId must be distinct from currentKeyId");
			}
			requirePublicKeyPem(nextPublicKeyPem, "nextPublicKeyPem");
		}
	}

	public RouteBundleStartupProperties(
		String descriptorBase64,
		String activationRequestIdentity,
		String trustedRawDescriptorBaseUrl,
		String currentKeyId,
		String currentPublicKeyPem) {
		this(
			descriptorBase64,
			activationRequestIdentity,
			trustedRawDescriptorBaseUrl,
			currentKeyId,
			currentPublicKeyPem,
			null,
			null);
	}

	public byte[] descriptorBytes() {
		return decodeDescriptor(descriptorBase64);
	}

	public List<RouteBundleCurrentKeyVerifier.CurrentKey> trustedKeys() {
		var currentKey = new RouteBundleCurrentKeyVerifier.CurrentKey(currentKeyId, currentPublicKeyPem);
		if (nextKeyId == null) {
			return List.of(currentKey);
		}
		return List.of(
			currentKey,
			new RouteBundleCurrentKeyVerifier.CurrentKey(nextKeyId, nextPublicKeyPem));
	}

	private static String normalizeOptional(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	private static void requireKeyId(String value, String field) {
		if (value == null || !KEY_ID.matcher(value).matches()) {
			throw new IllegalArgumentException(field + " must be a bounded safe key identity");
		}
	}

	private static void requirePublicKeyPem(String value, String field) {
		if (value == null
			|| value.isBlank()
			|| value.length() > MAX_PUBLIC_KEY_LENGTH
			|| !value.equals(value.strip())
			|| value.indexOf('\0') >= 0) {
			throw new IllegalArgumentException(field + " must be bounded non-blank raw text");
		}
		try {
			RouteBundleCurrentKeyVerifier.validatePublicKeyPem(value);
		} catch (RouteBundleCurrentKeyVerifier.VerificationException exception) {
			throw new IllegalArgumentException(field + " must be an exact RSA public-key PEM", exception);
		}
	}

	private static byte[] decodeDescriptor(String value) {
		if (value == null || value.isEmpty() || value.length() > MAX_DESCRIPTOR_BASE64_LENGTH) {
			throw new IllegalArgumentException("descriptorBase64 must be bounded canonical Base64");
		}
		try {
			byte[] decoded = Base64.getDecoder().decode(value);
			if (decoded.length == 0
				|| decoded.length > MAX_DESCRIPTOR_BYTES
				|| !Base64.getEncoder().encodeToString(decoded).equals(value)) {
				throw new IllegalArgumentException("descriptorBase64 must be bounded canonical Base64");
			}
			return decoded;
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("descriptorBase64 must be bounded canonical Base64", exception);
		}
	}

	private static void requireBoundedRaw(String value, int maxLength, String field) {
		if (value == null
			|| value.isEmpty()
			|| value.length() > maxLength
			|| !value.equals(value.strip())
			|| value.codePoints().anyMatch(codePoint -> codePoint < 0x20 || codePoint == 0x7f)) {
			throw new IllegalArgumentException(field + " must be bounded non-empty raw text");
		}
	}
}
