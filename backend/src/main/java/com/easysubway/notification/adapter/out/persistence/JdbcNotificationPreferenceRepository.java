package com.easysubway.notification.adapter.out.persistence;

import com.easysubway.notification.application.port.out.LoadNotificationPreferencePort;
import com.easysubway.notification.application.port.out.SaveNotificationSettingsPort;
import com.easysubway.notification.application.port.out.SaveRegisteredDevicePort;
import com.easysubway.notification.domain.DevicePlatform;
import com.easysubway.notification.domain.NotificationSettings;
import com.easysubway.notification.domain.RegisteredDevice;
import com.easysubway.user.application.port.out.DeleteUserNotificationPreferencePort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("prod | staging | release | prod-like")
public class JdbcNotificationPreferenceRepository implements
	LoadNotificationPreferencePort,
	SaveRegisteredDevicePort,
	SaveNotificationSettingsPort,
	DeleteUserNotificationPreferencePort {

	private final JdbcTemplate jdbcTemplate;
	private final DatabaseDialect databaseDialect;

	@Autowired
	public JdbcNotificationPreferenceRepository(DataSource dataSource) {
		this(new JdbcTemplate(dataSource));
	}

	JdbcNotificationPreferenceRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
		this.databaseDialect = detectDatabaseDialect(jdbcTemplate);
	}

	@Override
	public Optional<NotificationSettings> loadNotificationSettings(String userId) {
		try {
			return Optional.ofNullable(jdbcTemplate.queryForObject(
				"""
					SELECT user_id,
						favorite_station_facility_alerts,
						favorite_route_facility_alerts,
						report_status_alerts,
						data_quality_alerts,
						updated_at
					FROM notification_settings
					WHERE user_id = ?
					""",
				this::mapNotificationSettings,
				userId
			));
		} catch (EmptyResultDataAccessException exception) {
			return Optional.empty();
		}
	}

	@Override
	public List<RegisteredDevice> loadDevices(String userId) {
		return jdbcTemplate.query(
			"""
				SELECT user_id,
					platform,
					device_token,
					registered_at
				FROM registered_devices
				WHERE user_id = ?
				ORDER BY registered_at ASC, device_token ASC
				""",
			this::mapRegisteredDevice,
			userId
		);
	}

	@Override
	@Transactional
	public RegisteredDevice saveRegisteredDevice(RegisteredDevice device) {
		if (databaseDialect == DatabaseDialect.POSTGRESQL) {
			upsertRegisteredDeviceWithPostgresql(device);
			return device;
		}
		saveRegisteredDeviceWithUpdateInsert(device);
		return device;
	}

	private void upsertRegisteredDeviceWithPostgresql(RegisteredDevice device) {
		// 같은 물리 기기가 여러 사용자에게 중복 발송되지 않도록 플랫폼/토큰 단위로 소유자를 하나만 둔다.
		jdbcTemplate.update(
			"""
				INSERT INTO registered_devices (
					user_id,
					platform,
					device_token,
					registered_at
				)
				VALUES (?, ?, ?, ?)
				ON CONFLICT (platform, device_token) DO UPDATE
				SET user_id = EXCLUDED.user_id,
					registered_at = EXCLUDED.registered_at
				""",
			device.userId(),
			device.platform().name(),
			device.deviceToken(),
			device.registeredAt()
		);
	}

	private void saveRegisteredDeviceWithUpdateInsert(RegisteredDevice device) {
		if (updateRegisteredDeviceOwner(device) == 0) {
			insertRegisteredDevice(device);
		}
	}

	@Override
	public NotificationSettings saveNotificationSettings(NotificationSettings settings) {
		if (updateNotificationSettings(settings) == 0) {
			try {
				insertNotificationSettings(settings);
			} catch (DuplicateKeyException exception) {
				updateNotificationSettings(settings);
			}
		}
		return settings;
	}

	@Override
	public boolean deleteNotificationSettings(String userId) {
		return jdbcTemplate.update(
			"""
				DELETE FROM notification_settings
				WHERE user_id = ?
				""",
			userId
		) > 0;
	}

	@Override
	public int deleteRegisteredDevices(String userId) {
		return jdbcTemplate.update(
			"""
				DELETE FROM registered_devices
				WHERE user_id = ?
				""",
			userId
		);
	}

	private int updateNotificationSettings(NotificationSettings settings) {
		return jdbcTemplate.update(
			"""
				UPDATE notification_settings
				SET favorite_station_facility_alerts = ?,
					favorite_route_facility_alerts = ?,
					report_status_alerts = ?,
					data_quality_alerts = ?,
					updated_at = ?
				WHERE user_id = ?
				""",
			settings.favoriteStationFacilityAlerts(),
			settings.favoriteRouteFacilityAlerts(),
			settings.reportStatusAlerts(),
			settings.dataQualityAlerts(),
			settings.updatedAt(),
			settings.userId()
		);
	}

	private void insertNotificationSettings(NotificationSettings settings) {
		jdbcTemplate.update(
			"""
				INSERT INTO notification_settings (
					user_id,
					favorite_station_facility_alerts,
					favorite_route_facility_alerts,
					report_status_alerts,
					data_quality_alerts,
					updated_at
				)
				VALUES (?, ?, ?, ?, ?, ?)
				""",
			settings.userId(),
			settings.favoriteStationFacilityAlerts(),
			settings.favoriteRouteFacilityAlerts(),
			settings.reportStatusAlerts(),
			settings.dataQualityAlerts(),
			settings.updatedAt()
		);
	}

	private void insertRegisteredDevice(RegisteredDevice device) {
		jdbcTemplate.update(
			"""
				INSERT INTO registered_devices (
					user_id,
					platform,
					device_token,
					registered_at
				)
				VALUES (?, ?, ?, ?)
				""",
			device.userId(),
			device.platform().name(),
			device.deviceToken(),
			device.registeredAt()
		);
	}

	private int updateRegisteredDeviceOwner(RegisteredDevice device) {
		return jdbcTemplate.update(
			"""
				UPDATE registered_devices
				SET user_id = ?,
					registered_at = ?
				WHERE platform = ? AND device_token = ?
				""",
			device.userId(),
			device.registeredAt(),
			device.platform().name(),
			device.deviceToken()
		);
	}

	private NotificationSettings mapNotificationSettings(ResultSet resultSet, int rowNumber) throws SQLException {
		return new NotificationSettings(
			resultSet.getString("user_id"),
			resultSet.getBoolean("favorite_station_facility_alerts"),
			resultSet.getBoolean("favorite_route_facility_alerts"),
			resultSet.getBoolean("report_status_alerts"),
			resultSet.getBoolean("data_quality_alerts"),
			resultSet.getTimestamp("updated_at").toLocalDateTime()
		);
	}

	private RegisteredDevice mapRegisteredDevice(ResultSet resultSet, int rowNumber) throws SQLException {
		return new RegisteredDevice(
			resultSet.getString("user_id"),
			DevicePlatform.valueOf(resultSet.getString("platform")),
			resultSet.getString("device_token"),
			resultSet.getTimestamp("registered_at").toLocalDateTime()
		);
	}

	private DatabaseDialect detectDatabaseDialect(JdbcTemplate jdbcTemplate) {
		DatabaseDialect dialect = jdbcTemplate.execute((ConnectionCallback<DatabaseDialect>) connection -> {
			String productName = connection.getMetaData().getDatabaseProductName();
			return "H2".equalsIgnoreCase(productName) ? DatabaseDialect.H2 : DatabaseDialect.POSTGRESQL;
		});
		return dialect == null ? DatabaseDialect.POSTGRESQL : dialect;
	}

	private enum DatabaseDialect {
		POSTGRESQL,
		H2
	}
}
