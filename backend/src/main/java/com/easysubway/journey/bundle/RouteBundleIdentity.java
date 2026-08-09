package com.easysubway.journey.bundle;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The complete identity supplied by the reviewed fixture. This is deliberately
 * value-only: production publication and signature verification belong to a
 * later integration boundary.
 */
public record RouteBundleIdentity(
    String component,
    String bundleVersion,
    String stationCatalogContract,
    String timeZone,
    String manifestSha256,
    String payloadSha256,
    String topologySha256,
    String timetableSha256,
    String accessibilitySha256,
    String fareSha256,
    String provenanceSha256,
    String compatibilitySha256,
    Instant activeFrom,
    Instant freshUntil,
    int backendSchemaVersion,
    String digestAlgorithm,
    String signingIdentity,
    String publicationEvidenceSha256,
    String finalEvidenceSha256,
    String promotionEvidenceSha256,
    String activationEvidenceSha256) {

    private static final Pattern CANONICAL_DIGEST = Pattern.compile("[a-z0-9]{64}");

    public RouteBundleIdentity {
        requireExact(component, "server-route-bundle", "component");
        requireExact(bundleVersion, "v1", "bundleVersion");
        requireExact(stationCatalogContract, "station-catalog-v1", "stationCatalogContract");
        requireExact(timeZone, "Asia/Seoul", "timeZone");
        requireDigest(manifestSha256, "manifestSha256");
        requireDigest(payloadSha256, "payloadSha256");
        requireDigest(topologySha256, "topologySha256");
        requireDigest(timetableSha256, "timetableSha256");
        requireDigest(accessibilitySha256, "accessibilitySha256");
        requireDigest(fareSha256, "fareSha256");
        requireDigest(provenanceSha256, "provenanceSha256");
        requireDigest(compatibilitySha256, "compatibilitySha256");
        activeFrom = Objects.requireNonNull(activeFrom, "activeFrom");
        freshUntil = Objects.requireNonNull(freshUntil, "freshUntil");
        if (!freshUntil.isAfter(activeFrom)) {
            throw new IllegalArgumentException("freshUntil must be after activeFrom");
        }
        if (backendSchemaVersion != 3) {
            throw new IllegalArgumentException("backendSchemaVersion must be 3");
        }
        requireExact(digestAlgorithm, "SHA-256", "digestAlgorithm");
        requireExact(signingIdentity, "fixture-signing-key-v1", "signingIdentity");
        requireDigest(publicationEvidenceSha256, "publicationEvidenceSha256");
        requireDigest(finalEvidenceSha256, "finalEvidenceSha256");
        requireDigest(promotionEvidenceSha256, "promotionEvidenceSha256");
        requireDigest(activationEvidenceSha256, "activationEvidenceSha256");
    }

    private static void requireExact(String value, String expected, String field) {
        if (!expected.equals(value)) {
            throw new IllegalArgumentException(field + " is not the current fixture identity");
        }
    }

    private static void requireDigest(String value, String field) {
        if (value == null || !CANONICAL_DIGEST.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a canonical fixture digest");
        }
    }
}
