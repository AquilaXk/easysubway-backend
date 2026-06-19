package com.easysubway.user.adapter.in.web;

import com.easysubway.common.web.ApiResponse;
import com.easysubway.user.application.port.in.UserDataDeletionUseCase;
import com.easysubway.user.domain.UserDataDeletionResult;
import java.security.Principal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class UserDataController {

	private final UserDataDeletionUseCase userDataDeletionUseCase;

	UserDataController(UserDataDeletionUseCase userDataDeletionUseCase) {
		this.userDataDeletionUseCase = userDataDeletionUseCase;
	}

	@DeleteMapping("/api/v1/me")
	ApiResponse<UserDataDeletionResponse> deleteCurrentUserData(Principal principal) {
		return ApiResponse.ok(UserDataDeletionResponse.from(
			userDataDeletionUseCase.deleteUserData(principal.getName())
		));
	}

	record UserDataDeletionResponse(
		String userId,
		int deletedFavoriteStationCount,
		int deletedFavoriteFacilityCount,
		int deletedFavoriteRouteCount,
		int anonymizedRouteFeedbackCount,
		boolean notificationSettingsDeleted,
		int deletedRegisteredDeviceCount,
		int deletedPushNotificationCount,
		boolean mobilityProfileDeleted,
		int anonymizedReportCount
	) {

		static UserDataDeletionResponse from(UserDataDeletionResult result) {
			return new UserDataDeletionResponse(
				result.userId(),
				result.deletedFavoriteStationCount(),
				result.deletedFavoriteFacilityCount(),
				result.deletedFavoriteRouteCount(),
				result.anonymizedRouteFeedbackCount(),
				result.notificationSettingsDeleted(),
				result.deletedRegisteredDeviceCount(),
				result.deletedPushNotificationCount(),
				result.mobilityProfileDeleted(),
				result.anonymizedReportCount()
			);
		}
	}
}
