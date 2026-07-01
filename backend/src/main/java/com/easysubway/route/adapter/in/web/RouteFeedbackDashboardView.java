package com.easysubway.route.adapter.in.web;

import java.util.List;

public record RouteFeedbackDashboardView(
	long totalCount,
	long helpfulCount,
	long notHelpfulCount,
	long blockedByRealWorldCount,
	List<RatingCountRow> ratingRows,
	List<RecentBlockedFeedbackRow> recentBlockedFeedbacks,
	List<EtaCalibrationBucketRow> etaCalibrationBuckets
) {

	public record RatingCountRow(String label, String description, long count) {
	}

	public record RecentBlockedFeedbackRow(
		String createdAtLabel,
		String originStationName,
		String destinationStationName,
		String mobilityTypeLabel
	) {
	}

	public record EtaCalibrationBucketRow(
		String mobilityTypeLabel,
		String constraintMode,
		String etaSource,
		String etaOffsetBucket,
		long count,
		String reviewAction
	) {
	}
}
