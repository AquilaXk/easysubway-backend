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
	 * {@code now} 기준 활성(게시됨·미만료·미중단) 공지 목록. 게시 중단된 공지는 제외한다.
	 */
	List<ServiceNotice> findActiveAt(LocalDateTime now);

	/**
	 * 게시 중단 여부와 무관하게 최근 공지 목록. 게시 이력 조회에 쓰인다.
	 */
	List<ServiceNotice> findRecent(int limit);

	/**
	 * 아직 게시 중단되지 않은 공지만 게시 중단으로 표시한다(soft-unpublish, compare-and-set).
	 * row는 삭제하지 않는다. 활성 공지를 실제로 게시 중단했으면 {@code true},
	 * 대상이 없거나 이미 게시 중단돼 바뀐 row가 없으면 {@code false}를 준다.
	 */
	boolean unpublish(String id, LocalDateTime unpublishedAt, String unpublishedBy);
}
