package com.easysubway.usage.adapter.in.web;

import com.easysubway.usage.application.port.in.UserActivityDashboardUseCase;
import com.easysubway.usage.domain.UserActivityDashboardSummary;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class UserActivityAdminPageController {

	private final UserActivityDashboardUseCase userActivityDashboardUseCase;

	UserActivityAdminPageController(UserActivityDashboardUseCase userActivityDashboardUseCase) {
		this.userActivityDashboardUseCase = userActivityDashboardUseCase;
	}

	@GetMapping("/admin/usage/activity/page")
	String userActivityDashboardPage(Model model) {
		model.addAttribute("summary", UserActivityDashboardView.from(userActivityDashboardUseCase.summarizeUserActivity()));
		return "admin/usage/activity";
	}

	record UserActivityDashboardView(
		long totalActiveUsers,
		List<DailyUserActivityRow> dailyActivityRows
	) {

		static UserActivityDashboardView from(UserActivityDashboardSummary summary) {
			return new UserActivityDashboardView(
				summary.totalActiveUsers(),
				summary.dailyActivities()
					.stream()
					.map(row -> new DailyUserActivityRow(row.date().toString(), row.activeUserCount()))
					.toList()
			);
		}
	}

	record DailyUserActivityRow(String dateLabel, long activeUserCount) {
	}
}
