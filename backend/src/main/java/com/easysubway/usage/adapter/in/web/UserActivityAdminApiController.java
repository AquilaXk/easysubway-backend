package com.easysubway.usage.adapter.in.web;

import com.easysubway.common.web.ApiResponse;
import com.easysubway.usage.application.port.in.UserActivityDashboardUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class UserActivityAdminApiController {

	private final UserActivityDashboardUseCase userActivityDashboardUseCase;

	UserActivityAdminApiController(UserActivityDashboardUseCase userActivityDashboardUseCase) {
		this.userActivityDashboardUseCase = userActivityDashboardUseCase;
	}

	@GetMapping("/admin/usage/activity/summary")
	ApiResponse<UserActivityDashboardView> userActivitySummary() {
		return ApiResponse.ok(UserActivityDashboardView.from(userActivityDashboardUseCase.summarizeUserActivity()));
	}
}
