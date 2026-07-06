package com.easysubway.admin.audit.application.port.out;

import com.easysubway.admin.audit.application.AdminAuditActorContext;
import com.easysubway.admin.audit.application.AdminAuditQuery;
import com.easysubway.admin.audit.domain.AdminAuditEvent;
import com.easysubway.admin.audit.domain.AdminAuditEventType;
import java.util.List;
import java.util.Optional;

public interface AdminAuditEventRepository {

	void save(AdminAuditEvent event);

	List<AdminAuditEvent> findRecent(AdminAuditEventType eventType, int limit);

	default List<AdminAuditEvent> findRecent(AdminAuditEventType eventType, int limit, int offset) {
		return offset <= 0 ? findRecent(eventType, limit) : List.of();
	}

	/** 필터·페이지네이션이 적용된 감사 이벤트 목록(#1747). 발생 최신순. */
	List<AdminAuditEvent> search(AdminAuditQuery query);

	/** 같은 필터의 총 건수. 목록과 같은 질의를 공유해 페이지네이션·내보내기 정합을 보장한다. */
	long count(AdminAuditQuery query);

	/**
	 * 내보내기용 조회: 질의의 페이지네이션은 무시하고 같은 필터로 최신순 {@code limit}건까지 준다.
	 * 목록과 같은 WHERE를 공유해 화면과 내보내기 결과가 정합한다.
	 */
	List<AdminAuditEvent> findForExport(AdminAuditQuery query, int limit);

	/**
	 * actor 필터 select 옵션용. scopeEventType이 지정되면(개인정보 로그) 그 유형 이벤트의 actor만 준다.
	 * 정렬된 distinct 목록.
	 */
	List<String> findDistinctActors(AdminAuditEventType scopeEventType);

	/**
	 * 상세 드로어용 단건 조회. scopeEventType이 지정되면 그 유형만(개인정보 로그), excludePrivacyRead면
	 * PRIVACY_READ를 제외한다(관리자 감사 화면 권한 분리 — 개인정보 조회 상세는 열 수 없다).
	 */
	Optional<AdminAuditEvent> findById(long id, AdminAuditEventType scopeEventType, boolean excludePrivacyRead);

	/**
	 * 상세 드로어의 "같은 actor 전후 타임라인": pivot 기준 같은 actor의 직전·직후 이벤트를 각 radius개씩
	 * 시간순으로 준다. scopeEventType이 지정되면 그 유형만(개인정보 로그), excludePrivacyRead면 PRIVACY_READ
	 * 제외(관리자 감사 화면이 개인정보 조회 흐름을 노출하지 않도록).
	 */
	AdminAuditActorContext findActorContext(
		AdminAuditEvent pivot, AdminAuditEventType scopeEventType, boolean excludePrivacyRead, int radius);
}
