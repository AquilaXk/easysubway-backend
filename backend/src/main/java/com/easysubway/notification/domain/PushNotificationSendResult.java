package com.easysubway.notification.domain;

public record PushNotificationSendResult(
	boolean successful,
	String failureReason
) {

	public PushNotificationSendResult {
		if (failureReason != null) {
			failureReason = failureReason.trim();
		}
		if (successful && failureReason != null) {
			throw new InvalidPushNotificationException("발송 성공 결과에는 실패 사유를 둘 수 없습니다.");
		}
		if (!successful && (failureReason == null || failureReason.isBlank())) {
			throw new InvalidPushNotificationException("발송 실패 사유가 필요합니다.");
		}
	}

	public static PushNotificationSendResult sent() {
		return new PushNotificationSendResult(true, null);
	}

	public static PushNotificationSendResult failed(String failureReason) {
		return new PushNotificationSendResult(false, failureReason);
	}
}
