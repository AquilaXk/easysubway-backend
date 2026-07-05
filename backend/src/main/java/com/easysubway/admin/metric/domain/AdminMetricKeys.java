package com.easysubway.admin.metric.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 일별 지표 스냅샷(#1739) 키. 대시보드 즉석 계산과 같은 소스에서 뽑아 "정합"을 보장한다.
 *
 * <p>값 단위: 건수는 개수, 소요는 분, 비율은 퍼센트(0~100).
 */
public final class AdminMetricKeys {

	public static final String REPORTS_RECENT_24H = "reports.recent_24h";
	public static final String REPORTS_PENDING = "reports.pending";
	public static final String REPORTS_PROCESSING_AVG_MINUTES = "reports.processing_avg_minutes";
	public static final String FACILITIES_NEEDS_VERIFICATION = "facilities.needs_verification";
	public static final String FACILITIES_DELAYED = "facilities.delayed";
	public static final String ROUTE_SEARCHES = "route.searches";
	public static final String ROUTE_BLOCKED_RATE = "route.blocked_rate";
	public static final String ROUTE_FEEDBACK_HELPFUL = "route.feedback_helpful";
	public static final String ROUTE_FEEDBACK_NOT_HELPFUL = "route.feedback_not_helpful";
	public static final String ROUTE_FEEDBACK_BLOCKED = "route.feedback_blocked";
	public static final String PUSH_ATTEMPTED = "push.attempted";
	public static final String PUSH_FAILED = "push.failed";
	public static final String API_ERROR_RATE = "api.error_rate";
	public static final String USERS_ACTIVE = "users.active";

	private static final List<String> ALL = List.of(
		REPORTS_RECENT_24H,
		REPORTS_PENDING,
		REPORTS_PROCESSING_AVG_MINUTES,
		FACILITIES_NEEDS_VERIFICATION,
		FACILITIES_DELAYED,
		ROUTE_SEARCHES,
		ROUTE_BLOCKED_RATE,
		ROUTE_FEEDBACK_HELPFUL,
		ROUTE_FEEDBACK_NOT_HELPFUL,
		ROUTE_FEEDBACK_BLOCKED,
		PUSH_ATTEMPTED,
		PUSH_FAILED,
		API_ERROR_RATE,
		USERS_ACTIVE
	);

	private static final Map<String, String> LABELS = labels();

	private AdminMetricKeys() {
	}

	public static List<String> all() {
		return ALL;
	}

	private static final Set<String> RATE_METRICS = Set.of(
		ROUTE_BLOCKED_RATE, API_ERROR_RATE, REPORTS_PROCESSING_AVG_MINUTES);

	public static boolean isKnown(String metricKey) {
		return ALL.contains(metricKey);
	}

	/**
	 * 비율·평균 지표인지 여부. 기간 비교에서 이런 지표는 합계가 무의미하므로(예: 7일 차단률 합 = 175%)
	 * 값이 있는 날의 평균으로 집계한다. 건수 지표는 합계로 집계한다.
	 */
	public static boolean isRate(String metricKey) {
		return RATE_METRICS.contains(metricKey);
	}

	/** 차트·표에 쓰는 한글 표시 라벨. 미등록 키는 키 자체를 돌려준다. */
	public static String label(String metricKey) {
		return LABELS.getOrDefault(metricKey, metricKey);
	}

	private static Map<String, String> labels() {
		Map<String, String> labels = new LinkedHashMap<>();
		labels.put(REPORTS_RECENT_24H, "제보 접수(24시간)");
		labels.put(REPORTS_PENDING, "확인 대기 제보");
		labels.put(REPORTS_PROCESSING_AVG_MINUTES, "평균 처리 시간(분)");
		labels.put(FACILITIES_NEEDS_VERIFICATION, "확인 필요 시설");
		labels.put(FACILITIES_DELAYED, "지연 시설 상태");
		labels.put(ROUTE_SEARCHES, "경로 검색");
		labels.put(ROUTE_BLOCKED_RATE, "경로 차단률(%)");
		labels.put(ROUTE_FEEDBACK_HELPFUL, "도움이 됨");
		labels.put(ROUTE_FEEDBACK_NOT_HELPFUL, "도움이 안 됨");
		labels.put(ROUTE_FEEDBACK_BLOCKED, "현장 차단 신고");
		labels.put(PUSH_ATTEMPTED, "푸시 시도");
		labels.put(PUSH_FAILED, "푸시 실패");
		labels.put(API_ERROR_RATE, "API 오류율(%)");
		labels.put(USERS_ACTIVE, "활성 사용자");
		return labels;
	}
}
