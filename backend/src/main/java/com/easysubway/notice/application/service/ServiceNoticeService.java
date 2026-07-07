package com.easysubway.notice.application.service;

import com.easysubway.notice.application.port.out.ServiceNoticeRepository;
import com.easysubway.notice.domain.ServiceNotice;
import com.easysubway.notice.domain.ServiceNoticeSeverity;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 운행 공지 조회·발행·즉시 내리기 유스케이스.
 *
 * <p>활성 조회는 저장소가 시각으로 필터한 뒤, 노선도 홈 배너·목록 표시 순서에 맞게
 * disruption 우선·최신 게시순으로 정렬한다.
 */
@Service
public class ServiceNoticeService {

	private static final Comparator<ServiceNotice> DISPLAY_ORDER =
		Comparator.comparing((ServiceNotice notice) -> notice.severity() == ServiceNoticeSeverity.DISRUPTION ? 0 : 1)
			.thenComparing(ServiceNotice::publishedAt, Comparator.reverseOrder());

	private final ServiceNoticeRepository repository;
	private final Clock clock;

	@Autowired
	public ServiceNoticeService(ServiceNoticeRepository repository) {
		this(repository, Clock.systemUTC());
	}

	ServiceNoticeService(ServiceNoticeRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	public List<ServiceNotice> activeNotices() {
		return repository.findActiveAt(LocalDateTime.now(clock)).stream()
			.sorted(DISPLAY_ORDER)
			.toList();
	}

	public ServiceNotice publish(PublishNoticeCommand command, String publishedBy) {
		ServiceNotice notice = new ServiceNotice(
			UUID.randomUUID().toString(),
			command.scope(),
			command.scopeValue(),
			command.title(),
			command.body(),
			command.severity(),
			LocalDateTime.now(clock),
			command.expiresAt(),
			publishedBy
		);
		repository.save(notice);
		return notice;
	}

	public void unpublish(String id) {
		repository.deleteById(id);
	}
}
