package com.easysubway.user.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.user.application.port.out.AnonymizeUserFacilityReportPort;
import com.easysubway.user.application.port.out.AnonymizeUserRouteFeedbackPort;
import com.easysubway.user.application.port.out.DeleteUserFavoriteFacilityPort;
import com.easysubway.user.application.port.out.DeleteUserFavoriteRoutePort;
import com.easysubway.user.application.port.out.DeleteUserFavoriteStationPort;
import com.easysubway.user.application.port.out.DeleteUserMobilityProfilePort;
import com.easysubway.user.application.port.out.DeleteUserNotificationPreferencePort;
import com.easysubway.user.application.port.out.DeleteUserPushNotificationPort;
import com.easysubway.user.domain.InvalidUserDataDeletionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("사용자 데이터 삭제 서비스")
class UserDataDeletionServiceTest {

	@Test
	@DisplayName("사용자 연결 데이터를 삭제하고 신고 기록은 익명화한다")
	void deleteUserDataClearsLinkedDataAndAnonymizesReports() {
		var favoriteStations = new RecordingDeleteUserFavoriteStationPort(2);
		var favoriteFacilities = new RecordingDeleteUserFavoriteFacilityPort(1);
		var favoriteRoutes = new RecordingDeleteUserFavoriteRoutePort(3);
		var routeFeedbacks = new RecordingAnonymizeUserRouteFeedbackPort(6);
		var notificationPreferences = new RecordingDeleteUserNotificationPreferencePort(true, 2);
		var pushNotifications = new RecordingDeleteUserPushNotificationPort(4);
		var mobilityProfile = new RecordingDeleteUserMobilityProfilePort(true);
		var reports = new RecordingAnonymizeUserFacilityReportPort(5);
		var service = new UserDataDeletionService(
			favoriteStations,
			favoriteFacilities,
			favoriteRoutes,
			routeFeedbacks,
			notificationPreferences,
			pushNotifications,
			mobilityProfile,
			reports
		);

		var result = service.deleteUserData(" anonymous-user-1 ");

		assertThat(result.userId()).isEqualTo("anonymous-user-1");
		assertThat(result.deletedFavoriteStationCount()).isEqualTo(2);
		assertThat(result.deletedFavoriteFacilityCount()).isEqualTo(1);
		assertThat(result.deletedFavoriteRouteCount()).isEqualTo(3);
		assertThat(result.anonymizedRouteFeedbackCount()).isEqualTo(6);
		assertThat(result.notificationSettingsDeleted()).isTrue();
		assertThat(result.deletedRegisteredDeviceCount()).isEqualTo(2);
		assertThat(result.deletedPushNotificationCount()).isEqualTo(4);
		assertThat(result.mobilityProfileDeleted()).isTrue();
		assertThat(result.anonymizedReportCount()).isEqualTo(5);
		assertThat(favoriteStations.requestedUserId).isEqualTo("anonymous-user-1");
		assertThat(favoriteFacilities.requestedUserId).isEqualTo("anonymous-user-1");
		assertThat(favoriteRoutes.requestedUserId).isEqualTo("anonymous-user-1");
		assertThat(routeFeedbacks.requestedUserId).isEqualTo("anonymous-user-1");
		assertThat(notificationPreferences.settingsRequestedUserId).isEqualTo("anonymous-user-1");
		assertThat(notificationPreferences.devicesRequestedUserId).isEqualTo("anonymous-user-1");
		assertThat(pushNotifications.requestedUserId).isEqualTo("anonymous-user-1");
		assertThat(mobilityProfile.requestedUserId).isEqualTo("anonymous-user-1");
		assertThat(reports.requestedUserId).isEqualTo("anonymous-user-1");
	}

	@Test
	@DisplayName("사용자 데이터 삭제는 사용자 식별자를 요구한다")
	void deleteUserDataRequiresUserId() {
		var service = new UserDataDeletionService(
			new RecordingDeleteUserFavoriteStationPort(0),
			new RecordingDeleteUserFavoriteFacilityPort(0),
			new RecordingDeleteUserFavoriteRoutePort(0),
			new RecordingAnonymizeUserRouteFeedbackPort(0),
			new RecordingDeleteUserNotificationPreferencePort(false, 0),
			new RecordingDeleteUserPushNotificationPort(0),
			new RecordingDeleteUserMobilityProfilePort(false),
			new RecordingAnonymizeUserFacilityReportPort(0)
		);

		assertThatThrownBy(() -> service.deleteUserData(" "))
			.isInstanceOf(InvalidUserDataDeletionException.class)
			.hasMessage("사용자 식별자가 필요합니다.");
		assertThatThrownBy(() -> service.deleteUserData(null))
			.isInstanceOf(InvalidUserDataDeletionException.class)
			.hasMessage("사용자 식별자가 필요합니다.");
	}

	private static final class RecordingDeleteUserFavoriteStationPort implements DeleteUserFavoriteStationPort {

		private final int count;
		private String requestedUserId;

		private RecordingDeleteUserFavoriteStationPort(int count) {
			this.count = count;
		}

		@Override
		public int deleteFavoriteStationsByUserId(String userId) {
			requestedUserId = userId;
			return count;
		}
	}

	private static final class RecordingDeleteUserFavoriteFacilityPort implements DeleteUserFavoriteFacilityPort {

		private final int count;
		private String requestedUserId;

		private RecordingDeleteUserFavoriteFacilityPort(int count) {
			this.count = count;
		}

		@Override
		public int deleteFavoriteFacilitiesByUserId(String userId) {
			requestedUserId = userId;
			return count;
		}
	}

	private static final class RecordingDeleteUserFavoriteRoutePort implements DeleteUserFavoriteRoutePort {

		private final int count;
		private String requestedUserId;

		private RecordingDeleteUserFavoriteRoutePort(int count) {
			this.count = count;
		}

		@Override
		public int deleteFavoriteRoutesByUserId(String userId) {
			requestedUserId = userId;
			return count;
		}
	}

	private static final class RecordingAnonymizeUserRouteFeedbackPort implements AnonymizeUserRouteFeedbackPort {

		private final int count;
		private String requestedUserId;

		private RecordingAnonymizeUserRouteFeedbackPort(int count) {
			this.count = count;
		}

		@Override
		public int anonymizeRouteFeedbacksByUserId(String userId) {
			requestedUserId = userId;
			return count;
		}
	}

	private static final class RecordingDeleteUserNotificationPreferencePort
		implements DeleteUserNotificationPreferencePort {

		private final boolean settingsDeleted;
		private final int deviceCount;
		private String settingsRequestedUserId;
		private String devicesRequestedUserId;

		private RecordingDeleteUserNotificationPreferencePort(boolean settingsDeleted, int deviceCount) {
			this.settingsDeleted = settingsDeleted;
			this.deviceCount = deviceCount;
		}

		@Override
		public boolean deleteNotificationSettings(String userId) {
			settingsRequestedUserId = userId;
			return settingsDeleted;
		}

		@Override
		public int deleteRegisteredDevices(String userId) {
			devicesRequestedUserId = userId;
			return deviceCount;
		}
	}

	private static final class RecordingDeleteUserPushNotificationPort implements DeleteUserPushNotificationPort {

		private final int count;
		private String requestedUserId;

		private RecordingDeleteUserPushNotificationPort(int count) {
			this.count = count;
		}

		@Override
		public int deletePushNotifications(String userId) {
			requestedUserId = userId;
			return count;
		}
	}

	private static final class RecordingDeleteUserMobilityProfilePort implements DeleteUserMobilityProfilePort {

		private final boolean deleted;
		private String requestedUserId;

		private RecordingDeleteUserMobilityProfilePort(boolean deleted) {
			this.deleted = deleted;
		}

		@Override
		public boolean deleteMobilityProfile(String userId) {
			requestedUserId = userId;
			return deleted;
		}
	}

	private static final class RecordingAnonymizeUserFacilityReportPort implements AnonymizeUserFacilityReportPort {

		private final int count;
		private String requestedUserId;

		private RecordingAnonymizeUserFacilityReportPort(int count) {
			this.count = count;
		}

		@Override
		public int anonymizeFacilityReportsByUserId(String userId) {
			requestedUserId = userId;
			return count;
		}
	}
}
