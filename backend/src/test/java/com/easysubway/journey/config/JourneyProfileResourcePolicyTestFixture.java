package com.easysubway.journey.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Supplies one real, digest-bound policy file to full application-context tests. */
public final class JourneyProfileResourcePolicyTestFixture {

	private static final Path PATH = writePolicy();
	private static final String SHA256 = sha256(readPolicy());

	private JourneyProfileResourcePolicyTestFixture() {
	}

	public static String path() {
		return PATH.toString();
	}

	public static String sha256() {
		return SHA256;
	}

	private static Path writePolicy() {
		try {
			Path path = Files.createTempFile("journey-profile-resource-policy-", ".json");
			Files.writeString(path, JourneyProfileResourcePolicyArtifactTest.validJson(), StandardCharsets.UTF_8);
			path.toFile().deleteOnExit();
			return path;
		} catch (IOException exception) {
			throw new AssertionError("failed to create Journey profile resource-policy fixture", exception);
		}
	}

	private static byte[] readPolicy() {
		try {
			return Files.readAllBytes(PATH);
		} catch (IOException exception) {
			throw new AssertionError("failed to read Journey profile resource-policy fixture", exception);
		}
	}

	public static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException exception) {
			throw new AssertionError("SHA-256 is unavailable", exception);
		}
	}
}
