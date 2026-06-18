package com.easysubway.route.adapter.in.web;

import java.util.List;

public record RouteFeedbackDashboardView(
	long totalCount,
	long helpfulCount,
	long notHelpfulCount,
	long blockedByRealWorldCount,
	List<RatingCountRow> ratingRows,
	List<RecentBlockedFeedbackRow> recentBlockedFeedbacks
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
}
