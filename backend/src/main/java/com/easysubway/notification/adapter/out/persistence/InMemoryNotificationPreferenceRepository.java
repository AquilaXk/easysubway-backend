package com.easysubway.notification.adapter.out.persistence;

import com.easysubway.notification.application.port.out.LoadNotificationPreferencePort;
import com.easysubway.notification.application.port.out.SaveNotificationSettingsPort;
import com.easysubway.notification.application.port.out.SaveRegisteredDevicePort;
import com.easysubway.notification.domain.DevicePlatform;
import com.easysubway.notification.domain.NotificationSettings;
import com.easysubway.notification.domain.RegisteredDevice;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryNotificationPreferenceRepository implements
	LoadNotificationPreferencePort,
	SaveRegisteredDevicePort,
	SaveNotificationSettingsPort {

	private final Map<String, NotificationSettings> settingsByUserId = new ConcurrentHashMap<>();
	private final Map<String, Map<DeviceKey, RegisteredDevice>> devicesByUserId = new ConcurrentHashMap<>();

	@Override
	public Optional<NotificationSettings> loadNotificationSettings(String userId) {
		return Optional.ofNullable(settingsByUserId.get(userId));
	}

	@Override
	public List<RegisteredDevice> loadDevices(String userId) {
		return devicesByUserId.getOrDefault(userId, Map.of())
			.values()
			.stream()
			.sorted(Comparator
				.comparing(RegisteredDevice::registeredAt)
				.thenComparing(RegisteredDevice::deviceToken))
			.toList();
	}

	@Override
	public RegisteredDevice saveRegisteredDevice(RegisteredDevice device) {
		var deviceKey = new DeviceKey(device.platform(), device.deviceToken());
		// 한 물리 기기는 한 사용자에게만 알림이 가야 하므로 재등록 시 이전 소유자 버킷에서 제거한다.
		devicesByUserId.values().forEach(devices -> devices.remove(deviceKey));
		devicesByUserId
			.computeIfAbsent(device.userId(), ignored -> new ConcurrentHashMap<>())
			.put(deviceKey, device);
		return device;
	}

	@Override
	public NotificationSettings saveNotificationSettings(NotificationSettings settings) {
		settingsByUserId.put(settings.userId(), settings);
		return settings;
	}

	private record DeviceKey(DevicePlatform platform, String deviceToken) {
	}
}
