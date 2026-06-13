package com.easysubway.notification.application.port.out;

import com.easysubway.notification.domain.NotificationSettings;

public interface SaveNotificationSettingsPort {

	NotificationSettings saveNotificationSettings(NotificationSettings settings);
}
