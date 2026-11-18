package com.easysubway.notification.domain;

/**
 * 실패 푸시 재발송 결과(#1746).
 *
 * <p>{@code blocked}면 상한 초과로 아무것도 재발송하지 않았다. 그 외에는 선택 건수(requested) 중 실패
 * 상태였던 건만 재발송(resent)하고 나머지(이미 성공·대기·없음)는 멱등하게 건너뛴다(skipped).
 */
public record PushNotificationResendResult(
	int requestedCount,
	int resentCount,
	int skippedCount,
	boolean blocked,
	int maxPerResend
) {

	public static PushNotificationResendResult blocked(int requestedCount, int maxPerResend) {
		return new PushNotificationResendResult(requestedCount, 0, 0, true, maxPerResend);
	}
}
