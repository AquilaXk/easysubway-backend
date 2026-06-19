package com.easysubway.auth.application.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class AnonymousAuthTokenHasher {

	private AnonymousAuthTokenHasher() {
	}

	public static String sha256(String token) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(token.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 hash algorithm is required.", exception);
		}
	}
}
