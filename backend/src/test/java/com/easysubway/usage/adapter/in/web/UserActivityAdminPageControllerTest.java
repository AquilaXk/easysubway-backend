package com.easysubway.usage.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.easysubway.usage.application.port.in.UserActivityDashboardUseCase;
import com.easysubway.usage.domain.UserActivityDashboardSummary;
import com.easysubway.usage.domain.UserActivityDashboardSummary.DailyUserActivity;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;

@DisplayName("관리자 사용자 활동 대시보드")
class UserActivityAdminPageControllerTest {

	@Test
	@DisplayName("관리자는 최근 7일 일별 활성 사용자 현황을 확인한다")
	void adminGetsDailyUserActivityDashboardPage() {
		UserActivityDashboardUseCase useCase = mock(UserActivityDashboardUseCase.class);
		when(useCase.summarizeUserActivity()).thenReturn(new UserActivityDashboardSummary(
			3,
			List.of(
				new DailyUserActivity(LocalDate.of(2026, 6, 17), 2),
				new DailyUserActivity(LocalDate.of(2026, 6, 16), 1)
			)
		));
		var controller = new UserActivityAdminPageController(useCase);
		ExtendedModelMap model = new ExtendedModelMap();

		String viewName = controller.userActivityDashboardPage(model);

		assertThat(viewName).isEqualTo("admin/usage/activity");
		UserActivityAdminPageController.UserActivityDashboardView view =
			(UserActivityAdminPageController.UserActivityDashboardView) model.getAttribute("summary");
		assertThat(view.totalActiveUsers()).isEqualTo(3);
		assertThat(view.dailyActivityRows())
			.extracting(row -> row.dateLabel() + ":" + row.activeUserCount())
			.containsExactly("2026-06-17:2", "2026-06-16:1");
		assertThat(view.toString()).doesNotContain("anonymous-user");
	}
}
