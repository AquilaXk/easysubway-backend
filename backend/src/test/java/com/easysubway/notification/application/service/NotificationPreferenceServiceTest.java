package com.easysubway.notification.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.notification.adapter.out.persistence.InMemoryNotificationPreferenceRepository;
import com.easysubway.notification.application.port.in.RegisterDeviceCommand;
import com.easysubway.notification.application.port.in.SaveNotificationSettingsCommand;
import com.easysubway.notification.domain.DevicePlatform;
import com.easysubway.notification.domain.InvalidNotificationPreferenceException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("알림 설정 서비스")
class NotificationPreferenceServiceTest {

	private final InMemoryNotificationPreferenceRepository repository = new InMemoryNotificationPreferenceRepository();
	private final NotificationPreferenceService service = new NotificationPreferenceService(
		repository,
		repository,
		repository,
		Clock.fixed(Instant.parse("2026-06-13T00:00:00Z"), ZoneId.of("Asia/Seoul"))
	);

	@Test
	@DisplayName("새 사용자는 이동 안전에 필요한 기본 알림을 켠 상태로 조회한다")
	void getSettingsReturnsDefaultEnabledSettings() {
		var settings = service.getNotificationSettings("anonymous-user-1");

		assertThat(settings.userId()).isEqualTo("anonymous-user-1");
		assertThat(settings.favoriteStationFacilityAlerts()).isTrue();
		assertThat(settings.favoriteRouteFacilityAlerts()).isTrue();
		assertThat(settings.reportStatusAlerts()).isTrue();
		assertThat(settings.dataQualityAlerts()).isFalse();
		assertThat(settings.updatedAt()).isEqualTo(LocalDateTime.of(2026, 6, 13, 9, 0));
	}

	@Test
	@DisplayName("사용자는 알림 종류별 수신 여부를 저장할 수 있다")
	void saveSettingsStoresChannelPreferences() {
		var settings = service.saveNotificationSettings(new SaveNotificationSettingsCommand(
			"anonymous-user-1",
			true,
			false,
			true,
			true
		));

		assertThat(settings.favoriteStationFacilityAlerts()).isTrue();
		assertThat(settings.favoriteRouteFacilityAlerts()).isFalse();
		assertThat(settings.reportStatusAlerts()).isTrue();
		assertThat(settings.dataQualityAlerts()).isTrue();
		assertThat(settings.updatedAt()).isEqualTo(LocalDateTime.of(2026, 6, 13, 9, 0));
		assertThat(service.getNotificationSettings("anonymous-user-1")).isEqualTo(settings);
	}

	@Test
	@DisplayName("기기 토큰은 사용자와 플랫폼 기준으로 저장하고 같은 토큰은 갱신한다")
	void registerDeviceStoresAndUpdatesDeviceToken() {
		var device = service.registerDevice(new RegisterDeviceCommand(
			"anonymous-user-1",
			DevicePlatform.ANDROID,
			"device-token-1"
		));
		var updated = service.registerDevice(new RegisterDeviceCommand(
			"anonymous-user-1",
			DevicePlatform.ANDROID,
			"device-token-1"
		));

		assertThat(device.userId()).isEqualTo("anonymous-user-1");
		assertThat(device.platform()).isEqualTo(DevicePlatform.ANDROID);
		assertThat(device.deviceToken()).isEqualTo("device-token-1");
		assertThat(device.registeredAt()).isEqualTo(LocalDateTime.of(2026, 6, 13, 9, 0));
		assertThat(updated).isEqualTo(device);
		assertThat(repository.loadDevices("anonymous-user-1")).containsExactly(device);
	}

	@Test
	@DisplayName("같은 기기 토큰은 새 사용자 등록 시 이전 사용자 목록에서 제거한다")
	void registerDeviceMovesTokenOwnershipToLatestUser() {
		var oldOwnerDevice = service.registerDevice(new RegisterDeviceCommand(
			"anonymous-user-1",
			DevicePlatform.IOS,
			"shared-device-token"
		));
		var newOwnerDevice = service.registerDevice(new RegisterDeviceCommand(
			"anonymous-user-2",
			DevicePlatform.IOS,
			"shared-device-token"
		));

		assertThat(oldOwnerDevice.userId()).isEqualTo("anonymous-user-1");
		assertThat(newOwnerDevice.userId()).isEqualTo("anonymous-user-2");
		assertThat(repository.loadDevices("anonymous-user-1")).isEmpty();
		assertThat(repository.loadDevices("anonymous-user-2")).containsExactly(newOwnerDevice);
	}

	@Test
	@DisplayName("알림 명령은 사용자, 플랫폼, 기기 토큰을 요구한다")
	void notificationCommandsRequireUserPlatformAndToken() {
		assertThatThrownBy(() -> service.getNotificationSettings(""))
			.isInstanceOf(InvalidNotificationPreferenceException.class)
			.hasMessage("사용자 식별자가 필요합니다.");

		assertThatThrownBy(() -> service.registerDevice(new RegisterDeviceCommand(
			"anonymous-user-1",
			null,
			"device-token-1"
		)))
			.isInstanceOf(InvalidNotificationPreferenceException.class)
			.hasMessage("기기 플랫폼을 선택해야 합니다.");

		assertThatThrownBy(() -> service.registerDevice(new RegisterDeviceCommand(
			"anonymous-user-1",
			DevicePlatform.IOS,
			""
		)))
			.isInstanceOf(InvalidNotificationPreferenceException.class)
			.hasMessage("기기 토큰이 필요합니다.");
	}
}
