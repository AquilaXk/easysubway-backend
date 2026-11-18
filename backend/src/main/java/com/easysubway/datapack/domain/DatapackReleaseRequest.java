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
	LocalDateTime updatedAt,
	String promoteOutcome,
	String promoteDetail
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
			null, null, now, null, now, null, null);
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
			dispatchIdempotencyKey, workflowRunUrl, createdAt, at, at,
			promoteOutcome, promoteDetail);
	}

	public DatapackReleaseRequest markPublished(String workflowRunUrl, LocalDateTime at) {
		if (!status.canTransitionTo(DatapackReleaseRequestStatus.PUBLISHED)) {
			throw new IllegalStateException("release request cannot be published from state: " + status);
		}
		return new DatapackReleaseRequest(approvalId, candidateId, scopeId, targetChannel,
			buildSpecSha256, sourceSnapshotSetHash, approvedLedgerHash, requestedBy, approvedBy,
			DatapackReleaseRequestStatus.PUBLISHED, dispatchIdempotencyKey, workflowRunUrl,
			createdAt, approvedAt, at, promoteOutcome, promoteDetail);
	}

	public DatapackReleaseRequest markFailed(String detail, LocalDateTime at) {
		if (!status.canTransitionTo(DatapackReleaseRequestStatus.FAILED)) {
			throw new IllegalStateException("release request cannot fail from state: " + status);
		}
		return new DatapackReleaseRequest(approvalId, candidateId, scopeId, targetChannel,
			buildSpecSha256, sourceSnapshotSetHash, approvedLedgerHash, requestedBy, approvedBy,
			DatapackReleaseRequestStatus.FAILED, dispatchIdempotencyKey, workflowRunUrl,
			createdAt, approvedAt, at, promoteOutcome, detail);
	}

	public DatapackReleaseRequest withPromoteOutcome(String outcome, String detail) {
		return new DatapackReleaseRequest(approvalId, candidateId, scopeId, targetChannel,
			buildSpecSha256, sourceSnapshotSetHash, approvedLedgerHash, requestedBy, approvedBy,
			status, dispatchIdempotencyKey, workflowRunUrl, createdAt, approvedAt, updatedAt,
			outcome, detail);
	}

	private static String normalizeSha(String value, String name) {
		if (value == null || !SHA256_HEX.matcher(value).matches()) {
			throw new IllegalArgumentException(name + " must be a sha256 hex string");
		}
		return value.toLowerCase(Locale.ROOT);
	}
}
