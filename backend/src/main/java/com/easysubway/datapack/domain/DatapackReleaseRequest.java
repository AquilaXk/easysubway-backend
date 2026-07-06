package com.easysubway.datapack.domain;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.regex.Pattern;

public record DatapackReleaseRequest(
	String approvalId,
	String candidateId,
	String scopeId,
	String targetChannel,
	String buildSpecSha256,
	String sourceSnapshotSetHash,
	String approvedLedgerHash,
	String requestedBy,
	String approvedBy,
	DatapackReleaseRequestStatus status,
	String dispatchIdempotencyKey,
	String workflowRunUrl,
	LocalDateTime createdAt,
	LocalDateTime approvedAt,
	LocalDateTime updatedAt
) {

	private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-fA-F]{64}");

	public DatapackReleaseRequest {
		buildSpecSha256 = normalizeSha(buildSpecSha256, "buildSpecSha256");
		sourceSnapshotSetHash = normalizeSha(sourceSnapshotSetHash, "sourceSnapshotSetHash");
		approvedLedgerHash = normalizeSha(approvedLedgerHash, "approvedLedgerHash");
	}

	public static DatapackReleaseRequest requested(
		String approvalId, String candidateId, String scopeId, String targetChannel,
		String buildSpecSha256, String sourceSnapshotSetHash, String approvedLedgerHash,
		String requestedBy, LocalDateTime now
	) {
		return new DatapackReleaseRequest(
			approvalId, candidateId, scopeId, targetChannel,
			buildSpecSha256, sourceSnapshotSetHash, approvedLedgerHash,
			requestedBy, null, DatapackReleaseRequestStatus.REQUESTED,
			null, null, now, null, now);
	}

	public DatapackReleaseRequest approve(String approver, LocalDateTime at) {
		if (!status.canTransitionTo(DatapackReleaseRequestStatus.APPROVED)) {
			throw new IllegalStateException("release request cannot be approved from state: " + status);
		}
		if (approver == null || approver.isBlank()) {
			throw new IllegalArgumentException("approvedBy is required");
		}
		if (approver.equals(requestedBy)) {
			throw new IllegalArgumentException("approvedBy must be different from requestedBy");
		}
		return new DatapackReleaseRequest(
			approvalId, candidateId, scopeId, targetChannel,
			buildSpecSha256, sourceSnapshotSetHash, approvedLedgerHash,
			requestedBy, approver, DatapackReleaseRequestStatus.APPROVED,
			dispatchIdempotencyKey, workflowRunUrl, createdAt, at, at);
	}

	private static String normalizeSha(String value, String name) {
		if (value == null || !SHA256_HEX.matcher(value).matches()) {
			throw new IllegalArgumentException(name + " must be a sha256 hex string");
		}
		return value.toLowerCase(Locale.ROOT);
	}
}
