package com.easysubway.notification.adapter.in.web;

import com.easysubway.common.web.ApiResponse;
import com.easysubway.notification.application.port.in.NotificationPreferenceUseCase;
import com.easysubway.notification.application.port.in.RegisterDeviceCommand;
import com.easysubway.notification.application.port.in.SaveNotificationSettingsCommand;
import com.easysubway.notification.domain.DevicePlatform;
import com.easysubway.notification.domain.NotificationSettings;
import com.easysubway.notification.domain.RegisteredDevice;
import java.security.Principal;
import java.time.LocalDateTime;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class NotificationPreferenceController {

	private final NotificationPreferenceUseCase notificationPreferenceUseCase;

	NotificationPreferenceController(NotificationPreferenceUseCase notificationPreferenceUseCase) {
		this.notificationPreferenceUseCase = notificationPreferenceUseCase;
	}

	@PostMapping("/api/v1/devices")
	ApiResponse<RegisteredDeviceResponse> registerDevice(
		@RequestBody RegisterDeviceRequest request,
		Principal principal
	) {
		RegisteredDevice device = notificationPreferenceUseCase.registerDevice(new RegisterDeviceCommand(
			principal.getName(),
			request.platform(),
			request.deviceToken()
		));
		return ApiResponse.ok(RegisteredDeviceResponse.from(device));
	}

	@GetMapping("/api/v1/me/notification-settings")
	ApiResponse<NotificationSettingsResponse> getNotificationSettings(Principal principal) {
		NotificationSettings settings = notificationPreferenceUseCase.getNotificationSettings(principal.getName());
		return ApiResponse.ok(NotificationSettingsResponse.from(settings));
	}

	@PutMapping("/api/v1/me/notification-settings")
	ApiResponse<NotificationSettingsResponse> saveNotificationSettings(
		@RequestBody SaveNotificationSettingsRequest request,
		Principal principal
	) {
		NotificationSettings settings = notificationPreferenceUseCase.saveNotificationSettings(
			new SaveNotificationSettingsCommand(
				principal.getName(),
				request.favoriteStationFacilityAlerts(),
				request.favoriteRouteFacilityAlerts(),
				request.reportStatusAlerts(),
				request.dataQualityAlerts()
			)
		);
		return ApiResponse.ok(NotificationSettingsResponse.from(settings));
	}

	record RegisterDeviceRequest(
		String userId,
		DevicePlatform platform,
		String deviceToken
	) {
	}

	record RegisteredDeviceResponse(
		String userId,
		DevicePlatform platform,
		String deviceToken,
		LocalDateTime registeredAt
	) {

		static RegisteredDeviceResponse from(RegisteredDevice device) {
			return new RegisteredDeviceResponse(
				device.userId(),
				device.platform(),
				device.deviceToken(),
				device.registeredAt()
			);
		}
	}

	record SaveNotificationSettingsRequest(
		String userId,
		boolean favoriteStationFacilityAlerts,
		boolean favoriteRouteFacilityAlerts,
		boolean reportStatusAlerts,
		boolean dataQualityAlerts
	) {
	}

	record NotificationSettingsResponse(
		String userId,
		boolean favoriteStationFacilityAlerts,
		boolean favoriteRouteFacilityAlerts,
		boolean reportStatusAlerts,
		boolean dataQualityAlerts,
		LocalDateTime updatedAt
	) {

		static NotificationSettingsResponse from(NotificationSettings settings) {
			return new NotificationSettingsResponse(
				settings.userId(),
				settings.favoriteStationFacilityAlerts(),
				settings.favoriteRouteFacilityAlerts(),
				settings.reportStatusAlerts(),
				settings.dataQualityAlerts(),
				settings.updatedAt()
			);
		}
	}
}
