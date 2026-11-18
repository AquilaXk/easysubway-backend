package com.easysubway.report.domain;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 제보 확인 대기열(#1740)의 SLA 경과 뱃지. 대기 상태(SUBMITTED·UNDER_REVIEW) 제보가 접수 후
 * 임계 시간을 넘기면 경과를 표시해 "오래된 것부터" 처리하도록 유도한다.
 *
 * <p>종결 상태(ACCEPTED·REJECTED·RESOLVED·DUPLICATE)는 경과가 의미 없어 뱃지를 비운다.
 * 임계값은 상용 모더레이션 큐 기준선(24h 경고·72h 위반)을 기본으로 한다.
 *
 * @param label 표시 문구(없으면 빈 문자열)
 * @param tone  status 프래그먼트 톤(warn·bad)
 */
public record ReportSlaBadge(String label, String tone) {

	static final long WARN_HOURS = 24;
	static final long BREACH_HOURS = 72;

	private static final ReportSlaBadge NONE = new ReportSlaBadge("", "");

	public static ReportSlaBadge of(FacilityReportStatus status, LocalDateTime createdAt, LocalDateTime now) {
		if (createdAt == null || !isPending(status)) {
			return NONE;
		}
		long hours = Duration.between(createdAt, now).toHours();
		if (hours >= BREACH_HOURS) {
			return new ReportSlaBadge(BREACH_HOURS + "시간 초과", "bad");
		}
		if (hours >= WARN_HOURS) {
			return new ReportSlaBadge(WARN_HOURS + "시간 초과", "warn");
		}
		return NONE;
	}

	private static boolean isPending(FacilityReportStatus status) {
		return status == FacilityReportStatus.SUBMITTED || status == FacilityReportStatus.UNDER_REVIEW;
	}

	public boolean present() {
		return !label.isEmpty();
	}
}
