package com.easysubway.admin.alert;

import java.util.List;

/**
 * 알림 센터 요약(#1738). 권한으로 걸러진 운영 신호 항목들을 담는다.
 *
 * <p>상태 저장 없는 파생 신호로 시작한다(읽음 처리 테이블은 비범위). 매 폴링마다 새로 집약한다.
 */
public record AdminAlertSummary(List<AdminAlertItem> items) {

	public AdminAlertSummary {
		items = items == null ? List.of() : List.copyOf(items);
	}

	public static AdminAlertSummary empty() {
		return new AdminAlertSummary(List.of());
	}

	public int count() {
		return items.size();
	}

	public boolean hasAlerts() {
		return !items.isEmpty();
	}
}
