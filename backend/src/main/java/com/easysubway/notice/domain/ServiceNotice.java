package com.easysubway.notice.domain;

import java.time.LocalDateTime;

/**
 * 운행 장애·이슈 공지 한 건.
 *
 * <p>실시간성 데이터이므로 데이터팩이 아닌 온라인 overlay로만 흐른다(#1414 분리 원칙).
 * 공개 API는 {@link #isActiveAt(LocalDateTime)}가 참인 공지만 노출한다 — 만료된 공지는
 * 서버 필터에서 제외되어 앱으로 나가지 않는다.
 */
public record ServiceNotice(
	String id,
	ServiceNoticeScope scope,
	String scopeValue,
	String title,
	String body,
	ServiceNoticeSeverity severity,
	LocalDateTime publishedAt,
	LocalDateTime expiresAt,
	String publishedBy
) {

	public ServiceNotice {
		if (scope == null) {
			throw new IllegalArgumentException("scope는 필수입니다.");
		}
		if (severity == null) {
			throw new IllegalArgumentException("severity는 필수입니다.");
		}
		if (isBlank(title)) {
			throw new IllegalArgumentException("title은 필수입니다.");
		}
		if (isBlank(body)) {
			throw new IllegalArgumentException("body는 필수입니다.");
		}
		if (publishedAt == null) {
			throw new IllegalArgumentException("publishedAt은 필수입니다.");
		}
		if (expiresAt != null && !expiresAt.isAfter(publishedAt)) {
			throw new IllegalArgumentException("expiresAt은 publishedAt 이후여야 합니다.");
		}
		if (scope != ServiceNoticeScope.ALL && isBlank(scopeValue)) {
			throw new IllegalArgumentException("REGION·LINE 대상은 scopeValue가 필수입니다.");
		}
		if (scope == ServiceNoticeScope.ALL) {
			scopeValue = null;
		}
	}

	/**
	 * {@code now}에 이 공지가 활성인지. 게시 시각 이상이고 만료 시각(있다면) 미만이면 활성.
	 * 만료 시각은 배타적이다.
	 */
	public boolean isActiveAt(LocalDateTime now) {
		if (now.isBefore(publishedAt)) {
			return false;
		}
		return expiresAt == null || now.isBefore(expiresAt);
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
