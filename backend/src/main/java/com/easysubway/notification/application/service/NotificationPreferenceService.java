package com.easysubway.notification.application.service;

import com.easysubway.notification.application.port.in.NotificationPreferenceUseCase;
import com.easysubway.notification.application.port.in.RegisterDeviceCommand;
import com.easysubway.notification.application.port.in.SaveNotificationSettingsCommand;
import com.easysubway.notification.application.port.out.LoadNotificationPreferencePort;
import com.easysubway.notification.application.port.out.SaveNotificationSettingsPort;
import com.easysubway.notification.application.port.out.SaveRegisteredDevicePort;
import com.easysubway.notification.domain.InvalidNotificationPreferenceException;
import com.easysubway.notification.domain.NotificationSettings;
import com.easysubway.notification.domain.RegisteredDevice;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class NotificationPreferenceService implements NotificationPreferenceUseCase {

	private final LoadNotificationPreferencePort loadNotificationPreferencePort;
	private final SaveRegisteredDevicePort saveRegisteredDevicePort;
	private final SaveNotificationSettingsPort saveNotificationSettingsPort;
	private final Clock clock;

	@Autowired
	public NotificationPreferenceService(
		LoadNotificationPreferencePort loadNotificationPreferencePort,
		SaveRegisteredDevicePort saveRegisteredDevicePort,
		SaveNotificationSettingsPort saveNotificationSettingsPort
	) {
		this(
			loadNotificationPreferencePort,
			saveRegisteredDevicePort,
			saveNotificationSettingsPort,
			Clock.systemDefaultZone()
		);
	}

	public NotificationPreferenceService(
		LoadNotificationPreferencePort loadNotificationPreferencePort,
		SaveRegisteredDevicePort saveRegisteredDevicePort,
		SaveNotificationSettingsPort saveNotificationSettingsPort,
		Clock clock
	) {
		this.loadNotificationPreferencePort = loadNotificationPreferencePort;
		this.saveRegisteredDevicePort = saveRegisteredDevicePort;
		this.saveNotificationSettingsPort = saveNotificationSettingsPort;
		this.clock = clock;
	}

	@Override
	public RegisteredDevice registerDevice(RegisterDeviceCommand command) {
		var device = new RegisteredDevice(
			command.userId(),
			command.platform(),
			command.deviceToken(),
			LocalDateTime.now(clock)
		);
		return saveRegisteredDevicePort.saveRegisteredDevice(device);
	}

	@Override
	public NotificationSettings getNotificationSettings(String userId) {
		String normalizedUserId = requireUserId(userId);
		return loadNotificationPreferencePort.loadNotificationSettings(normalizedUserId)
			.orElseGet(() -> defaultSettings(normalizedUserId));
	}

	@Override
	public NotificationSettings saveNotificationSettings(SaveNotificationSettingsCommand command) {
		var settings = new NotificationSettings(
			command.userId(),
			command.favoriteStationFacilityAlerts(),
			command.favoriteRouteFacilityAlerts(),
			command.reportStatusAlerts(),
			command.dataQualityAlerts(),
			LocalDateTime.now(clock)
		);
		return saveNotificationSettingsPort.saveNotificationSettings(settings);
	}

	private NotificationSettings defaultSettings(String userId) {
		// 주요 이동 안전 알림은 기본 수신으로 두고, 데이터 품질 알림만 사용자가 켜도록 둔다.
		return new NotificationSettings(
			userId,
			true,
			true,
			true,
			false,
			LocalDateTime.now(clock)
		);
	}

	private String requireUserId(String userId) {
		if (userId == null || userId.isBlank()) {
			throw new InvalidNotificationPreferenceException("사용자 식별자가 필요합니다.");
		}
		return userId.trim();
	}
}
