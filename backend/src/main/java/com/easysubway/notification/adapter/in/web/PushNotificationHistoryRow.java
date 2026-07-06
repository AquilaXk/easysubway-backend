package com.easysubway.notification.adapter.in.web;

import com.easysubway.notification.domain.PushNotification;
import com.easysubway.notification.domain.PushNotificationStatus;
import com.easysubway.notification.domain.PushNotificationType;
import java.time.LocalDateTime;

/**
 * 관리자 발송 이력 표준 테이블(#1746)의 행. 수신자 식별자(사용자·기기 토큰)는 마스킹해 노출하고,
 * 상태·유형은 한글 라벨로, 실패 사유 원문은 행 확장(details)에서만 보여준다.
 */
record PushNotificationHistoryRow(
	String notificationId,
	String maskedUserId,
	String maskedDeviceToken,
	String platformLabel,
	String typeLabel,
	String statusLabel,
	String statusTone,
	String title,
	String failureReason,
	LocalDateTime createdAt
) {

	static PushNotificationHistoryRow from(PushNotification notification) {
		return new PushNotificationHistoryRow(
			notification.notificationId(),
			PushRecipientMask.maskUserId(notification.userId()),
			PushRecipientMask.maskDeviceToken(notification.deviceToken()),
			platformLabel(notification.platform().name()),
			typeLabel(notification.type()),
			statusLabel(notification.status()),
			statusTone(notification.status()),
			notification.title(),
			notification.failureReason(),
			notification.createdAt()
		);
	}

	private static String platformLabel(String platform) {
		return switch (platform) {
			case "ANDROID" -> "안드로이드";
			case "IOS" -> "iOS";
			default -> platform;
		};
	}

	static String typeLabel(PushNotificationType type) {
		return switch (type) {
			case FAVORITE_STATION_FACILITY -> "즐겨찾는 역 시설";
			case FAVORITE_ROUTE_FACILITY -> "즐겨찾는 경로 시설";
			case REPORT_STATUS -> "제보 처리 상태";
			case DATA_QUALITY -> "데이터 품질";
		};
	}

	static String statusLabel(PushNotificationStatus status) {
		return switch (status) {
			case PENDING -> "대기 중";
			case PROCESSING -> "처리 중";
			case SENT -> "발송 완료";
			case FAILED -> "발송 실패";
		};
	}

	private static String statusTone(PushNotificationStatus status) {
		return switch (status) {
			case PENDING, PROCESSING -> "pending";
			case SENT -> "ok";
			case FAILED -> "failure";
		};
	}
}
