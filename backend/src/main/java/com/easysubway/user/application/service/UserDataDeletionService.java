package com.easysubway.user.application.service;

import com.easysubway.user.application.port.in.UserDataDeletionUseCase;
import com.easysubway.user.application.port.out.AnonymizeUserFacilityReportPort;
import com.easysubway.user.application.port.out.AnonymizeUserRouteFeedbackPort;
import com.easysubway.user.application.port.out.DeleteUserFavoriteFacilityPort;
import com.easysubway.user.application.port.out.DeleteUserFavoriteRoutePort;
import com.easysubway.user.application.port.out.DeleteUserFavoriteStationPort;
import com.easysubway.user.application.port.out.DeleteUserMobilityProfilePort;
import com.easysubway.user.application.port.out.DeleteUserNotificationPreferencePort;
import com.easysubway.user.application.port.out.DeleteUserPushNotificationPort;
import com.easysubway.user.domain.InvalidUserDataDeletionException;
import com.easysubway.user.domain.UserDataDeletionResult;
import org.springframework.stereotype.Service;

@Service
public class UserDataDeletionService implements UserDataDeletionUseCase {

	private final DeleteUserFavoriteStationPort deleteUserFavoriteStationPort;
	private final DeleteUserFavoriteFacilityPort deleteUserFavoriteFacilityPort;
	private final DeleteUserFavoriteRoutePort deleteUserFavoriteRoutePort;
	private final AnonymizeUserRouteFeedbackPort anonymizeUserRouteFeedbackPort;
	private final DeleteUserNotificationPreferencePort deleteUserNotificationPreferencePort;
	private final DeleteUserPushNotificationPort deleteUserPushNotificationPort;
	private final DeleteUserMobilityProfilePort deleteUserMobilityProfilePort;
	private final AnonymizeUserFacilityReportPort anonymizeUserFacilityReportPort;

	public UserDataDeletionService(
		DeleteUserFavoriteStationPort deleteUserFavoriteStationPort,
		DeleteUserFavoriteFacilityPort deleteUserFavoriteFacilityPort,
		DeleteUserFavoriteRoutePort deleteUserFavoriteRoutePort,
		AnonymizeUserRouteFeedbackPort anonymizeUserRouteFeedbackPort,
		DeleteUserNotificationPreferencePort deleteUserNotificationPreferencePort,
		DeleteUserPushNotificationPort deleteUserPushNotificationPort,
		DeleteUserMobilityProfilePort deleteUserMobilityProfilePort,
		AnonymizeUserFacilityReportPort anonymizeUserFacilityReportPort
	) {
		this.deleteUserFavoriteStationPort = deleteUserFavoriteStationPort;
		this.deleteUserFavoriteFacilityPort = deleteUserFavoriteFacilityPort;
		this.deleteUserFavoriteRoutePort = deleteUserFavoriteRoutePort;
		this.anonymizeUserRouteFeedbackPort = anonymizeUserRouteFeedbackPort;
		this.deleteUserNotificationPreferencePort = deleteUserNotificationPreferencePort;
		this.deleteUserPushNotificationPort = deleteUserPushNotificationPort;
		this.deleteUserMobilityProfilePort = deleteUserMobilityProfilePort;
		this.anonymizeUserFacilityReportPort = anonymizeUserFacilityReportPort;
	}

	@Override
	public UserDataDeletionResult deleteUserData(String userId) {
		String normalizedUserId = normalizeUserId(userId);
		int deletedFavoriteStationCount = deleteUserFavoriteStationPort.deleteFavoriteStationsByUserId(normalizedUserId);
		int deletedFavoriteFacilityCount = deleteUserFavoriteFacilityPort.deleteFavoriteFacilitiesByUserId(normalizedUserId);
		int deletedFavoriteRouteCount = deleteUserFavoriteRoutePort.deleteFavoriteRoutesByUserId(normalizedUserId);
		int anonymizedRouteFeedbackCount = anonymizeUserRouteFeedbackPort.anonymizeRouteFeedbacksByUserId(normalizedUserId);
		boolean notificationSettingsDeleted =
			deleteUserNotificationPreferencePort.deleteNotificationSettings(normalizedUserId);
		int deletedRegisteredDeviceCount =
			deleteUserNotificationPreferencePort.deleteRegisteredDevices(normalizedUserId);
		int deletedPushNotificationCount = deleteUserPushNotificationPort.deletePushNotifications(normalizedUserId);
		boolean mobilityProfileDeleted = deleteUserMobilityProfilePort.deleteMobilityProfile(normalizedUserId);
		int anonymizedReportCount = anonymizeUserFacilityReportPort.anonymizeFacilityReportsByUserId(normalizedUserId);
		return new UserDataDeletionResult(
			normalizedUserId,
			deletedFavoriteStationCount,
			deletedFavoriteFacilityCount,
			deletedFavoriteRouteCount,
			anonymizedRouteFeedbackCount,
			notificationSettingsDeleted,
			deletedRegisteredDeviceCount,
			deletedPushNotificationCount,
			mobilityProfileDeleted,
			anonymizedReportCount
		);
	}

	private String normalizeUserId(String userId) {
		if (userId == null || userId.isBlank()) {
			throw new InvalidUserDataDeletionException("사용자 식별자가 필요합니다.");
		}
		return userId.trim();
	}
}
