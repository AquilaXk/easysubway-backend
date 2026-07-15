package com.easysubway.datapack.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.regex.Pattern;

public record DataSourceSnapshot(
	String snapshotId,
	String sourceId,
	String provider,
	LocalDateTime retrievedAt,
	LocalDateTime sourceUpdatedAt,
	LocalDateTime freshnessBasisAt,
	LocalDateTime providerValidUntil,
	int rowCount,
	int coverageCount,
	String rawSha256,
	String rawObjectUri,
	String redactedRequestFingerprint,
	String schemaFingerprint,
	String snapshotStatus,
	String schemaStatus,
	String licenseStatus,
	String fetchStatus,
	boolean redistributionAllowed,
	boolean credentialRedacted,
	String previousSnapshotId,
	String diffSummary,
	String diffSummaryJson,
	LocalDateTime freshnessExpiresAt,
	LocalDateTime rawRetentionExpiresAt,
	String governancePolicyVersion,
	String governancePolicySha256
) {

	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	public DataSourceSnapshot {
		snapshotId = requireText(snapshotId, "snapshotId");
		sourceId = requireText(sourceId, "sourceId");
		provider = requireText(provider, "provider");
		if (retrievedAt == null) {
			throw new InvalidDataSourceSnapshotException("retrievedAt is required.");
		}
		retrievedAt = normalizeTimestamp(retrievedAt);
		sourceUpdatedAt = normalizeTimestamp(sourceUpdatedAt);
		freshnessBasisAt = normalizeTimestamp(freshnessBasisAt);
		providerValidUntil = normalizeTimestamp(providerValidUntil);
		if (rowCount < 0) {
			throw new InvalidDataSourceSnapshotException("rowCount must be zero or positive.");
		}
		if (coverageCount < 0) {
			throw new InvalidDataSourceSnapshotException("coverageCount must be zero or positive.");
		}
		rawSha256 = requireSha256(rawSha256, "rawSha256");
		rawObjectUri = requireText(rawObjectUri, "rawObjectUri");
		redactedRequestFingerprint = requireSha256(redactedRequestFingerprint, "redactedRequestFingerprint");
		schemaFingerprint = requireSha256(schemaFingerprint, "schemaFingerprint");
		snapshotStatus = requireText(snapshotStatus, "snapshotStatus");
		schemaStatus = requireText(schemaStatus, "schemaStatus");
		licenseStatus = requireText(licenseStatus, "licenseStatus");
		fetchStatus = requireText(fetchStatus, "fetchStatus");
		previousSnapshotId = trimToNull(previousSnapshotId);
		diffSummary = trimToNull(diffSummary);
		diffSummaryJson = canonicalJson(diffSummaryJson);
		if (freshnessExpiresAt == null) {
			throw new InvalidDataSourceSnapshotException("freshnessExpiresAt is required.");
		}
		freshnessExpiresAt = normalizeTimestamp(freshnessExpiresAt);
		if (rawRetentionExpiresAt == null) {
			throw new InvalidDataSourceSnapshotException("rawRetentionExpiresAt is required.");
		}
		rawRetentionExpiresAt = normalizeTimestamp(rawRetentionExpiresAt);
		governancePolicyVersion = trimToNull(governancePolicyVersion);
		governancePolicySha256 = trimToNull(governancePolicySha256);
	}

	public void requireRawEvidenceWritePolicy() {
		if (!credentialRedacted) {
			throw new InvalidDataSourceSnapshotException("credentialRedacted must be true before storing raw evidence.");
		}
		requireCredentialFreeRawObjectUri(rawObjectUri);
		if (!rawRetentionExpiresAt.isAfter(retrievedAt)) {
			throw new InvalidDataSourceSnapshotException("rawRetentionExpiresAt must be after retrievedAt.");
		}
		requireText(governancePolicyVersion, "governancePolicyVersion");
		requireSha256(governancePolicySha256, "governancePolicySha256");
		if ((previousSnapshotId == null) != (diffSummary == null)
			|| (previousSnapshotId == null) != (diffSummaryJson == null)) {
			throw new InvalidDataSourceSnapshotException(
				"first snapshot must omit diffSummary and diffSummaryJson; later snapshots must include both."
			);
		}
	}

	private static String requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new InvalidDataSourceSnapshotException(field + " is required.");
		}
		return value.trim();
	}

	private static String requireSha256(String value, String field) {
		String trimmed = requireText(value, field);
		if (!SHA256.matcher(trimmed).matches()) {
			throw new InvalidDataSourceSnapshotException(field + " must be a lowercase SHA-256 hex value.");
		}
		return trimmed;
	}

	private static void requireCredentialFreeRawObjectUri(String value) {
		String trimmed = requireText(value, "rawObjectUri");
		URI uri;
		try {
			uri = new URI(trimmed);
		} catch (URISyntaxException exception) {
			throw new InvalidDataSourceSnapshotException("rawObjectUri must be a credential-free object storage URI.");
		}
		var objectStorageScheme = "s3".equals(uri.getScheme()) || "oci".equals(uri.getScheme());
		if (!objectStorageScheme
			|| uri.getRawAuthority() == null
			|| uri.getRawAuthority().isBlank()
			|| uri.getRawPath() == null
			|| uri.getRawPath().isBlank()
			|| "/".equals(uri.getRawPath())
			|| uri.getPort() != -1
			|| !isCanonicalObjectPath(uri.getPath())
			|| trimmed.contains("@")
			|| uri.getRawQuery() != null
			|| uri.getRawUserInfo() != null
			|| uri.getRawFragment() != null) {
			throw new InvalidDataSourceSnapshotException("rawObjectUri must be a credential-free object storage URI.");
		}
	}

	private static boolean isCanonicalObjectPath(String decodedPath) {
		if (decodedPath == null || !decodedPath.startsWith("/") || decodedPath.length() == 1) {
			return false;
		}
		for (String segment : decodedPath.substring(1).split("/", -1)) {
			if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)
				|| segment.codePoints().anyMatch(Character::isISOControl)) {
				return false;
			}
		}
		return true;
	}

	private static String trimToNull(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value.trim();
	}

	private static String canonicalJson(String value) {
		String trimmed = trimToNull(value);
		if (trimmed == null) {
			return null;
		}
		try {
			return OBJECT_MAPPER.writeValueAsString(sortJson(OBJECT_MAPPER.readTree(trimmed)));
		} catch (JsonProcessingException exception) {
			throw new InvalidDataSourceSnapshotException("diffSummaryJson must be valid JSON.");
		}
	}

	private static JsonNode sortJson(JsonNode value) {
		if (value.isObject()) {
			var sorted = OBJECT_MAPPER.createObjectNode();
			var fields = new ArrayList<String>();
			value.fieldNames().forEachRemaining(fields::add);
			fields.sort(Comparator.naturalOrder());
			for (String field : fields) {
				sorted.set(field, sortJson(value.get(field)));
			}
			return sorted;
		}
		if (value.isArray()) {
			var sorted = OBJECT_MAPPER.createArrayNode();
			value.forEach(entry -> sorted.add(sortJson(entry)));
			return sorted;
		}
		return value;
	}

	private static LocalDateTime normalizeTimestamp(LocalDateTime value) {
		if (value == null) {
			return null;
		}
		return value.truncatedTo(ChronoUnit.MICROS);
	}
}
