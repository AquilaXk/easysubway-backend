package com.easysubway.datapack.application.port.out;

import java.time.LocalDateTime;
import java.util.Optional;

public interface DatapackReleaseChannelCommandPort {

	Optional<ReleaseChannelEvent> findEventByIdempotencyKey(String channel, String idempotencyKey);

	/** 채널 현재 상태 조회 — 잠금 없음. promote 전 채널 존재 확인 등 읽기 전용 용도. */
	Optional<ReleaseChannelState> findChannel(String channel);

	Optional<ReleaseChannelState> lockChannel(String channel);

	boolean candidateHasManifest(String candidateId, String manifestSha256);

	boolean candidateHasPassingReleaseEvidence(String candidateId, String evidenceBundleSha256);

	void updateChannel(
		String channel,
		String nextCandidateId,
		String nextManifestSha256,
		String previousStableCandidateId,
		String previousManifestSha256,
		String operationType,
		String requestedBy,
		String approvedBy,
		String reason,
		String idempotencyKey,
		LocalDateTime updatedAt
	);

	void insertEvent(
		String id,
		String channel,
		String previousCandidateId,
		String nextCandidateId,
		String previousManifestSha256,
		String nextManifestSha256,
		String operationType,
		String requestedBy,
		String approvedBy,
		String reason,
		String idempotencyKey,
		String workflowRunUrl,
		String evidenceBundleSha256,
		LocalDateTime createdAt
	);

	record ReleaseChannelEvent(
		String id,
		String channel,
		String previousCandidateId,
		String nextCandidateId,
		String previousManifestSha256,
		String nextManifestSha256,
		String operationType,
		String requestedBy,
		String approvedBy,
		String reason,
		String idempotencyKey,
		String workflowRunUrl,
		String evidenceBundleSha256
	) {
	}

	record ReleaseChannelState(
		String channel,
		String candidateId,
		String manifestSha256,
		String previousStableCandidateId,
		String previousManifestSha256,
		boolean rollbackAvailable,
		String lastOperationStatus
	) {
	}
}
