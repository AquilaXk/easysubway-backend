package com.easysubway.notice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.common.error.ConflictException;
import com.easysubway.common.error.ResourceNotFoundException;
import com.easysubway.notice.application.port.out.ServiceNoticeRepository;
import com.easysubway.notice.domain.ServiceNotice;
import com.easysubway.notice.domain.ServiceNoticeScope;
import com.easysubway.notice.domain.ServiceNoticeSeverity;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ServiceNoticeService")
class ServiceNoticeServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.parse("2026-07-06T09:00:00");

	private final Clock clock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

	private static final class InMemoryRepository implements ServiceNoticeRepository {
		final List<ServiceNotice> stored = new ArrayList<>();

		@Override
		public void save(ServiceNotice notice) {
			stored.removeIf(n -> n.id().equals(notice.id()));
			stored.add(notice);
		}

		@Override
		public Optional<ServiceNotice> findById(String id) {
			return stored.stream().filter(n -> n.id().equals(id)).findFirst();
		}

		@Override
		public List<ServiceNotice> findActiveAt(LocalDateTime now) {
			return stored.stream().filter(n -> n.isActiveAt(now)).toList();
		}

		@Override
		public List<ServiceNotice> findRecent(int limit) {
			return stored.stream()
				.sorted(Comparator.comparing(ServiceNotice::publishedAt).reversed())
				.limit(limit)
				.toList();
		}

		@Override
		public boolean unpublish(String id, LocalDateTime unpublishedAt, String unpublishedBy) {
			ServiceNotice existing = stored.stream()
				.filter(n -> n.id().equals(id))
				.findFirst()
				.orElse(null);
			if (existing == null || existing.isUnpublished()) {
				return false;
			}
			save(existing.unpublish(unpublishedAt, unpublishedBy));
			return true;
		}
	}

	private final InMemoryRepository repository = new InMemoryRepository();
	private final ServiceNoticeService service = new ServiceNoticeService(repository, clock);

	@Test
	@DisplayName("발행하면 id가 생성되고 게시 시각이 현재로 저장된다")
	void publishSavesActiveNotice() {
		ServiceNotice published = service.publish(
			new PublishNoticeCommand(
				ServiceNoticeScope.LINE, "2",
				"2호선 지연", "우회 경로를 확인하세요.",
				ServiceNoticeSeverity.DISRUPTION, null
			),
			"operator-a"
		);

		assertThat(published.id()).isNotBlank();
		assertThat(published.publishedAt()).isEqualTo(NOW);
		assertThat(published.publishedBy()).isEqualTo("operator-a");
		assertThat(repository.findById(published.id())).isPresent();
	}

	@Test
	@DisplayName("활성 공지는 disruption 우선, 그 다음 최신 게시순으로 정렬")
	void activeNoticesSortedBySeverityThenNewest() {
		repository.save(new ServiceNotice("a", ServiceNoticeScope.ALL, null, "info-old", "b",
			ServiceNoticeSeverity.INFO, NOW.minusHours(3), null, "op"));
		repository.save(new ServiceNotice("b", ServiceNoticeScope.ALL, null, "disruption-old", "b",
			ServiceNoticeSeverity.DISRUPTION, NOW.minusHours(2), null, "op"));
		repository.save(new ServiceNotice("c", ServiceNoticeScope.ALL, null, "disruption-new", "b",
			ServiceNoticeSeverity.DISRUPTION, NOW.minusHours(1), null, "op"));

		List<ServiceNotice> active = service.activeNotices();

		assertThat(active).extracting(ServiceNotice::id).containsExactly("c", "b", "a");
	}

	@Test
	@DisplayName("게시 중단은 row를 보존하고 활성 조회에서만 제외한다")
	void unpublishKeepsRowButHidesFromActive() {
		ServiceNotice published = service.publish(
			new PublishNoticeCommand(
				ServiceNoticeScope.ALL, null, "전체", "본문",
				ServiceNoticeSeverity.INFO, null
			),
			"operator-a"
		);

		service.unpublish(published.id(), "operator-b");

		ServiceNotice stored = repository.findById(published.id()).orElseThrow();
		assertThat(stored.isUnpublished()).isTrue();
		assertThat(stored.unpublishedBy()).isEqualTo("operator-b");
		assertThat(stored.unpublishedAt()).isEqualTo(NOW);
		assertThat(service.activeNotices()).isEmpty();
	}

	@Test
	@DisplayName("게시 중단된 공지도 최근 이력 조회에는 남는다")
	void unpublishedNoticeStaysInHistory() {
		ServiceNotice published = service.publish(
			new PublishNoticeCommand(
				ServiceNoticeScope.ALL, null, "전체", "본문",
				ServiceNoticeSeverity.INFO, null
			),
			"operator-a"
		);

		service.unpublish(published.id(), "operator-b");

		assertThat(repository.findRecent(50)).extracting(ServiceNotice::id)
			.contains(published.id());
	}

	@Test
	@DisplayName("없는 공지 게시 중단은 404(ResourceNotFoundException)")
	void unpublishMissingNoticeNotFound() {
		assertThatThrownBy(() -> service.unpublish("missing", "operator-a"))
			.isInstanceOf(ResourceNotFoundException.class);
	}

	@Test
	@DisplayName("두 번째 게시 중단은 409(ConflictException)")
	void secondUnpublishConflicts() {
		ServiceNotice published = service.publish(
			new PublishNoticeCommand(
				ServiceNoticeScope.ALL, null, "전체", "본문",
				ServiceNoticeSeverity.INFO, null
			),
			"operator-a"
		);
		service.unpublish(published.id(), "operator-b");

		assertThatThrownBy(() -> service.unpublish(published.id(), "operator-c"))
			.isInstanceOf(ConflictException.class);
	}
}
