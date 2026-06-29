package com.easysubway.datapack.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

public record DataSourceSnapshot(
	String snapshotId,
	String sourceId,
	String provider,
	LocalDateTime retrievedAt,
	LocalDateTime sourceUpdatedAt,
	int rowCount,
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
	LocalDateTime freshnessExpiresAt,
	LocalDateTime rawRetentionExpiresAt
) {

	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

	public DataSourceSnapshot {
		snapshotId = requireText(snapshotId, "snapshotId");
		sourceId = requireText(sourceId, "sourceId");
		provider = requireText(provider, "provider");
		if (retrievedAt == null) {
			throw new InvalidDataSourceSnapshotException("retrievedAt is required.");
		}
		retrievedAt = normalizeTimestamp(retrievedAt);
		sourceUpdatedAt = normalizeTimestamp(sourceUpdatedAt);
		if (rowCount < 0) {
			throw new InvalidDataSourceSnapshotException("rowCount must be zero or positive.");
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
		if (freshnessExpiresAt == null) {
			throw new InvalidDataSourceSnapshotException("freshnessExpiresAt is required.");
		}
		freshnessExpiresAt = normalizeTimestamp(freshnessExpiresAt);
		if (rawRetentionExpiresAt == null) {
			throw new InvalidDataSourceSnapshotException("rawRetentionExpiresAt is required.");
		}
		rawRetentionExpiresAt = normalizeTimestamp(rawRetentionExpiresAt);
	}

	public void requireRawEvidenceWritePolicy() {
		if (!credentialRedacted) {
			throw new InvalidDataSourceSnapshotException("credentialRedacted must be true before storing raw evidence.");
		}
		requireCredentialFreeRawObjectUri(rawObjectUri);
		if (!rawRetentionExpiresAt.isAfter(retrievedAt)) {
			throw new InvalidDataSourceSnapshotException("rawRetentionExpiresAt must be after retrievedAt.");
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
			|| trimmed.contains("@")
			|| uri.getRawQuery() != null
			|| uri.getRawUserInfo() != null
			|| uri.getRawFragment() != null) {
			throw new InvalidDataSourceSnapshotException("rawObjectUri must be a credential-free object storage URI.");
		}
	}

	private static String trimToNull(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value.trim();
	}

	private static LocalDateTime normalizeTimestamp(LocalDateTime value) {
		if (value == null) {
			return null;
		}
		return value.truncatedTo(ChronoUnit.MICROS);
	}
}
