package com.easysubway.route.domain;

import com.easysubway.profile.domain.MobilityType;
import java.time.LocalDateTime;
import java.util.List;

public record RouteFeedbackDashboardSummary(
	long totalCount,
	long helpfulCount,
	long notHelpfulCount,
	long blockedByRealWorldCount,
	List<RecentBlockedFeedback> recentBlockedFeedbacks,
	List<EtaCalibrationBucket> etaCalibrationBuckets
) {

	public RouteFeedbackDashboardSummary(
		long totalCount,
		long helpfulCount,
		long notHelpfulCount,
		long blockedByRealWorldCount,
		List<RecentBlockedFeedback> recentBlockedFeedbacks
	) {
		this(totalCount, helpfulCount, notHelpfulCount, blockedByRealWorldCount, recentBlockedFeedbacks, List.of());
	}

	public RouteFeedbackDashboardSummary {
		if (totalCount < 0 || helpfulCount < 0 || notHelpfulCount < 0 || blockedByRealWorldCount < 0) {
			throw new InvalidRouteFeedbackException("경로 피드백 집계 수는 0 이상이어야 합니다.");
		}
		if (totalCount != helpfulCount + notHelpfulCount + blockedByRealWorldCount) {
			throw new InvalidRouteFeedbackException("전체 경로 피드백 수와 평점별 피드백 수가 일치하지 않습니다.");
		}
		recentBlockedFeedbacks = List.copyOf(recentBlockedFeedbacks);
		etaCalibrationBuckets = List.copyOf(etaCalibrationBuckets);
	}

	public record RecentBlockedFeedback(
		String originStationName,
		String destinationStationName,
		MobilityType mobilityType,
		LocalDateTime createdAt
	) {

		public RecentBlockedFeedback {
			if (originStationName == null || originStationName.isBlank()) {
				throw new InvalidRouteFeedbackException("현장 차단 신고 출발역 이름이 필요합니다.");
			}
			if (destinationStationName == null || destinationStationName.isBlank()) {
				throw new InvalidRouteFeedbackException("현장 차단 신고 도착역 이름이 필요합니다.");
			}
			if (mobilityType == null) {
				throw new InvalidRouteFeedbackException("현장 차단 신고 이동 프로필이 필요합니다.");
			}
			if (createdAt == null) {
				throw new InvalidRouteFeedbackException("현장 차단 신고 시각이 필요합니다.");
			}
		}
	}

	public record EtaCalibrationBucket(
		MobilityType mobilityType,
		ConstraintMode constraintMode,
		EtaSource etaSource,
		RouteEtaOffsetBucket etaOffsetBucket,
		long count
	) {

		public EtaCalibrationBucket {
			if (mobilityType == null || constraintMode == null || etaSource == null || etaOffsetBucket == null) {
				throw new InvalidRouteFeedbackException("ETA 보정 bucket 집계 기준이 필요합니다.");
			}
			if (count < 0) {
				throw new InvalidRouteFeedbackException("ETA 보정 bucket 집계 수는 0 이상이어야 합니다.");
			}
		}
	}
}
