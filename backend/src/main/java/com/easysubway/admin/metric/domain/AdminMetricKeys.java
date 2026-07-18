package com.easysubway.admin.metric.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 일별 지표 스냅샷(#1739) 키. 대시보드 즉석 계산과 같은 소스에서 뽑아 "정합"을 보장한다.
 *
 * <p>값 단위: 건수는 개수, 소요는 분, 비율은 퍼센트(0~100).
 *
 * <p>기간 비교 의미는 지표 종류({@link AdminMetricKind})가 정한다. 누계·비율·롤링 윈도는 기간
 * 합산이 무의미하므로 기간 내 최신 스냅샷끼리 비교하고, 일별 counter만 기간 합계로 비교한다(#2273).
 */
public final class AdminMetricKeys {

	/**
	 * 지표 종류. 기간 비교(전 기간 대비 증감)에서 값을 어떻게 모을지 결정한다.
	 *
	 * <ul>
	 *   <li>{@link #GAUGE}: 시점 상태·누계 총량(대기 건수, 누적 검색 수 등). 기간 내 최신 스냅샷을 비교한다.
	 *   <li>{@link #RATE}: 비율·평균(차단률, 오류율, 평균 처리 시간). 기간 내 최신 스냅샷을 비교한다.
	 *   <li>{@link #DAILY_COUNTER}: 그날 하루치 증가분만 담는 지표. 이 종류만 기간 합계로 비교한다.
	 *   <li>{@link #ROLLING_WINDOW}: 이동 기간(24시간 접수, 7일 활성 사용자 등). 스냅샷이 겹쳐
	 *       합산하면 중복되므로 기간 내 최신 스냅샷을 비교한다.
	 * </ul>
	 */
	public enum AdminMetricKind {
		GAUGE,
		RATE,
		DAILY_COUNTER,
		ROLLING_WINDOW
	}

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
	private static final Map<String, AdminMetricKind> KINDS = kinds();

	private AdminMetricKeys() {
	}

	public static List<String> all() {
		return ALL;
	}

	public static boolean isKnown(String metricKey) {
		return ALL.contains(metricKey);
	}

	/**
	 * 지표 종류. 기간 비교에서 값을 어떻게 모을지 결정한다. 미등록 키는 합산하면 위험하므로 최신
	 * 스냅샷만 비교하는 {@link AdminMetricKind#GAUGE}로 본다(안전 기본값).
	 */
	public static AdminMetricKind kind(String metricKey) {
		return KINDS.getOrDefault(metricKey, AdminMetricKind.GAUGE);
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

	// 각 지표 스냅샷이 담는 값의 성격에 따라 종류를 고정한다. 대시보드 요약 use case가 돌려주는 값이
	// 그날 하루치 증가분이 아니라 시점 상태·누계 총량·비율·이동 기간이므로, 일별 합산 대상은 없다(#2273).
	private static Map<String, AdminMetricKind> kinds() {
		Map<String, AdminMetricKind> kinds = new LinkedHashMap<>();
		kinds.put(REPORTS_RECENT_24H, AdminMetricKind.ROLLING_WINDOW); // 스냅샷 시점 기준 최근 24시간 접수
		kinds.put(REPORTS_PENDING, AdminMetricKind.GAUGE); // 현재 확인 대기 건수
		kinds.put(REPORTS_PROCESSING_AVG_MINUTES, AdminMetricKind.RATE); // 평균 처리 시간(분)
		kinds.put(FACILITIES_NEEDS_VERIFICATION, AdminMetricKind.GAUGE); // 현재 확인 필요 시설 수
		kinds.put(FACILITIES_DELAYED, AdminMetricKind.GAUGE); // 현재 지연 시설 상태 수
		kinds.put(ROUTE_SEARCHES, AdminMetricKind.GAUGE); // 누적 경로 검색 총량(all-time COUNT)
		kinds.put(ROUTE_BLOCKED_RATE, AdminMetricKind.RATE); // 차단률(%)
		kinds.put(ROUTE_FEEDBACK_HELPFUL, AdminMetricKind.GAUGE); // 누적 도움됨 피드백 총량
		kinds.put(ROUTE_FEEDBACK_NOT_HELPFUL, AdminMetricKind.GAUGE); // 누적 도움 안 됨 피드백 총량
		kinds.put(ROUTE_FEEDBACK_BLOCKED, AdminMetricKind.GAUGE); // 누적 현장 차단 신고 총량
		kinds.put(PUSH_ATTEMPTED, AdminMetricKind.GAUGE); // 누적 푸시 시도 총량
		kinds.put(PUSH_FAILED, AdminMetricKind.GAUGE); // 누적 푸시 실패 총량
		kinds.put(API_ERROR_RATE, AdminMetricKind.RATE); // API 오류율(%)
		kinds.put(USERS_ACTIVE, AdminMetricKind.ROLLING_WINDOW); // 최근 7일 활성 사용자(이동 기간)
		return kinds;
	}
}
