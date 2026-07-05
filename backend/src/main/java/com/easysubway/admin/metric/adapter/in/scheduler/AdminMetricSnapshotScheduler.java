package com.easysubway.admin.metric.adapter.in.scheduler;

import com.easysubway.admin.metric.application.service.AdminMetricSnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 일별 지표 스냅샷 스케줄러(#1739). 하루 1회 오늘 날짜로 집계를 수행한다.
 *
 * <p>같은 날짜 재실행은 서비스가 upsert로 멱등 처리하므로 재기동·중복 트리거에 안전하다.
 * 실패는 서비스가 상태 홀더에 기록해 대시보드·알림 센터가 노출하고, 여기서는 스케줄 스레드가
 * 예외로 죽지 않도록 로깅 후 삼킨다. 수동 재실행은 관리자 엔드포인트가 같은 서비스를 호출한다.
 */
@Component
class AdminMetricSnapshotScheduler {

	private static final Logger log = LoggerFactory.getLogger(AdminMetricSnapshotScheduler.class);

	private final AdminMetricSnapshotService snapshotService;

	AdminMetricSnapshotScheduler(AdminMetricSnapshotService snapshotService) {
		this.snapshotService = snapshotService;
	}

	@Scheduled(cron = "${easysubway.admin.metric.snapshot.cron:0 10 0 * * *}")
	void snapshotDaily() {
		try {
			snapshotService.snapshotToday();
			log.info("Admin metric daily snapshot completed.");
		} catch (RuntimeException exception) {
			log.warn("Admin metric daily snapshot failed.", exception);
		}
	}
}
