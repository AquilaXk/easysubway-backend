package com.easysubway.route.domain;

public record RouteFeedbackDashboardSummary(
	long totalCount,
	long helpfulCount,
	long notHelpfulCount,
	long blockedByRealWorldCount
) {

	public RouteFeedbackDashboardSummary {
		if (totalCount < 0 || helpfulCount < 0 || notHelpfulCount < 0 || blockedByRealWorldCount < 0) {
			throw new InvalidRouteFeedbackException("경로 피드백 집계 수는 0 이상이어야 합니다.");
		}
		if (totalCount != helpfulCount + notHelpfulCount + blockedByRealWorldCount) {
			throw new InvalidRouteFeedbackException("전체 경로 피드백 수와 평점별 피드백 수가 일치하지 않습니다.");
		}
	}
}
