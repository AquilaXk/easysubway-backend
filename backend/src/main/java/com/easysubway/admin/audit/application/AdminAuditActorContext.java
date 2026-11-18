package com.easysubway.admin.audit.application;

import com.easysubway.admin.audit.domain.AdminAuditEvent;
import java.util.List;

/**
 * 상세 드로어(#1747)의 "같은 actor 전후 타임라인". 선택한 이벤트를 기준으로 같은 actor의 직전·직후
 * 이벤트를 시간순(오름차순)으로 담는다. 목록과 같은 저장소를 조회해 전후 맥락이 정합한다.
 *
 * @param before 기준 이벤트 직전 이벤트들(시간 오름차순, 최대 radius개)
 * @param after  기준 이벤트 직후 이벤트들(시간 오름차순, 최대 radius개)
 */
public record AdminAuditActorContext(List<AdminAuditEvent> before, List<AdminAuditEvent> after) {

	public AdminAuditActorContext {
		before = List.copyOf(before);
		after = List.copyOf(after);
	}

	public static AdminAuditActorContext empty() {
		return new AdminAuditActorContext(List.of(), List.of());
	}
}
