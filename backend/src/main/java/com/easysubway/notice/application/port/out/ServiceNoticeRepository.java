package com.easysubway.notice.application.port.out;

import com.easysubway.notice.domain.ServiceNotice;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 운행 공지 영속 포트. 활성 조회는 저장소 수준에서 게시·만료 시각으로 필터한다.
 */
public interface ServiceNoticeRepository {

	void save(ServiceNotice notice);

	Optional<ServiceNotice> findById(String id);

	/**
	 * {@code now} 기준 활성(게시됨·미만료) 공지 목록.
	 */
	List<ServiceNotice> findActiveAt(LocalDateTime now);

	void deleteById(String id);
}
