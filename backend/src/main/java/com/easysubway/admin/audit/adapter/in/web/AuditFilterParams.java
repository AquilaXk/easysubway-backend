package com.easysubway.admin.audit.adapter.in.web;

import com.easysubway.admin.audit.application.AdminAuditQuery;
import com.easysubway.admin.audit.domain.AdminAuditEventType;
import com.easysubway.admin.audit.domain.AdminAuditOutcome;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 감사 화면·내보내기(#1747)가 공유하는 필터 폼 바인딩. 빈 문자열이 enum 변환 400을 내지 않도록
 * String으로 받아 파싱한다(Spring StringToEnum이 빈 문자열도 변환 시도하는 것과 무관하게 안전).
 */
record AuditFilterParams(
	String eventType,
	String actor,
	String outcome,
	String keyword,
	@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
	@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
	Boolean reasonMissing,
	Integer page,
	Integer size
) {

	AdminAuditEventType eventTypeOrNull() {
		return parseEnum(eventType, AdminAuditEventType.class);
	}

	AdminAuditOutcome outcomeOrNull() {
		return parseEnum(outcome, AdminAuditOutcome.class);
	}

	/**
	 * 목록·내보내기가 같은 필터를 공유하도록 질의를 만든다. forcedEventType은 개인정보 화면(PRIVACY_READ)
	 * 강제, excludePrivacyRead는 관리자 감사 화면에서 개인정보 조회 이벤트를 제외(권한 분리).
	 */
	AdminAuditQuery toQuery(
		AdminAuditEventType forcedEventType, boolean excludePrivacyRead, Integer pageOverride, Integer sizeOverride) {
		return AdminAuditQuery.of(
			forcedEventType,
			eventTypeOrNull(),
			actor,
			outcomeOrNull(),
			keyword,
			from,
			to,
			reasonMissing,
			pageOverride,
			sizeOverride,
			excludePrivacyRead
		);
	}

	private static <E extends Enum<E>> E parseEnum(String value, Class<E> type) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return Enum.valueOf(type, value.trim());
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}
}
