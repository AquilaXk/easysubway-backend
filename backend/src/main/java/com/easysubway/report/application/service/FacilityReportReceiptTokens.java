package com.easysubway.report.application.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

class FacilityReportReceiptTokens {

	private final String pepper;

	FacilityReportReceiptTokens(String pepper) {
		this.pepper = pepper;
	}

	IssuedReceiptToken issue(String clientSubmissionId) {
		String normalizedClientSubmissionId = normalize(clientSubmissionId);
		byte[] tokenBytes = sha256("receipt-token:%s:%s".formatted(pepper, normalizedClientSubmissionId));
		String token = Base64.getUrlEncoder()
			.withoutPadding()
			.encodeToString(tokenBytes);
		return new IssuedReceiptToken(token, hash(token));
	}

	String hash(String token) {
		return hex(sha256("receipt-token-hash:%s:%s".formatted(pepper, normalize(token))));
	}

	boolean matches(String token, String expectedHash) {
		return expectedHash != null && MessageDigest.isEqual(
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

	private byte[] sha256(String value) {
		try {
			return MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is required", exception);
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
