package com.easysubway.admin.metric.application.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * 지표 스냅샷 잡의 마지막 실행 상태(#1739). 상태 저장 없는 운영 신호라 프로세스 메모리에 둔다
 * (알림 센터의 파생 신호와 같은 성격). 대시보드는 마지막 성공 시각을, 알림 센터는 실패 여부를 읽는다.
 */
@Component
public class AdminMetricSnapshotStatusHolder {

	private final AtomicReference<AdminMetricSnapshotStatus> status = new AtomicReference<>();

	public void recordSuccess(LocalDateTime ranAt, LocalDate targetDate) {
		status.set(new AdminMetricSnapshotStatus(ranAt, targetDate, true, null));
	}

	public void recordFailure(LocalDateTime ranAt, LocalDate targetDate, String message) {
		status.set(new AdminMetricSnapshotStatus(ranAt, targetDate, false, message));
	}

	public Optional<AdminMetricSnapshotStatus> latest() {
		return Optional.ofNullable(status.get());
	}

	/** 아직 성공 이력이 없거나 마지막 실행이 실패면 true(알림 센터 신호용). */
	public boolean isFailing() {
		AdminMetricSnapshotStatus current = status.get();
		return current != null && !current.success();
	}

	public record AdminMetricSnapshotStatus(
		LocalDateTime ranAt,
		LocalDate targetDate,
		boolean success,
		String message
	) {
	}
}
