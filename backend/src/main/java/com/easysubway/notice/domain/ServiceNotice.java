package com.easysubway.notice.domain;

import java.time.LocalDateTime;

/**
 * 운행 장애·이슈 공지 한 건.
 *
 * <p>실시간성 데이터이므로 데이터팩이 아닌 온라인 overlay로만 흐른다(#1414 분리 원칙).
 * 공개 API는 {@link #isActiveAt(LocalDateTime)}가 참인 공지만 노출한다 — 만료된 공지는
 * 서버 필터에서 제외되어 앱으로 나가지 않는다.
 *
 * <p>게시 중단은 row 삭제가 아니라 {@code unpublishedAt}/{@code unpublishedBy}를 남기는
 * soft-unpublish다(#2275). 게시 중단된 공지는 활성 조회에서 빠지지만 이력 조회로는 남는다.
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
	String publishedBy,
	LocalDateTime unpublishedAt,
	String unpublishedBy
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
		if ((unpublishedAt == null) != isBlank(unpublishedBy)) {
			throw new IllegalArgumentException("unpublishedAt과 unpublishedBy는 함께 있거나 함께 없어야 합니다.");
		}
	}

	/**
	 * 아직 게시 중단되지 않은(활성 후보) 공지를 만든다. 발행·저장 왕복의 기본 형태다.
	 */
	public ServiceNotice(
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
		this(id, scope, scopeValue, title, body, severity, publishedAt, expiresAt, publishedBy, null, null);
	}

	/**
	 * {@code now}에 이 공지가 활성인지. 게시 중단되지 않았고, 게시 시각 이상이며 만료 시각(있다면)
	 * 미만이면 활성. 만료 시각은 배타적이다.
	 */
	public boolean isActiveAt(LocalDateTime now) {
		if (isUnpublished()) {
			return false;
		}
		if (now.isBefore(publishedAt)) {
			return false;
		}
		return expiresAt == null || now.isBefore(expiresAt);
	}

	/**
	 * 게시 중단 여부. {@code unpublishedAt}이 남아 있으면 게시 중단된 상태다.
	 */
	public boolean isUnpublished() {
		return unpublishedAt != null;
	}

	/**
	 * {@code at} 시각에 {@code by}가 게시를 중단한 상태의 새 공지를 만든다. 원장 row는 보존하고
	 * 상태만 바꾼다. 이미 게시 중단된 공지에는 적용할 수 없다.
	 */
	public ServiceNotice unpublish(LocalDateTime at, String by) {
		if (at == null) {
			throw new IllegalArgumentException("unpublishedAt은 필수입니다.");
		}
		if (isBlank(by)) {
			throw new IllegalArgumentException("unpublishedBy는 필수입니다.");
		}
		if (isUnpublished()) {
			throw new IllegalStateException("이미 게시 중단된 공지입니다.");
		}
		return new ServiceNotice(
			id, scope, scopeValue, title, body, severity, publishedAt, expiresAt, publishedBy, at, by);
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
