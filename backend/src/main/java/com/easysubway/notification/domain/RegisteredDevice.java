package com.easysubway.notification.domain;

import java.time.LocalDateTime;

public record RegisteredDevice(
	String userId,
	DevicePlatform platform,
	String deviceToken,
	LocalDateTime registeredAt
) {

	public RegisteredDevice {
		if (userId == null || userId.isBlank()) {
			throw new InvalidNotificationPreferenceException("사용자 식별자가 필요합니다.");
		}
		if (platform == null) {
			throw new InvalidNotificationPreferenceException("기기 플랫폼을 선택해야 합니다.");
		}
		if (deviceToken == null || deviceToken.isBlank()) {
			throw new InvalidNotificationPreferenceException("기기 토큰이 필요합니다.");
		}
		if (registeredAt == null) {
			throw new InvalidNotificationPreferenceException("기기 등록 시간이 필요합니다.");
		}
		userId = userId.trim();
		deviceToken = deviceToken.trim();
	}
}
