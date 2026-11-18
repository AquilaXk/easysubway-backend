package com.easysubway.notification.application.port.in;

import com.easysubway.notification.domain.InvalidNotificationPreferenceException;

public record SaveNotificationSettingsCommand(
	String userId,
	boolean favoriteStationFacilityAlerts,
	boolean favoriteRouteFacilityAlerts,
	boolean reportStatusAlerts,
	boolean dataQualityAlerts
) {

	public SaveNotificationSettingsCommand {
		if (userId == null || userId.isBlank()) {
			throw new InvalidNotificationPreferenceException("사용자 식별자가 필요합니다.");
		}
		userId = userId.trim();
	}
}
