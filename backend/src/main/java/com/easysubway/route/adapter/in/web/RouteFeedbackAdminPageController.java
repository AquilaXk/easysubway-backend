package com.easysubway.route.adapter.in.web;

import com.easysubway.route.application.port.in.RouteFeedbackDashboardUseCase;
import com.easysubway.route.domain.RouteFeedbackDashboardSummary;
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
		List<RatingCountRow> ratingRows
	) {

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
				)
			);
		}
	}

	record RatingCountRow(String label, String description, long count) {
	}
}
