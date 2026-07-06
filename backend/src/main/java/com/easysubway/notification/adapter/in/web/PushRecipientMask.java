package com.easysubway.notification.adapter.in.web;

/**
 * 관리자 발송 이력 콘솔(#1746)의 수신자 식별자 마스킹.
 *
 * <p>개인정보 최소 노출 원칙: 사용자 식별자·기기 토큰은 원문을 그대로 노출하지 않고 앞 일부만 남긴다.
 * 남은 길이를 드러내지 않도록 고정 마커(••••)를 붙인다. 열람 자체는 감사에 남긴다.
 */
final class PushRecipientMask {

	private static final String MASK_MARKER = "••••";

	private PushRecipientMask() {
	}

	static String maskUserId(String userId) {
		return mask(userId, 4);
	}

	static String maskDeviceToken(String deviceToken) {
		return mask(deviceToken, 6);
	}

	private static String mask(String value, int visiblePrefix) {
		if (value == null || value.isBlank()) {
			return MASK_MARKER;
		}
		String trimmed = value.trim();
		if (trimmed.length() <= visiblePrefix) {
			return MASK_MARKER;
		}
		return trimmed.substring(0, visiblePrefix) + MASK_MARKER;
	}
}
