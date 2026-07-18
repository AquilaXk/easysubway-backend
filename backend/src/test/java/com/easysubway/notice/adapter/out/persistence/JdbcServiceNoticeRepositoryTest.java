package com.easysubway.notice.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.notice.application.port.out.ServiceNoticeRepository;
import com.easysubway.notice.domain.ServiceNotice;
import com.easysubway.notice.domain.ServiceNoticeScope;
import com.easysubway.notice.domain.ServiceNoticeSeverity;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@DisplayName("JdbcServiceNoticeRepository")
class JdbcServiceNoticeRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.parse("2026-07-06T12:00:00");

	@Autowired
	private ServiceNoticeRepository repository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM service_notice");
	}

	private ServiceNotice notice(
		String id, ServiceNoticeSeverity severity,
		LocalDateTime publishedAt, LocalDateTime expiresAt
	) {
		return new ServiceNotice(
			id, ServiceNoticeScope.LINE, "2", "2호선 지연", "우회 경로를 확인하세요.",
			severity, publishedAt, expiresAt, "operator-a");
	}

	@Test
	@DisplayName("save 후 findById가 모든 필드를 왕복한다")
	void savesAndReads() {
		repository.save(notice("n1", ServiceNoticeSeverity.DISRUPTION,
			NOW.minusHours(1), NOW.plusHours(1)));

		ServiceNotice found = repository.findById("n1").orElseThrow();
		assertThat(found.scope()).isEqualTo(ServiceNoticeScope.LINE);
		assertThat(found.scopeValue()).isEqualTo("2");
		assertThat(found.severity()).isEqualTo(ServiceNoticeSeverity.DISRUPTION);
		assertThat(found.expiresAt()).isEqualTo(NOW.plusHours(1));
	}

	@Test
	@DisplayName("ALL 대상은 scope_value가 null로 왕복한다")
	void allScopeNullValue() {
		repository.save(new ServiceNotice("n2", ServiceNoticeScope.ALL, null,
			"전체 공지", "본문", ServiceNoticeSeverity.INFO, NOW.minusHours(1), null, "op"));

		assertThat(repository.findById("n2").orElseThrow().scopeValue()).isNull();
	}

	@Test
	@DisplayName("findActiveAt은 미게시·만료 공지를 제외하고 게시 최신순으로 준다")
	void findActiveExcludesFutureAndExpired() {
		repository.save(notice("future", ServiceNoticeSeverity.INFO, NOW.plusHours(1), null));
		repository.save(notice("expired", ServiceNoticeSeverity.INFO, NOW.minusHours(3), NOW.minusHours(1)));
		repository.save(notice("active-old", ServiceNoticeSeverity.INFO, NOW.minusHours(2), NOW.plusHours(2)));
		repository.save(notice("active-new", ServiceNoticeSeverity.INFO, NOW.minusMinutes(30), null));

		List<ServiceNotice> active = repository.findActiveAt(NOW);

		assertThat(active).extracting(ServiceNotice::id).containsExactly("active-new", "active-old");
	}

	@Test
	@DisplayName("unpublish는 row를 보존하고 게시 중단 상태를 왕복한다")
	void unpublishKeepsRowAndPersistsState() {
		repository.save(notice("n3", ServiceNoticeSeverity.INFO, NOW.minusHours(1), null));

		boolean flipped = repository.unpublish("n3", NOW, "operator-b");

		assertThat(flipped).isTrue();
		ServiceNotice stored = repository.findById("n3").orElseThrow();
		assertThat(stored.isUnpublished()).isTrue();
		assertThat(stored.unpublishedAt()).isEqualTo(NOW);
		assertThat(stored.unpublishedBy()).isEqualTo("operator-b");
	}

	@Test
	@DisplayName("게시 중단된 공지는 findActiveAt에서 빠지지만 findRecent에는 남는다")
	void unpublishedExcludedFromActiveButKeptInRecent() {
		repository.save(notice("n4", ServiceNoticeSeverity.INFO, NOW.minusHours(1), null));
		repository.unpublish("n4", NOW, "operator-b");

		assertThat(repository.findActiveAt(NOW)).extracting(ServiceNotice::id).doesNotContain("n4");
		assertThat(repository.findRecent(50)).extracting(ServiceNotice::id).contains("n4");
	}

	@Test
	@DisplayName("두 번째 unpublish는 바뀐 row가 없어 false를 준다")
	void secondUnpublishReturnsFalse() {
		repository.save(notice("n5", ServiceNoticeSeverity.INFO, NOW.minusHours(1), null));

		assertThat(repository.unpublish("n5", NOW, "operator-b")).isTrue();
		assertThat(repository.unpublish("n5", NOW.plusHours(1), "operator-c")).isFalse();
	}

	@Test
	@DisplayName("없는 공지 unpublish는 false를 준다")
	void unpublishMissingReturnsFalse() {
		assertThat(repository.unpublish("missing", NOW, "operator-b")).isFalse();
	}
}
