package com.easysubway.datapack.domain;

import java.time.Instant;
import java.util.List;

/**
 * data 레포 workflow가 게시하는 자동화 상태 snapshot(artifactKind {@code automation-status-snapshot}, schemaVersion 1).
 * 만료 임박처럼 시간이 지나며 변하는 판정은 담지 않는다: 렌더 시각으로 backend가 계산한다.
 */
public record AutomationStatusSnapshot(
	Instant generatedAt,
	ActiveDatapack activeDatapack,
	List<Stage> stages,
	List<FailureIssue> failureIssues,
	Stuck stuck,
	boolean candidateInFlight
) {

	public AutomationStatusSnapshot {
		stages = List.copyOf(stages);
		failureIssues = List.copyOf(failureIssues);
	}

	public record ActiveDatapack(long releaseSequence, Instant publishedAt, Instant expiresAt) {
	}

	/** 단계의 최근 workflow run. conclusion은 진행 중이면 null이다. */
	public record RunSummary(long runId, String url, String status, String conclusion, Instant createdAt, Instant updatedAt) {
	}

	/** latest가 null이면 그 단계의 run 기록이 없다. */
	public record Stage(String id, String label, RunSummary latest, Instant lastSuccessAt, boolean inFlight) {
	}

	public record FailureIssue(long number, String title, String url, Instant createdAt) {
	}

	public record StuckPull(long number, String title, String url, String branch, String stage, Instant createdAt, String reason) {
	}

	public record StaleClaim(String branch, Instant committedAt) {
	}

	public record BehindCap(String stage, long number, int closures) {
	}

	public record Stuck(List<StuckPull> pulls, List<StaleClaim> claims, List<BehindCap> behindCap) {

		public Stuck {
			pulls = List.copyOf(pulls);
			claims = List.copyOf(claims);
			behindCap = List.copyOf(behindCap);
		}

		public int total() {
			return pulls.size() + claims.size() + behindCap.size();
		}
	}
}
