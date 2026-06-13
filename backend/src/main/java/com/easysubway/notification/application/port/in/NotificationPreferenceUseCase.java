package com.easysubway.notification.application.port.in;

import com.easysubway.notification.domain.NotificationSettings;
import com.easysubway.notification.domain.RegisteredDevice;

public interface NotificationPreferenceUseCase {

	RegisteredDevice registerDevice(RegisterDeviceCommand command);

	NotificationSettings getNotificationSettings(String userId);

	NotificationSettings saveNotificationSettings(SaveNotificationSettingsCommand command);
}
