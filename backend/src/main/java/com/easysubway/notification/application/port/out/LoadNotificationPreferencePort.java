package com.easysubway.notification.application.port.out;

import com.easysubway.notification.domain.NotificationSettings;
import com.easysubway.notification.domain.RegisteredDevice;
import java.util.List;
import java.util.Optional;

public interface LoadNotificationPreferencePort {

	Optional<NotificationSettings> loadNotificationSettings(String userId);

	List<RegisteredDevice> loadDevices(String userId);
}
