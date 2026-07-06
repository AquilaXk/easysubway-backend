package com.easysubway.notification.application.port.in;

import com.easysubway.notification.domain.InvalidPushNotificationException;
import com.easysubway.notification.domain.PushNotificationStatus;
import com.easysubway.notification.domain.PushNotificationType;
import java.time.LocalDate;

/**
 * 관리자 푸시 발송 이력 표준 테이블(#1746)의 서버 파라미터 질의.
 *
 * <p>상태·알림 유형·발송 기간 필터와 내용 키워드 검색, 실패 사유 드릴다운, 페이지네이션을 담는다. 모든 값은
 * URL 쿼리에서 오며 no-JS(폼 제출)와 htmx(부분 갱신)가 같은 파라미터를 공유한다. 수신자 식별자(userId·기기
 * 토큰)는 개인정보라 검색 대상에서 제외하고 제목·본문만 매칭한다. 목록·건수·실패 분해가 같은 질의를 공유해
 * 분해 수치와 목록 건수의 정합을 by-construction으로 보장한다.
 */
public record PushNotificationHistoryQuery(
	PushNotificationStatus status,
	PushNotificationType type,
	String keyword,
	String failureReason,
	LocalDate createdFrom,
	LocalDate createdTo,
	int page,
	int size
) {

	public static final int DEFAULT_PAGE = 0;
	public static final int DEFAULT_SIZE = 20;
	public static final int MAX_SIZE = 100;

	public PushNotificationHistoryQuery {
		keyword = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
		failureReason = (failureReason == null || failureReason.isBlank()) ? null : failureReason.trim();
		if (page < 0 || size <= 0) {
			throw new InvalidPushNotificationException("페이지 요청 값을 확인해야 합니다.");
		}
		size = Math.min(size, MAX_SIZE);
		if (page > Integer.MAX_VALUE / size) {
			throw new InvalidPushNotificationException("페이지 요청 값을 확인해야 합니다.");
		}
		if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
			throw new InvalidPushNotificationException("발송 기간 시작이 종료보다 늦을 수 없습니다.");
		}
	}

	public static PushNotificationHistoryQuery of(
		PushNotificationStatus status,
		PushNotificationType type,
		String keyword,
		String failureReason,
		LocalDate createdFrom,
		LocalDate createdTo,
		Integer page,
		Integer size
	) {
		int normalizedPage = page == null ? DEFAULT_PAGE : page;
		int requestedSize = size == null ? DEFAULT_SIZE : size;
		return new PushNotificationHistoryQuery(
			status, type, keyword, failureReason, createdFrom, createdTo, normalizedPage, requestedSize);
	}

	/** 필터는 그대로 두고 페이지만 바꾼 질의. 클램프·기본 뷰 재조립에 쓴다. */
	public PushNotificationHistoryQuery withPage(int nextPage) {
		return new PushNotificationHistoryQuery(
			status, type, keyword, failureReason, createdFrom, createdTo, nextPage, size);
	}

	public boolean hasKeyword() {
		return keyword != null;
	}

	public boolean hasStatus() {
		return status != null;
	}

	public boolean hasType() {
		return type != null;
	}

	public boolean hasFailureReason() {
		return failureReason != null;
	}

	public int offset() {
		return page * size;
	}
}
