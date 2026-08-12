package com.easysubway.journey.bundle;

import java.util.Base64;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "easysubway.journey-v3.route-bundle-startup", ignoreUnknownFields = false)
public record RouteBundleStartupProperties(
	String descriptorBase64,
	String activationRequestIdentity,
	String trustedRawDescriptorBaseUrl,
	String currentKeyId,
	String currentPublicKeyPem) {

	private static final int MAX_DESCRIPTOR_BYTES = 1024 * 1024;
	private static final int MAX_DESCRIPTOR_BASE64_LENGTH = ((MAX_DESCRIPTOR_BYTES + 2) / 3) * 4;
	private static final int MAX_ACTIVATION_IDENTITY_LENGTH = 512;
	private static final int MAX_TRUSTED_ORIGIN_LENGTH = 2048;
	private static final int MAX_PUBLIC_KEY_LENGTH = 16 * 1024;
	private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

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
		if (currentKeyId == null || !KEY_ID.matcher(currentKeyId).matches()) {
			throw new IllegalArgumentException("currentKeyId must be a bounded safe key identity");
		}
		if (currentPublicKeyPem == null
			|| currentPublicKeyPem.isBlank()
			|| currentPublicKeyPem.length() > MAX_PUBLIC_KEY_LENGTH
			|| !currentPublicKeyPem.equals(currentPublicKeyPem.strip())
			|| currentPublicKeyPem.indexOf('\0') >= 0) {
			throw new IllegalArgumentException("currentPublicKeyPem must be bounded non-blank raw text");
		}
	}

	public byte[] descriptorBytes() {
		return decodeDescriptor(descriptorBase64);
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
