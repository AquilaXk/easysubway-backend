package com.easysubway.route.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Route feedback dashboard immutable list boundary")
class RouteFeedbackDashboardViewDefensiveCopyTest {

	private static final RouteFeedbackDashboardView.RatingCountRow RATING_ROW =
		new RouteFeedbackDashboardView.RatingCountRow("도움이 됨", "경로 안내가 실제 이동에 도움됨", 3);
	private static final RouteFeedbackDashboardView.RecentBlockedFeedbackRow BLOCKED_FEEDBACK_ROW =
		new RouteFeedbackDashboardView.RecentBlockedFeedbackRow(
			"2026-08-13 09:30",
			"상록수역",
			"한대앞역",
			"휠체어"
		);
	private static final RouteFeedbackDashboardView.EtaCalibrationBucketRow ETA_BUCKET_ROW =
		new RouteFeedbackDashboardView.EtaCalibrationBucketRow(
			"휠체어",
			"STEP_FREE",
			"JOURNEY_V3",
			"PLUS_5_MINUTES",
			2,
			"별도 검토"
		);

	@Test
	@DisplayName("constructor snapshots mutable dashboard row lists")
	void snapshotsMutableListInputs() {
		var ratingRows = new ArrayList<>(List.of(RATING_ROW));
		var blockedFeedbacks = new ArrayList<>(List.of(BLOCKED_FEEDBACK_ROW));
		var etaBuckets = new ArrayList<>(List.of(ETA_BUCKET_ROW));
		var view = view(ratingRows, blockedFeedbacks, etaBuckets);

		ratingRows.clear();
		blockedFeedbacks.clear();
		etaBuckets.clear();

		assertThat(view.totalCount()).isEqualTo(6);
		assertThat(view.helpfulCount()).isEqualTo(3);
		assertThat(view.notHelpfulCount()).isEqualTo(2);
		assertThat(view.blockedByRealWorldCount()).isEqualTo(1);
		assertThat(view.ratingRows()).containsExactly(RATING_ROW);
		assertThat(view.recentBlockedFeedbacks()).containsExactly(BLOCKED_FEEDBACK_ROW);
		assertThat(view.etaCalibrationBuckets()).containsExactly(ETA_BUCKET_ROW);
	}

	@Test
	@DisplayName("dashboard row list accessors are immutable")
	void exposesImmutableLists() {
		var view = view(
			new ArrayList<>(List.of(RATING_ROW)),
			new ArrayList<>(List.of(BLOCKED_FEEDBACK_ROW)),
			new ArrayList<>(List.of(ETA_BUCKET_ROW))
		);

		assertThatThrownBy(() -> view.ratingRows().clear())
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> view.recentBlockedFeedbacks().clear())
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> view.etaCalibrationBuckets().clear())
			.isInstanceOf(UnsupportedOperationException.class);
	}

	private static RouteFeedbackDashboardView view(
		List<RouteFeedbackDashboardView.RatingCountRow> ratingRows,
		List<RouteFeedbackDashboardView.RecentBlockedFeedbackRow> blockedFeedbacks,
		List<RouteFeedbackDashboardView.EtaCalibrationBucketRow> etaBuckets
	) {
		return new RouteFeedbackDashboardView(
			6,
			3,
			2,
			1,
			ratingRows,
			blockedFeedbacks,
			etaBuckets
		);
	}
}
