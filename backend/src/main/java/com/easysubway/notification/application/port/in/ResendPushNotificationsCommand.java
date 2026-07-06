package com.easysubway.notification.application.port.in;

import java.util.List;

/**
 * 실패 푸시 재발송 명령(#1746). 선택한 알림 식별자와 1회 재발송 상한을 담는다.
 *
 * <p>상한(maxPerResend)은 공통코드로 관리되며 컨트롤러에서 주입한다. 대량 오발송을 막기 위해 선택 건수가
 * 상한을 넘으면 서비스가 재발송을 차단한다. 멱등: 이미 발송 성공(SENT)한 건은 재발송 대상에서 제외된다.
 */
public record ResendPushNotificationsCommand(List<String> notificationIds, int maxPerResend) {

	public ResendPushNotificationsCommand {
		notificationIds = notificationIds == null
			? List.of()
			: notificationIds.stream()
				.filter(id -> id != null && !id.isBlank())
				.map(String::trim)
				.distinct()
				.toList();
	}
}
