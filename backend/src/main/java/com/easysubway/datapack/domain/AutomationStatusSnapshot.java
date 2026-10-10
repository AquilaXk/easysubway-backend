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
	boolean candidateInFlight,
	List<ExpiringSource> expiringSources
) {

	public AutomationStatusSnapshot {
		stages = List.copyOf(stages);
		failureIssues = List.copyOf(failureIssues);
		expiringSources = expiringSources == null ? null : List.copyOf(expiringSources);
	}

	/** data 레포가 원천 근거 만료 목록을 보냈는가. 보내지 않은 옛 snapshot은 정상으로 채우지 않고 "보고되지 않음"으로 둔다. */
	public boolean reportsExpiringSources() {
		return expiringSources != null;
	}

	public record ActiveDatapack(long releaseSequence, Instant publishedAt, Instant expiresAt) {
	}

	/** 단계의 최근 workflow run. conclusion은 진행 중이면 null이다. */
	public record RunSummary(long runId, String url, String status, String conclusion, Instant createdAt, Instant updatedAt) {
	}

	/** latest가 null이면 그 단계의 run 기록이 없다. */
	public record Stage(String id, String label, RunSummary latest, Instant lastSuccessAt, boolean inFlight) {
	}

	/** 근거를 갱신하는 자동화 작업의 상태. NONE이면 자동 갱신 경로가 없다(refreshStage도 null). */
	public enum RefreshState { OK, FAILED, BLOCKED, NONE }

	/**
	 * 곧 만료되는 원천 근거 하나. 남은 시간은 담지 않는다: 렌더 시각으로 backend가 계산한다.
	 *
	 * @param name 사람이 읽는 자료 이름
	 * @param evidence 근거 종류(원천 항목 아래 키 이름)
	 * @param refreshStage 근거를 갱신하는 자동화 단계. 자동 갱신 경로가 없으면 null
	 */
	public record ExpiringSource(String sourceId, String name, String evidence, Instant freshUntil, String refreshStage, RefreshState refreshState) {
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
