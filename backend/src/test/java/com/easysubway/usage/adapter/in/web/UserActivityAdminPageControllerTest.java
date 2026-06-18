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
			12,
			2,
			3_240,
			List.of(
				new DailyUserActivity(LocalDate.of(2026, 6, 17), 2, 7, 1, 1_400),
				new DailyUserActivity(LocalDate.of(2026, 6, 16), 1, 5, 1, 1_840)
			)
		));
		var controller = new UserActivityAdminPageController(useCase);
		ExtendedModelMap model = new ExtendedModelMap();

		String viewName = controller.userActivityDashboardPage(model);

		assertThat(viewName).isEqualTo("admin/usage/activity");
		UserActivityDashboardView view = (UserActivityDashboardView) model.getAttribute("summary");
		assertThat(view.totalActiveUsers()).isEqualTo(3);
		assertThat(view.totalApiRequests()).isEqualTo(12);
		assertThat(view.totalApiErrors()).isEqualTo(2);
		assertThat(view.apiErrorRatePercent()).isEqualTo("16.7%");
		assertThat(view.apiErrorAlertLabel()).isEqualTo("점검 필요");
		assertThat(view.apiErrorAlertDescription()).isEqualTo("최근 7일 API 오류율이 5% 이상입니다.");
		assertThat(view.apiErrorAlertClass()).isEqualTo("warning");
		assertThat(view.averageApiResponseMillis()).isEqualTo(270);
		assertThat(view.averageApiResponseTimeLabel()).isEqualTo("270ms");
		assertThat(view.dailyActivityRows())
			.extracting(row -> row.dateLabel() + ":" + row.activeUserCount() + ":" + row.apiRequestCount() + ":" + row.apiErrorCount() + ":" + row.apiErrorRatePercent() + ":" + row.averageApiResponseTimeLabel())
			.containsExactly("2026-06-17:2:7:1:14.3%:200ms", "2026-06-16:1:5:1:20.0%:368ms");
		assertThat(view.toString()).doesNotContain("anonymous-user");
	}

	@Test
	@DisplayName("사용자 활동 대시보드는 API 오류율이 정확히 5퍼센트면 점검 필요로 표시한다")
	void userActivityDashboardRequiresInspectionAtApiErrorRateBoundary() {
		var view = UserActivityDashboardView.from(new UserActivityDashboardSummary(
			1,
			20,
			1,
			2_000,
			List.of(new DailyUserActivity(LocalDate.of(2026, 6, 17), 1, 20, 1, 2_000))
		));

		assertThat(view.apiErrorRatePercent()).isEqualTo("5.0%");
		assertThat(view.apiErrorAlertLabel()).isEqualTo("점검 필요");
		assertThat(view.apiErrorAlertClass()).isEqualTo("warning");
	}

	@Test
	@DisplayName("사용자 활동 대시보드는 API 요청이 없으면 정상으로 표시한다")
	void userActivityDashboardIsOkWhenThereAreNoApiRequests() {
		var view = UserActivityDashboardView.from(new UserActivityDashboardSummary(
			0,
			0,
			0,
			0,
			List.of()
		));

		assertThat(view.apiErrorRatePercent()).isEqualTo("0.0%");
		assertThat(view.apiErrorAlertLabel()).isEqualTo("정상");
		assertThat(view.apiErrorAlertClass()).isEqualTo("ok");
	}
}
