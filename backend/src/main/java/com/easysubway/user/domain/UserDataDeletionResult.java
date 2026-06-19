package com.easysubway.user.domain;

public record UserDataDeletionResult(
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

	public UserDataDeletionResult {
		if (userId == null || userId.isBlank()) {
			throw new InvalidUserDataDeletionException("사용자 식별자가 필요합니다.");
		}
		if (deletedFavoriteStationCount < 0
			|| deletedFavoriteFacilityCount < 0
			|| deletedFavoriteRouteCount < 0
			|| anonymizedRouteFeedbackCount < 0
			|| deletedRegisteredDeviceCount < 0
			|| deletedPushNotificationCount < 0
			|| anonymizedReportCount < 0) {
			throw new InvalidUserDataDeletionException("삭제 결과 건수는 음수일 수 없습니다.");
		}
	}
}
