package com.easysubway.notification.adapter.in.web;

import com.easysubway.common.web.ApiResponse;
import com.easysubway.notification.application.port.in.DispatchPushNotificationCommand;
import com.easysubway.notification.application.port.in.PushNotificationDispatchUseCase;
import com.easysubway.notification.domain.DevicePlatform;
import com.easysubway.notification.domain.PushNotification;
import com.easysubway.notification.domain.PushNotificationDispatchResult;
import com.easysubway.notification.domain.PushNotificationStatus;
import com.easysubway.notification.domain.PushNotificationType;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PushNotificationController {

	private final PushNotificationDispatchUseCase pushNotificationDispatchUseCase;

	PushNotificationController(PushNotificationDispatchUseCase pushNotificationDispatchUseCase) {
		this.pushNotificationDispatchUseCase = pushNotificationDispatchUseCase;
	}

	@PostMapping("/admin/notifications/push")
	ApiResponse<PushNotificationDispatchResponse> dispatch(@RequestBody PushNotificationDispatchRequest request) {
		PushNotificationDispatchResult result = pushNotificationDispatchUseCase.dispatch(
			new DispatchPushNotificationCommand(
				request.userId(),
				request.type(),
				request.title(),
				request.body()
			)
		);
		return ApiResponse.ok(PushNotificationDispatchResponse.from(result));
	}

	record PushNotificationDispatchRequest(
		String userId,
		PushNotificationType type,
		String title,
		String body
	) {
	}

	record PushNotificationDispatchResponse(
		String requestedUserId,
		PushNotificationType type,
		int createdCount,
		List<PushNotificationResponse> notifications
	) {

		static PushNotificationDispatchResponse from(PushNotificationDispatchResult result) {
			return new PushNotificationDispatchResponse(
				result.requestedUserId(),
				result.type(),
				result.createdCount(),
				result.notifications().stream()
					.map(PushNotificationResponse::from)
					.toList()
			);
		}
	}

	record PushNotificationResponse(
		String notificationId,
		String userId,
		DevicePlatform platform,
		PushNotificationType type,
		String title,
		String body,
		PushNotificationStatus status,
		LocalDateTime createdAt
	) {

		static PushNotificationResponse from(PushNotification notification) {
			return new PushNotificationResponse(
				notification.notificationId(),
				notification.userId(),
				notification.platform(),
				notification.type(),
				notification.title(),
				notification.body(),
				notification.status(),
				notification.createdAt()
			);
		}
	}
}
