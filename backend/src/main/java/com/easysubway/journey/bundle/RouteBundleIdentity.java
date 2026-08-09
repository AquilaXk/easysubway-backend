package com.easysubway.journey.bundle;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable Data v1 manifest identity. Backend admission provenance is deliberately separate. */
public record RouteBundleIdentity(
	int manifestVersion,
	String artifactKind,
	String bundleId,
	long releaseSequence,
	String stationSetSha256,
	String payloadSha256,
	String topologySha256,
	String timetableSha256,
	String accessibilitySha256,
	String fareSha256,
	String provenanceSha256,
	String compatibilitySha256,
	String serviceTimezone,
	String activeFrom,
	String freshUntil,
	SchemaCompatibility schemaCompatibility,
	String keyId,
	Signature signature) {

	private static final long MAX_SAFE_RELEASE_SEQUENCE = 9_007_199_254_740_991L;
	private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
	private static final Pattern KST_MILLIS = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\+09:00");
	private static final Pattern BASE64URL_UNPADDED = Pattern.compile("[A-Za-z0-9_-]+");
	private static final String ARTIFACT_KIND = "server-route-bundle";
	private static final String SERVICE_TIMEZONE = "Asia/Seoul";
	private static final String SIGNATURE_ALGORITHM = "rsa-sha256-server-route-bundle-v1";

	public RouteBundleIdentity {
		if (manifestVersion != 1) {
			throw new IllegalArgumentException("manifestVersion must be 1");
		}
		if (!ARTIFACT_KIND.equals(artifactKind)) {
			throw new IllegalArgumentException("artifactKind must be " + ARTIFACT_KIND);
		}
		requireRawText(bundleId, "bundleId");
		if (releaseSequence < 1 || releaseSequence > MAX_SAFE_RELEASE_SEQUENCE) {
			throw new IllegalArgumentException("releaseSequence must be between 1 and " + MAX_SAFE_RELEASE_SEQUENCE);
		}
		requireSha256(stationSetSha256, "stationSetSha256");
		requireSha256(payloadSha256, "payloadSha256");
		requireSha256(topologySha256, "topologySha256");
		requireSha256(timetableSha256, "timetableSha256");
		requireSha256(accessibilitySha256, "accessibilitySha256");
		requireSha256(fareSha256, "fareSha256");
		requireSha256(provenanceSha256, "provenanceSha256");
		requireSha256(compatibilitySha256, "compatibilitySha256");
		if (!SERVICE_TIMEZONE.equals(serviceTimezone)) {
			throw new IllegalArgumentException("serviceTimezone must be " + SERVICE_TIMEZONE);
		}
		activeFrom = requireKstMillis(activeFrom, "activeFrom");
		freshUntil = requireKstMillis(freshUntil, "freshUntil");
		if (!activeFromInstant(activeFrom).isBefore(freshUntilInstant(freshUntil))) {
			throw new IllegalArgumentException("activeFrom must be before freshUntil");
		}
		schemaCompatibility = Objects.requireNonNull(schemaCompatibility, "schemaCompatibility");
		requireRawText(keyId, "keyId");
		signature = Objects.requireNonNull(signature, "signature");
	}

	public Instant activeFromInstant() {
		return activeFromInstant(activeFrom);
	}

	public Instant freshUntilInstant() {
		return freshUntilInstant(freshUntil);
	}

	public record SchemaCompatibility(int backendMin, int backendMax) {
		public SchemaCompatibility {
			if (backendMin != 3 || backendMax != 3) {
				throw new IllegalArgumentException("backend schema compatibility must be 3..3");
			}
		}
	}

	public record Signature(String algorithm, String value) {
		public Signature {
			if (!SIGNATURE_ALGORITHM.equals(algorithm)) {
				throw new IllegalArgumentException("signature algorithm is not current");
			}
			if (value == null || !BASE64URL_UNPADDED.matcher(value).matches()) {
				throw new IllegalArgumentException("signature value must be unpadded base64url");
			}
		}
	}

	private static String requireKstMillis(String value, String field) {
		if (value == null || !KST_MILLIS.matcher(value).matches()) {
			throw new IllegalArgumentException(field + " must be a KST millisecond timestamp");
		}
		try {
			OffsetDateTime parsed = OffsetDateTime.parse(value);
			if (!ZoneOffset.ofHours(9).equals(parsed.getOffset())) {
				throw new IllegalArgumentException(field + " must use +09:00");
			}
			return value;
		} catch (java.time.DateTimeException exception) {
			throw new IllegalArgumentException(field + " must be Gregorian-valid", exception);
		}
	}

	private static Instant activeFromInstant(String value) {
		return OffsetDateTime.parse(value).toInstant();
	}

	private static Instant freshUntilInstant(String value) {
		return OffsetDateTime.parse(value).toInstant();
	}

	private static void requireRawText(String value, String field) {
		if (value == null || value.isEmpty()
			|| isEcmaScriptTrimCodePoint(value.codePointAt(0))
			|| isEcmaScriptTrimCodePoint(value.codePointBefore(value.length()))) {
			throw new IllegalArgumentException(field + " must be non-empty raw text without ECMAScript trim edges");
		}
	}

	private static boolean isEcmaScriptTrimCodePoint(int codePoint) {
		return codePoint >= 0x2000 && codePoint <= 0x200A
			|| switch (codePoint) {
				case 0x0009, 0x000A, 0x000B, 0x000C, 0x000D, 0x0020, 0x00A0, 0x1680,
					0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF -> true;
				default -> false;
			};
	}

	private static void requireSha256(String value, String field) {
		if (value == null || !SHA_256.matcher(value).matches()) {
			throw new IllegalArgumentException(field + " must be a lowercase SHA-256 digest");
		}
	}
}
