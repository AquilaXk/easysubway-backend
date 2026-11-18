package com.easysubway.route.adapter.in.web;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.application.port.in.RouteFeedbackDashboardUseCase;
import com.easysubway.route.domain.RouteFeedbackDashboardSummary;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class RouteFeedbackDashboardAssembler {

	private static final DateTimeFormatter RECENT_FEEDBACK_TIME_FORMATTER =
		DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	private final RouteFeedbackDashboardUseCase routeFeedbackDashboardUseCase;

	RouteFeedbackDashboardAssembler(RouteFeedbackDashboardUseCase routeFeedbackDashboardUseCase) {
		this.routeFeedbackDashboardUseCase = routeFeedbackDashboardUseCase;
	}

	public RouteFeedbackDashboardView assemble() {
		RouteFeedbackDashboardSummary summary = routeFeedbackDashboardUseCase.summarizeRouteFeedbacks();
		return new RouteFeedbackDashboardView(
			summary.totalCount(),
			summary.helpfulCount(),
			summary.notHelpfulCount(),
			summary.blockedByRealWorldCount(),
			List.of(
				new RouteFeedbackDashboardView.RatingCountRow(
					"도움이 됨",
					"경로 안내가 실제 이동에 도움됨",
					summary.helpfulCount()
				),
				new RouteFeedbackDashboardView.RatingCountRow(
					"도움이 안 됨",
					"경로 안내가 실제 이동과 맞지 않음",
					summary.notHelpfulCount()
				),
				new RouteFeedbackDashboardView.RatingCountRow(
					"현장 차단",
					"엘리베이터 고장, 공사, 폐쇄 등으로 이동 불가",
					summary.blockedByRealWorldCount()
				)
			),
			summary.recentBlockedFeedbacks()
				.stream()
				.map(row -> new RouteFeedbackDashboardView.RecentBlockedFeedbackRow(
					row.createdAt().format(RECENT_FEEDBACK_TIME_FORMATTER),
					row.originStationName(),
					row.destinationStationName(),
					mobilityTypeLabel(row.mobilityType())
				))
				.toList(),
			summary.etaCalibrationBuckets()
				.stream()
				.map(row -> new RouteFeedbackDashboardView.EtaCalibrationBucketRow(
					mobilityTypeLabel(row.mobilityType()),
					row.constraintMode().name(),
					row.etaSource().name(),
					row.etaOffsetBucket().name(),
					row.count(),
					"board/transfer slack 기본값 변경은 별도 검토 PR로 반영"
				))
				.toList()
		);
	}

	private static String mobilityTypeLabel(MobilityType mobilityType) {
		return switch (mobilityType) {
			case SENIOR -> "고령자";
			case STROLLER -> "유모차";
			case WHEELCHAIR -> "휠체어";
			case PREGNANT -> "임산부";
			case TEMPORARY_INJURY -> "일시 부상";
			case LUGGAGE -> "큰 짐";
		};
	}
}
