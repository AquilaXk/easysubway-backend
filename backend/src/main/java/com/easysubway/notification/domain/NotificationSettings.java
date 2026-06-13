package com.easysubway.notification.domain;

import java.time.LocalDateTime;

public record NotificationSettings(
	String userId,
	boolean favoriteStationFacilityAlerts,
	boolean favoriteRouteFacilityAlerts,
	boolean reportStatusAlerts,
	boolean dataQualityAlerts,
	LocalDateTime updatedAt
) {

	public NotificationSettings {
		if (userId == null || userId.isBlank()) {
			throw new InvalidNotificationPreferenceException("사용자 식별자가 필요합니다.");
		}
		if (updatedAt == null) {
			throw new InvalidNotificationPreferenceException("알림 설정 시간이 필요합니다.");
		}
		userId = userId.trim();
	}
}
