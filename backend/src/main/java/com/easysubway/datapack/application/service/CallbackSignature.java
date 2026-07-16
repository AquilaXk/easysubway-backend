package com.easysubway.datapack.application.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** release callback payload의 HMAC-SHA256 서명 생성·상수시간 검증. */
public class CallbackSignature {

    private final byte[] key;

    public CallbackSignature(String hmacKey) {
        this.key = hmacKey == null ? new byte[0] : hmacKey.getBytes(StandardCharsets.UTF_8);
    }

    public String sign(CanonicalFields f) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(f.message().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC 계산 실패", e);
        }
    }

    public boolean verify(CanonicalFields f, String provided) {
        if (provided == null || key.length == 0) {
            return false;
        }
        byte[] a = sign(f).getBytes(StandardCharsets.UTF_8);
        byte[] b = provided.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

	public String sign(LegacyCanonicalFields f) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(f.message().getBytes(StandardCharsets.UTF_8)));
		} catch (java.security.GeneralSecurityException e) {
			throw new IllegalStateException("HMAC 계산 실패", e);
		}
	}

	public boolean verify(LegacyCanonicalFields f, String provided) {
		if (provided == null || key.length == 0) {
			return false;
		}
		byte[] a = sign(f).getBytes(StandardCharsets.UTF_8);
		byte[] b = provided.getBytes(StandardCharsets.UTF_8);
		return MessageDigest.isEqual(a, b);
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
