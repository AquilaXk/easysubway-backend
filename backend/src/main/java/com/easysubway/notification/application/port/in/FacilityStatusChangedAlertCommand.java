package com.easysubway.notification.application.port.in;

import com.easysubway.notification.domain.InvalidPushNotificationException;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;

public record FacilityStatusChangedAlertCommand(
	String facilityId,
	AccessibilityFacilityStatus status
) {

	public FacilityStatusChangedAlertCommand {
		if (facilityId == null || facilityId.isBlank()) {
			throw new InvalidPushNotificationException("시설 식별자가 필요합니다.");
		}
		if (status == null) {
			throw new InvalidPushNotificationException("시설 상태를 선택해야 합니다.");
		}
		facilityId = facilityId.trim();
	}
}
