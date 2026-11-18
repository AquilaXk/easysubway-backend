package com.easysubway.notification.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.notification.domain.DevicePlatform;
import com.easysubway.notification.domain.NotificationSettings;
import com.easysubway.notification.domain.RegisteredDevice;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@DisplayName("JDBC 알림 설정 저장소")
class JdbcNotificationPreferenceRepositoryTest {

	private JdbcNotificationPreferenceRepository repository;

	@BeforeEach
	void setUp() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:notification-preferences;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		var jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.execute("DROP TABLE IF EXISTS registered_devices");
		jdbcTemplate.execute("DROP TABLE IF EXISTS notification_settings");
		jdbcTemplate.execute("""
			CREATE TABLE notification_settings (
				user_id VARCHAR(120) NOT NULL PRIMARY KEY,
				favorite_station_facility_alerts BOOLEAN NOT NULL,
				favorite_route_facility_alerts BOOLEAN NOT NULL,
				report_status_alerts BOOLEAN NOT NULL,
				data_quality_alerts BOOLEAN NOT NULL,
				updated_at TIMESTAMP NOT NULL
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE registered_devices (
				user_id VARCHAR(120) NOT NULL,
				platform VARCHAR(20) NOT NULL,
				device_token VARCHAR(255) NOT NULL,
				registered_at TIMESTAMP NOT NULL,
				PRIMARY KEY (user_id, platform, device_token),
				CONSTRAINT uq_registered_devices_platform_token UNIQUE (platform, device_token),
				CONSTRAINT chk_registered_devices_platform CHECK (platform IN ('ANDROID', 'IOS'))
			)
			""");
		repository = new JdbcNotificationPreferenceRepository(jdbcTemplate);
	}

	@Test
	@DisplayName("알림 설정을 저장하고 사용자 식별자로 조회한다")
	void saveNotificationSettingsAndLoadByUserId() {
		var settings = settings("anonymous-user-1", true, false, true, false, 9);

		repository.saveNotificationSettings(settings);

		assertThat(repository.loadNotificationSettings("anonymous-user-1")).contains(settings);
	}

	@Test
	@DisplayName("같은 사용자의 알림 설정은 한 행만 갱신한다")
	void saveNotificationSettingsUpdatesExistingSettings() {
		repository.saveNotificationSettings(settings("anonymous-user-1", true, true, true, false, 9));
		var updatedSettings = settings("anonymous-user-1", false, true, false, true, 10);

		repository.saveNotificationSettings(updatedSettings);

		assertThat(repository.loadNotificationSettings("anonymous-user-1")).contains(updatedSettings);
	}

	@Test
	@DisplayName("등록 기기는 사용자별 등록 시각과 토큰 순서로 조회한다")
	void loadDevicesOrdersByRegisteredAtAndDeviceToken() {
		var laterDevice = device("anonymous-user-1", DevicePlatform.ANDROID, "token-c", 10);
		var secondDevice = device("anonymous-user-1", DevicePlatform.IOS, "token-b", 9);
		var firstDevice = device("anonymous-user-1", DevicePlatform.ANDROID, "token-a", 9);
		repository.saveRegisteredDevice(laterDevice);
		repository.saveRegisteredDevice(secondDevice);
		repository.saveRegisteredDevice(firstDevice);
		repository.saveRegisteredDevice(device("anonymous-user-2", DevicePlatform.IOS, "token-d", 8));

		assertThat(repository.loadDevices("anonymous-user-1"))
			.containsExactly(firstDevice, secondDevice, laterDevice);
	}

	@Test
	@DisplayName("같은 기기 토큰은 마지막으로 등록한 사용자에게만 연결된다")
	void saveRegisteredDeviceMovesTokenToLatestUser() {
		repository.saveRegisteredDevice(device("anonymous-user-1", DevicePlatform.ANDROID, "shared-token", 9));
		var latestDevice = device("anonymous-user-2", DevicePlatform.ANDROID, "shared-token", 10);

		repository.saveRegisteredDevice(latestDevice);

		assertThat(repository.loadDevices("anonymous-user-1")).isEmpty();
		assertThat(repository.loadDevices("anonymous-user-2")).containsExactly(latestDevice);
	}

	@Test
	@DisplayName("사용자 데이터 삭제 요청은 알림 설정과 등록 기기를 삭제한다")
	void deleteNotificationSettingsAndRegisteredDevicesByUserId() {
		repository.saveNotificationSettings(settings("anonymous-user-1", true, true, true, false, 9));
		repository.saveNotificationSettings(settings("anonymous-user-2", false, false, true, true, 9));
		repository.saveRegisteredDevice(device("anonymous-user-1", DevicePlatform.ANDROID, "token-a", 9));
		repository.saveRegisteredDevice(device("anonymous-user-1", DevicePlatform.IOS, "token-b", 10));
		repository.saveRegisteredDevice(device("anonymous-user-2", DevicePlatform.IOS, "token-c", 8));

		boolean deletedSettings = repository.deleteNotificationSettings("anonymous-user-1");
		int deletedDeviceCount = repository.deleteRegisteredDevices("anonymous-user-1");
		boolean deletedSettingsAgain = repository.deleteNotificationSettings("anonymous-user-1");
		int deletedDeviceAgainCount = repository.deleteRegisteredDevices("anonymous-user-1");

		assertThat(deletedSettings).isTrue();
		assertThat(deletedDeviceCount).isEqualTo(2);
		assertThat(deletedSettingsAgain).isFalse();
		assertThat(deletedDeviceAgainCount).isZero();
		assertThat(repository.loadNotificationSettings("anonymous-user-1")).isEmpty();
		assertThat(repository.loadDevices("anonymous-user-1")).isEmpty();
		assertThat(repository.loadNotificationSettings("anonymous-user-2"))
			.contains(settings("anonymous-user-2", false, false, true, true, 9));
		assertThat(repository.loadDevices("anonymous-user-2"))
			.containsExactly(device("anonymous-user-2", DevicePlatform.IOS, "token-c", 8));
	}

	private NotificationSettings settings(
		String userId,
		boolean favoriteStationFacilityAlerts,
		boolean favoriteRouteFacilityAlerts,
		boolean reportStatusAlerts,
		boolean dataQualityAlerts,
		int hour
	) {
		return new NotificationSettings(
			userId,
			favoriteStationFacilityAlerts,
			favoriteRouteFacilityAlerts,
			reportStatusAlerts,
			dataQualityAlerts,
			LocalDateTime.of(2026, 6, 17, hour, 0)
		);
	}

	private RegisteredDevice device(String userId, DevicePlatform platform, String deviceToken, int hour) {
		return new RegisteredDevice(
			userId,
			platform,
			deviceToken,
			LocalDateTime.of(2026, 6, 17, hour, 0)
		);
	}
}
