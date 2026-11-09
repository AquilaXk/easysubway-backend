package com.easysubway.datapack.application.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** release callback payload의 HMAC-SHA256 서명 생성·상수시간 검증. */
public class CallbackSignature {

    private final List<SecretKeySpec> verificationKeys;

    public CallbackSignature(String hmacKey) {
        this(hmacKey, "");
    }

    public CallbackSignature(String hmacKey, String nextHmacKey) {
        this.verificationKeys = buildVerificationKeys(hmacKey, nextHmacKey);
    }

    private static List<SecretKeySpec> buildVerificationKeys(String hmacKey, String nextHmacKey) {
        if (isBlank(hmacKey)) {
            return List.of();
        }
        SecretKeySpec primary = new SecretKeySpec(hmacKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        if (isBlank(nextHmacKey) || nextHmacKey.equals(hmacKey)) {
            return List.of(primary);
        }
        return List.of(
            primary,
            new SecretKeySpec(nextHmacKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public String sign(CanonicalFields f) {
        return signMessage(f.message(), verificationKeys.getFirst());
    }

    public boolean verify(CanonicalFields f, String provided) {
        return verifyMessage(f.message(), provided);
    }

	public String sign(LegacyCanonicalFields f) {
		return signMessage(f.message(), verificationKeys.getFirst());
	}

	public boolean verify(LegacyCanonicalFields f, String provided) {
		return verifyMessage(f.message(), provided);
	}

	private static String signMessage(String message, SecretKeySpec keySpec) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(keySpec);
			return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
		} catch (java.security.GeneralSecurityException e) {
			throw new IllegalStateException("HMAC 계산 실패", e);
		}
	}

	private boolean verifyMessage(String message, String provided) {
		if (provided == null || verificationKeys.isEmpty()) {
			return false;
		}
		byte[] providedBytes = provided.getBytes(StandardCharsets.UTF_8);
		boolean matched = false;
		for (SecretKeySpec keySpec : verificationKeys) {
			byte[] expected = signMessage(message, keySpec).getBytes(StandardCharsets.UTF_8);
			matched |= MessageDigest.isEqual(expected, providedBytes);
		}
		return matched;
	}

	public static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (java.security.GeneralSecurityException e) {
			throw new IllegalStateException("SHA-256 계산 실패", e);
		}
	}

	/** 배포 전 시작된 schema v1 workflow의 canonical callback 형식. */
	public record LegacyCanonicalFields(int schemaVersion, String artifactKind, String releaseRequestId,
		String workflowRunUrl, String manifestSha256, String sqliteSha256, String gzipSha256,
		String evidenceBundleSha256, String validatorStatus, String routeRegressionStatus,
		String publishStatus) {
		public LegacyCanonicalFields {
			for (String value : new String[] {artifactKind, releaseRequestId, workflowRunUrl,
				manifestSha256, sqliteSha256, gzipSha256, evidenceBundleSha256, validatorStatus,
				routeRegressionStatus, publishStatus}) {
				if (value == null || value.isBlank()) {
					throw new IllegalArgumentException("callback canonical field must not be blank");
				}
			}
		}

		String message() {
			return String.join("\n", String.valueOf(schemaVersion), artifactKind, releaseRequestId,
				workflowRunUrl, manifestSha256, sqliteSha256, gzipSha256, evidenceBundleSha256,
				validatorStatus, routeRegressionStatus, publishStatus);
		}
	}

    public record CanonicalFields(int schemaVersion, String artifactKind, String releaseRequestId,
        long releaseSequence, String channel, String idempotencyKey,
        String workflowRunUrl, String manifestSha256, String sqliteSha256, String gzipSha256,
        String evidenceBundleSha256, String validatorStatus, String routeRegressionStatus,
        String publishStatus) {
		public CanonicalFields {
			if (schemaVersion != 2) {
				throw new IllegalArgumentException("callback schemaVersion must be 2");
			}
			if (releaseSequence < 1) {
				throw new IllegalArgumentException("callback releaseSequence must be positive");
			}
			for (String value : new String[] {artifactKind, releaseRequestId, channel, idempotencyKey,
				workflowRunUrl, manifestSha256, sqliteSha256, gzipSha256, evidenceBundleSha256,
				validatorStatus, routeRegressionStatus, publishStatus}) {
				if (value == null || value.isBlank()) {
					throw new IllegalArgumentException("callback canonical field must not be blank");
				}
			}
		}

        String message() {
            return String.join("\n", String.valueOf(schemaVersion), artifactKind, releaseRequestId,
                String.valueOf(releaseSequence), channel, idempotencyKey,
                workflowRunUrl, manifestSha256, sqliteSha256, gzipSha256, evidenceBundleSha256,
                validatorStatus, routeRegressionStatus, publishStatus);
        }

		public String payloadSha256() {
			return CallbackSignature.sha256(message());
		}
    }
}
