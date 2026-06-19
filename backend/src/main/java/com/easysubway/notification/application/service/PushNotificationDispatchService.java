package com.easysubway.notification.application.service;

import com.easysubway.notification.application.port.in.DispatchPushNotificationCommand;
import com.easysubway.notification.application.port.in.PushNotificationDispatchUseCase;
import com.easysubway.notification.application.port.out.LoadNotificationPreferencePort;
import com.easysubway.notification.application.port.out.SavePushNotificationOutboxPort;
import com.easysubway.notification.domain.NotificationSettings;
import com.easysubway.notification.domain.PushNotification;
import com.easysubway.notification.domain.PushNotificationDispatchResult;
import com.easysubway.notification.domain.PushNotificationStatus;
import com.easysubway.notification.domain.PushNotificationType;
import com.easysubway.notification.domain.RegisteredDevice;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PushNotificationDispatchService implements PushNotificationDispatchUseCase {

	private final LoadNotificationPreferencePort loadNotificationPreferencePort;
	private final SavePushNotificationOutboxPort savePushNotificationOutboxPort;
	private final Clock clock;

	@Autowired
	public PushNotificationDispatchService(
		LoadNotificationPreferencePort loadNotificationPreferencePort,
		SavePushNotificationOutboxPort savePushNotificationOutboxPort
	) {
		this(loadNotificationPreferencePort, savePushNotificationOutboxPort, Clock.systemDefaultZone());
	}

	public PushNotificationDispatchService(
		LoadNotificationPreferencePort loadNotificationPreferencePort,
		SavePushNotificationOutboxPort savePushNotificationOutboxPort,
		Clock clock
	) {
		this.loadNotificationPreferencePort = loadNotificationPreferencePort;
		this.savePushNotificationOutboxPort = savePushNotificationOutboxPort;
		this.clock = clock;
	}

	@Override
	public PushNotificationDispatchResult dispatch(DispatchPushNotificationCommand command) {
		NotificationSettings settings = loadNotificationPreferencePort.loadNotificationSettings(command.userId())
			.orElseGet(() -> defaultSettings(command.userId()));
		if (!isEnabled(settings, command.type())) {
			return emptyResult(command);
		}

		List<PushNotification> savedNotifications = new ArrayList<>();
		for (var device : loadNotificationPreferencePort.loadDevices(command.userId())) {
			var notification = new PushNotification(
				notificationId(command, device),
				command.userId(),
				device.platform(),
				device.deviceToken(),
				command.type(),
				command.title(),
				command.body(),
				PushNotificationStatus.PENDING,
				LocalDateTime.now(clock)
			);
			savedNotifications.add(savePushNotificationOutboxPort.savePendingPushNotificationIfAbsent(notification));
		}

		return new PushNotificationDispatchResult(
			command.userId(),
			command.type(),
			savedNotifications.size(),
			savedNotifications
		);
	}

	private String notificationId(DispatchPushNotificationCommand command, RegisteredDevice device) {
		if (command.idempotencyKey() == null) {
			return "push-" + UUID.randomUUID();
		}
		String key = "%s|%s|%s".formatted(command.idempotencyKey(), device.platform(), device.deviceToken());
		return "push-" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
	}

	private PushNotificationDispatchResult emptyResult(DispatchPushNotificationCommand command) {
		return new PushNotificationDispatchResult(command.userId(), command.type(), 0, List.of());
	}

	private NotificationSettings defaultSettings(String userId) {
		// 사용자가 설정을 열기 전에도 핵심 이동 안전 알림은 받을 수 있게 기존 기본값과 동일하게 맞춘다.
		return new NotificationSettings(
			userId,
			true,
			true,
			true,
			false,
			LocalDateTime.now(clock)
		);
	}

	private boolean isEnabled(NotificationSettings settings, PushNotificationType type) {
		return switch (type) {
			case FAVORITE_STATION_FACILITY -> settings.favoriteStationFacilityAlerts();
			case FAVORITE_ROUTE_FACILITY -> settings.favoriteRouteFacilityAlerts();
			case REPORT_STATUS -> settings.reportStatusAlerts();
			case DATA_QUALITY -> settings.dataQualityAlerts();
		};
	}
}
