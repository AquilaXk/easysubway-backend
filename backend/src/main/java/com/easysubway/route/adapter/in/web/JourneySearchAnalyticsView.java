package com.easysubway.route.adapter.in.web;

import com.easysubway.journey.analytics.JourneySearchAnalyticsSummary;
import com.easysubway.journey.analytics.JourneySearchKind;
import com.easysubway.journey.analytics.JourneySearchOutcome;
import com.easysubway.journey.analytics.JourneySearchRecord;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 경로 검색 분석 화면의 Journey 탐색 집계 표시용 뷰. 운영자가 읽는 한국어 문구만 만든다. */
record JourneySearchAnalyticsView(
	int periodDays,
	long totalCount,
	long recordFailureCount,
	List<OutcomeColumn> columns,
	List<KindRow> kindRows,
	List<DayRow> dayRows,
	List<EngineRow> engineRows,
	List<LabeledCount> stairFreeRows,
	List<LabeledCount> categoryRows
) {

	private static final String OTHER = "기타";

	private static final Map<String, String> STAIR_FREE_LABELS = Map.of(
		"INCLUDED", "계단 없는 경로 포함",
		"OMITTED", "찾았지만 자리가 부족해 제외",
		"NOT_FOUND", "계단 없는 경로 없음",
		"UNDETERMINED", "접근성 정보가 부족해 확정하지 못함",
		JourneySearchRecord.UNKNOWN, "확인되지 않음",
		JourneySearchRecord.NOT_APPLICABLE, "해당 없음(결과 없음)");

	private static final Map<String, String> CATEGORY_LABELS = Map.of(
		"FASTEST", "빠른 경로",
		"FEWEST_TRANSFERS", "환승 적은 경로",
		"STAIR_FREE", "계단 없는 경로",
		"FASTEST_ARRIVAL", "가장 빨리 도착",
		"LATEST_DEPARTURE", "가장 늦게 출발",
		"LOWEST_WALKING_BURDEN", "걷기 적은 경로",
		"BEST_ACCESSIBILITY", "접근성 우수",
		"SAFEST_CONNECTION", "안전한 환승");

	static String kindLabel(JourneySearchKind kind) {
		return switch (kind) {
			case DEPART_AT -> "출발 시각";
			case DEPART_BETWEEN -> "출발 시간대";
			case ARRIVE_BY -> "도착 희망";
			case LAST_CONNECTION -> "막차";
		};
	}

	static String outcomeLabel(JourneySearchOutcome outcome) {
		return switch (outcome) {
			case FOUND -> "결과 있음";
			case NO_ROUTE -> "경로 없음";
			case TOO_COMPLEX -> "복잡도 초과";
			case TIMEOUT -> "시간 초과";
			case UNAVAILABLE -> "일시 불가";
			case REJECTED -> "조건 거절";
			case UNCLASSIFIED -> "분류 불가";
		};
	}

	static String stairFreeLabel(String value) {
		return STAIR_FREE_LABELS.getOrDefault(value, OTHER);
	}

	static String categoryLabel(String value) {
		return CATEGORY_LABELS.getOrDefault(value, OTHER);
	}

	/** 엔진 식별자(묶음/알고리즘/버전)는 보조 설명에만 두고 화면에는 버전만 짧게 보여 준다. */
	static EngineRow engineRow(String engineVersion, long count) {
		if (JourneySearchRecord.UNKNOWN.equals(engineVersion)) return new EngineRow("확인되지 않음", null, count);
		String[] parts = engineVersion.split("/");
		if (parts.length != 3 || parts[2].isBlank()) return new EngineRow(OTHER, null, count);
		return new EngineRow("버전 " + parts[2], engineVersion, count);
	}

	record OutcomeColumn(JourneySearchOutcome outcome, String label) {
	}

	record KindRow(String label, long total, List<Long> counts) {
	}

	record DayRow(LocalDate day, boolean recorded, long total, List<Long> counts) {
	}

	record LabeledCount(String label, long count) {
	}

	record EngineRow(String label, String detail, long count) {
	}

	static JourneySearchAnalyticsView from(JourneySearchAnalyticsSummary summary) {
		List<JourneySearchOutcome> outcomes = List.of(JourneySearchOutcome.values());
		List<OutcomeColumn> columns = outcomes.stream()
			.map(outcome -> new OutcomeColumn(outcome, outcomeLabel(outcome))).toList();
		return new JourneySearchAnalyticsView(
			summary.periodDays(),
			summary.totalCount(),
			summary.recordFailureCount(),
			columns,
			summary.kindRows().stream().map(row -> new KindRow(kindLabel(row.kind()), row.total(),
				outcomes.stream().map(row::count).toList())).toList(),
			summary.days().stream().map(row -> new DayRow(row.day(), row.recorded(), row.total(),
				outcomes.stream().map(row::count).toList())).toList(),
			summary.engineRows().stream().map(row -> engineRow(row.label(), row.count())).toList(),
			summary.stairFreeRows().stream().map(row -> new LabeledCount(
				stairFreeLabel(row.label()), row.count())).toList(),
			summary.categoryRows().stream().map(row -> new LabeledCount(
				categoryLabel(row.label()), row.count())).toList());
	}
}
