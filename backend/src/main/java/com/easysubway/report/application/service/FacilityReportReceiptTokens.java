package com.easysubway.report.application.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

class FacilityReportReceiptTokens {

	private static final int TOKEN_BYTES = 32;
	private static final String HMAC_ALGORITHM = "HmacSHA256";

	private final String pepper;
	private final SecureRandom secureRandom;

	FacilityReportReceiptTokens(String pepper) {
		this(pepper, new SecureRandom());
	}

	FacilityReportReceiptTokens(String pepper, SecureRandom secureRandom) {
		this.pepper = pepper;
		this.secureRandom = secureRandom;
	}

	IssuedReceiptToken issue(String clientSubmissionId) {
		normalize(clientSubmissionId);
		byte[] tokenBytes = new byte[TOKEN_BYTES];
		secureRandom.nextBytes(tokenBytes);
		String token = Base64.getUrlEncoder()
			.withoutPadding()
			.encodeToString(tokenBytes);
		return new IssuedReceiptToken(token, hash(token));
	}

	String hash(String token) {
		return hex(hmac(normalize(token)));
	}

	boolean matches(String token, String expectedHash) {
		if (token == null || token.isBlank() || expectedHash == null) {
			return false;
		}
		return MessageDigest.isEqual(
			hash(token).getBytes(StandardCharsets.UTF_8),
			expectedHash.getBytes(StandardCharsets.UTF_8)
		);
	}

	private String normalize(String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("receipt token value must not be blank");
		}
		return value.trim();
	}

	private byte[] hmac(String value) {
		try {
			Mac mac = Mac.getInstance(HMAC_ALGORITHM);
			mac.init(new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
			return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
		} catch (java.security.GeneralSecurityException exception) {
			throw new IllegalStateException("HMAC-SHA256 is required", exception);
		}
	}

	private String hex(byte[] bytes) {
		StringBuilder builder = new StringBuilder(bytes.length * 2);
		for (byte value : bytes) {
			builder.append(String.format("%02x", value & 0xff));
		}
		return builder.toString();
	}

	record IssuedReceiptToken(String token, String hash) {
	}
}
