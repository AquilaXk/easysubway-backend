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

    public record CanonicalFields(int schemaVersion, String artifactKind, String releaseRequestId,
        String workflowRunUrl, String manifestSha256, String sqliteSha256, String gzipSha256,
        String evidenceBundleSha256, String validatorStatus, String routeRegressionStatus,
        String publishStatus) {

        String message() {
            return String.join("\n", String.valueOf(schemaVersion), artifactKind, releaseRequestId,
                workflowRunUrl, manifestSha256, sqliteSha256, gzipSha256, evidenceBundleSha256,
                validatorStatus, routeRegressionStatus, publishStatus);
        }
    }
}
