package com.easysubway.route.adapter.in.web;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.application.port.in.RouteFeedbackDashboardUseCase;
import com.easysubway.route.domain.RouteFeedbackDashboardSummary;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class RouteFeedbackAdminPageController {

	private final RouteFeedbackDashboardUseCase routeFeedbackDashboardUseCase;

	RouteFeedbackAdminPageController(RouteFeedbackDashboardUseCase routeFeedbackDashboardUseCase) {
		this.routeFeedbackDashboardUseCase = routeFeedbackDashboardUseCase;
	}

	@GetMapping("/admin/routes/feedback/page")
	String routeFeedbackDashboardPage(Model model) {
		RouteFeedbackDashboardSummary summary = routeFeedbackDashboardUseCase.summarizeRouteFeedbacks();
		model.addAttribute("summary", RouteFeedbackDashboardView.from(summary));
		return "admin/routes/feedback";
	}

	record RouteFeedbackDashboardView(
		long totalCount,
		long helpfulCount,
		long notHelpfulCount,
		long blockedByRealWorldCount,
		List<RatingCountRow> ratingRows,
		List<RecentBlockedFeedbackRow> recentBlockedFeedbacks
	) {

		private static final DateTimeFormatter RECENT_FEEDBACK_TIME_FORMATTER =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

		static RouteFeedbackDashboardView from(RouteFeedbackDashboardSummary summary) {
			return new RouteFeedbackDashboardView(
				summary.totalCount(),
				summary.helpfulCount(),
				summary.notHelpfulCount(),
				summary.blockedByRealWorldCount(),
				List.of(
					new RatingCountRow("도움이 됨", "경로 안내가 실제 이동에 도움됨", summary.helpfulCount()),
					new RatingCountRow("도움이 안 됨", "경로 안내가 실제 이동과 맞지 않음", summary.notHelpfulCount()),
					new RatingCountRow("현장 차단", "엘리베이터 고장, 공사, 폐쇄 등으로 이동 불가", summary.blockedByRealWorldCount())
				),
				summary.recentBlockedFeedbacks()
					.stream()
					.map(row -> new RecentBlockedFeedbackRow(
						row.createdAt().format(RECENT_FEEDBACK_TIME_FORMATTER),
						row.originStationName(),
						row.destinationStationName(),
						mobilityTypeLabel(row.mobilityType())
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

	record RatingCountRow(String label, String description, long count) {
	}

	record RecentBlockedFeedbackRow(
		String createdAtLabel,
		String originStationName,
		String destinationStationName,
		String mobilityTypeLabel
	) {
	}
}
