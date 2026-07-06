package com.easysubway.admin.audit.application;

import com.easysubway.admin.audit.domain.AdminAuditEventType;
import com.easysubway.admin.audit.domain.AdminAuditOutcome;
import java.time.LocalDate;

/**
 * 관리자 감사·개인정보 조회 로그 표준 테이블(#1747)의 서버 파라미터 질의.
 *
 * <p>이벤트 유형·actor·결과·발생 기간·target 검색과, 개인정보 로그 점검용 "사유 없는 조회" 필터,
 * 페이지네이션을 담는다. 개인정보 로그 화면은 {@code eventType}을 {@code PRIVACY_READ}로 고정해
 * 권한 분리(프로그램별 접근)를 URL이 아니라 질의로도 강제한다. 목록·건수·내보내기가 같은 질의를
 * 공유해 정합을 보장한다.
 */
public record AdminAuditQuery(
	AdminAuditEventType eventType,
	String actor,
	AdminAuditOutcome outcome,
	String targetKeyword,
	LocalDate occurredFrom,
	LocalDate occurredTo,
	boolean reasonMissing,
	int page,
	int size,
	boolean excludePrivacyRead
) {

	public static final int DEFAULT_PAGE = 0;
	public static final int DEFAULT_SIZE = 20;
	public static final int MAX_SIZE = 100;

	public AdminAuditQuery {
		actor = blankToNull(actor);
		targetKeyword = blankToNull(targetKeyword);
		if (page < 0 || size <= 0) {
			throw new IllegalArgumentException("페이지 요청 값을 확인해야 합니다.");
		}
		size = Math.min(size, MAX_SIZE);
		if (page > Integer.MAX_VALUE / size) {
			throw new IllegalArgumentException("페이지 요청 값을 확인해야 합니다.");
		}
		if (occurredFrom != null && occurredTo != null && occurredFrom.isAfter(occurredTo)) {
			throw new IllegalArgumentException("발생 기간 시작이 종료보다 늦을 수 없습니다.");
		}
	}

	/** excludePrivacyRead 없이 만드는 하위 호환 생성자(기본 false = 제외 안 함). */
	public AdminAuditQuery(
		AdminAuditEventType eventType,
		String actor,
		AdminAuditOutcome outcome,
		String targetKeyword,
		LocalDate occurredFrom,
		LocalDate occurredTo,
		boolean reasonMissing,
		int page,
		int size
	) {
		this(eventType, actor, outcome, targetKeyword, occurredFrom, occurredTo, reasonMissing, page, size, false);
	}

	/**
	 * @param forcedEventType   null이면 사용자가 고른 유형(nullable)을 쓰고, 지정되면 그 유형으로 고정한다
	 *                          (개인정보 로그 화면이 PRIVACY_READ로 강제).
	 * @param excludePrivacyRead true면 PRIVACY_READ 이벤트를 결과에서 제외한다(관리자 감사 화면이 개인정보
	 *                          조회 로그와 권한 분리되도록 — 개인정보는 별도 권한의 전용 화면에서만 본다).
	 */
	public static AdminAuditQuery of(
		AdminAuditEventType forcedEventType,
		AdminAuditEventType eventType,
		String actor,
		AdminAuditOutcome outcome,
		String targetKeyword,
		LocalDate occurredFrom,
		LocalDate occurredTo,
		Boolean reasonMissing,
		Integer page,
		Integer size,
		boolean excludePrivacyRead
	) {
		// 잘못된 요청값(?page=-1, ?size=0, from>to)이 500으로 새지 않도록 질의 생성 전에 보정한다.
		int safePage = (page == null || page < 0) ? DEFAULT_PAGE : page;
		int safeSize = (size == null || size <= 0) ? DEFAULT_SIZE : size;
		LocalDate from = occurredFrom;
		LocalDate to = occurredTo;
		if (from != null && to != null && from.isAfter(to)) {
			LocalDate swap = from;
			from = to;
			to = swap;
		}
		return new AdminAuditQuery(
			forcedEventType != null ? forcedEventType : eventType,
			actor,
			outcome,
			targetKeyword,
			from,
			to,
			Boolean.TRUE.equals(reasonMissing),
			safePage,
			safeSize,
			excludePrivacyRead
		);
	}

	public AdminAuditQuery withPage(int nextPage) {
		return new AdminAuditQuery(eventType, actor, outcome, targetKeyword, occurredFrom, occurredTo,
			reasonMissing, nextPage, size, excludePrivacyRead);
	}

	public boolean hasEventType() {
		return eventType != null;
	}

	public boolean hasActor() {
		return actor != null;
	}

	public boolean hasOutcome() {
		return outcome != null;
	}

	public boolean hasTargetKeyword() {
		return targetKeyword != null;
	}

	public int offset() {
		return page * size;
	}

	private static String blankToNull(String value) {
		return (value == null || value.isBlank()) ? null : value.trim();
	}
}
