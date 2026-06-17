package com.easysubway.notification.domain;

import java.time.LocalDateTime;

public record PushNotification(
	String notificationId,
	String userId,
	DevicePlatform platform,
	String deviceToken,
	PushNotificationType type,
	String title,
	String body,
	PushNotificationStatus status,
	String failureReason,
	LocalDateTime createdAt
) {

	private static final int FAILURE_REASON_MAX_LENGTH = 1000;

	public PushNotification {
		if (failureReason != null) {
			failureReason = failureReason.trim();
			if (failureReason.isBlank()) {
				failureReason = null;
			} else if (failureReason.length() > FAILURE_REASON_MAX_LENGTH) {
				failureReason = failureReason.substring(0, FAILURE_REASON_MAX_LENGTH);
			}
		}
		if (notificationId == null || notificationId.isBlank()) {
			throw new InvalidPushNotificationException("알림 식별자가 필요합니다.");
		}
		if (userId == null || userId.isBlank()) {
			throw new InvalidPushNotificationException("사용자 식별자가 필요합니다.");
		}
		if (platform == null) {
			throw new InvalidPushNotificationException("기기 플랫폼을 선택해야 합니다.");
		}
		if (deviceToken == null || deviceToken.isBlank()) {
			throw new InvalidPushNotificationException("기기 토큰이 필요합니다.");
		}
		if (type == null) {
			throw new InvalidPushNotificationException("알림 종류를 선택해야 합니다.");
		}
		if (title == null || title.isBlank()) {
			throw new InvalidPushNotificationException("알림 제목이 필요합니다.");
		}
		if (body == null || body.isBlank()) {
			throw new InvalidPushNotificationException("알림 본문이 필요합니다.");
		}
		if (status == null) {
			throw new InvalidPushNotificationException("알림 상태가 필요합니다.");
		}
		if (status != PushNotificationStatus.FAILED && failureReason != null) {
			throw new InvalidPushNotificationException("실패하지 않은 알림에는 실패 사유를 둘 수 없습니다.");
		}
		if (createdAt == null) {
			throw new InvalidPushNotificationException("알림 생성 시간이 필요합니다.");
		}
		notificationId = notificationId.trim();
		userId = userId.trim();
		deviceToken = deviceToken.trim();
		title = title.trim();
		body = body.trim();
	}

	public PushNotification(
		String notificationId,
		String userId,
		DevicePlatform platform,
		String deviceToken,
		PushNotificationType type,
		String title,
		String body,
		PushNotificationStatus status,
		LocalDateTime createdAt
	) {
		this(notificationId, userId, platform, deviceToken, type, title, body, status, null, createdAt);
	}

	public PushNotification withStatus(PushNotificationStatus nextStatus) {
		return new PushNotification(
			notificationId,
			userId,
			platform,
			deviceToken,
			type,
			title,
			body,
			nextStatus,
			null,
			createdAt
		);
	}

	public PushNotification withSendResult(PushNotificationSendResult sendResult) {
		PushNotificationStatus nextStatus = sendResult.successful()
			? PushNotificationStatus.SENT
			: PushNotificationStatus.FAILED;
		return new PushNotification(
			notificationId,
			userId,
			platform,
			deviceToken,
			type,
			title,
			body,
			nextStatus,
			sendResult.failureReason(),
			createdAt
		);
	}
}
