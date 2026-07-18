package com.easysubway.notice.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ServiceNotice 도메인")
class ServiceNoticeTest {

	private static final LocalDateTime PUBLISHED = LocalDateTime.parse("2026-07-06T09:00:00");
	private static final LocalDateTime EXPIRES = LocalDateTime.parse("2026-07-06T18:00:00");

	private ServiceNotice notice(LocalDateTime publishedAt, LocalDateTime expiresAt) {
		return new ServiceNotice(
			"n1",
			ServiceNoticeScope.LINE,
			"2",
			"2호선 강남–역삼 상행 지연",
			"우회 경로를 확인하세요.",
			ServiceNoticeSeverity.DISRUPTION,
			publishedAt,
			expiresAt,
			"operator-a"
		);
	}

	@Test
	@DisplayName("게시 시각과 만료 시각 사이에는 활성")
	void activeWithinWindow() {
		ServiceNotice notice = notice(PUBLISHED, EXPIRES);
		assertThat(notice.isActiveAt(LocalDateTime.parse("2026-07-06T12:00:00"))).isTrue();
	}

	@Test
	@DisplayName("게시 시각 이전에는 비활성")
	void inactiveBeforePublish() {
		ServiceNotice notice = notice(PUBLISHED, EXPIRES);
		assertThat(notice.isActiveAt(LocalDateTime.parse("2026-07-06T08:59:59"))).isFalse();
	}

	@Test
	@DisplayName("만료 시각 도달·경과 시 비활성(만료 시각 배타적)")
	void inactiveAtAndAfterExpiry() {
		ServiceNotice notice = notice(PUBLISHED, EXPIRES);
		assertThat(notice.isActiveAt(EXPIRES)).isFalse();
		assertThat(notice.isActiveAt(EXPIRES.plusSeconds(1))).isFalse();
	}

	@Test
	@DisplayName("만료 시각이 없으면 게시 이후 계속 활성")
	void activeWhenNoExpiry() {
		ServiceNotice notice = notice(PUBLISHED, null);
		assertThat(notice.isActiveAt(LocalDateTime.parse("2030-01-01T00:00:00"))).isTrue();
		assertThat(notice.isActiveAt(PUBLISHED.minusSeconds(1))).isFalse();
	}

	@Test
	@DisplayName("노선/지역 대상은 대상 값이 필수")
	void scopeValueRequiredForLineOrRegion() {
		assertThatThrownBy(() -> new ServiceNotice(
			"n1", ServiceNoticeScope.LINE, "  ",
			"제목", "본문", ServiceNoticeSeverity.DISRUPTION,
			PUBLISHED, EXPIRES, "operator-a"
		)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("전체 대상은 대상 값이 없어도 된다")
	void allScopeAllowsNullValue() {
		ServiceNotice notice = new ServiceNotice(
			"n1", ServiceNoticeScope.ALL, null,
			"전체 공지", "본문", ServiceNoticeSeverity.INFO,
			PUBLISHED, null, "operator-a"
		);
		assertThat(notice.scopeValue()).isNull();
	}

	@Test
	@DisplayName("새로 만든 공지는 게시 중단 상태가 아니다")
	void freshNoticeIsNotUnpublished() {
		ServiceNotice notice = notice(PUBLISHED, EXPIRES);
		assertThat(notice.isUnpublished()).isFalse();
		assertThat(notice.unpublishedAt()).isNull();
		assertThat(notice.unpublishedBy()).isNull();
	}

	@Test
	@DisplayName("게시 중단은 상태만 남기고 원장 필드는 보존한다")
	void unpublishRetainsOriginalFields() {
		ServiceNotice notice = notice(PUBLISHED, null);
		LocalDateTime at = LocalDateTime.parse("2026-07-06T13:00:00");

		ServiceNotice unpublished = notice.unpublish(at, "operator-b");

		assertThat(unpublished.isUnpublished()).isTrue();
		assertThat(unpublished.unpublishedAt()).isEqualTo(at);
		assertThat(unpublished.unpublishedBy()).isEqualTo("operator-b");
		assertThat(unpublished.id()).isEqualTo(notice.id());
		assertThat(unpublished.publishedBy()).isEqualTo(notice.publishedBy());
		assertThat(unpublished.publishedAt()).isEqualTo(notice.publishedAt());
	}

	@Test
	@DisplayName("게시 중단된 공지는 창(window) 안이라도 활성이 아니다")
	void unpublishedNoticeIsNeverActive() {
		ServiceNotice notice = notice(PUBLISHED, EXPIRES)
			.unpublish(LocalDateTime.parse("2026-07-06T13:00:00"), "operator-b");
		assertThat(notice.isActiveAt(LocalDateTime.parse("2026-07-06T14:00:00"))).isFalse();
	}

	@Test
	@DisplayName("이미 게시 중단된 공지는 다시 게시 중단할 수 없다")
	void doubleUnpublishRejected() {
		ServiceNotice unpublished = notice(PUBLISHED, null)
			.unpublish(LocalDateTime.parse("2026-07-06T13:00:00"), "operator-b");
		assertThatThrownBy(() -> unpublished.unpublish(
			LocalDateTime.parse("2026-07-06T14:00:00"), "operator-c"))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("unpublishedAt과 unpublishedBy는 함께 있거나 함께 없어야 한다")
	void unpublishStateMustBeConsistent() {
		assertThatThrownBy(() -> new ServiceNotice(
			"n1", ServiceNoticeScope.ALL, null, "제목", "본문",
			ServiceNoticeSeverity.INFO, PUBLISHED, null, "operator-a",
			LocalDateTime.parse("2026-07-06T13:00:00"), null))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
