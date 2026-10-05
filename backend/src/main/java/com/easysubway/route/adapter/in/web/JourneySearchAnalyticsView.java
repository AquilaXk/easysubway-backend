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
	List<LabeledCount> engineRows,
	List<LabeledCount> stairFreeRows,
	List<LabeledCount> categoryRows
) {

	private static final Map<JourneySearchKind, String> KIND_LABELS = Map.of(
		JourneySearchKind.DEPART_AT, "출발 시각",
		JourneySearchKind.DEPART_BETWEEN, "출발 시간대",
		JourneySearchKind.ARRIVE_BY, "도착 희망",
		JourneySearchKind.LAST_CONNECTION, "막차");

	private static final Map<JourneySearchOutcome, String> OUTCOME_LABELS = Map.of(
		JourneySearchOutcome.FOUND, "결과 있음",
		JourneySearchOutcome.NO_ROUTE, "경로 없음",
		JourneySearchOutcome.TOO_COMPLEX, "복잡도 초과",
		JourneySearchOutcome.TIMEOUT, "시간 초과",
		JourneySearchOutcome.UNAVAILABLE, "일시 불가",
		JourneySearchOutcome.REJECTED, "조건 거절",
		JourneySearchOutcome.UNCLASSIFIED, "분류 불가");

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

	record OutcomeColumn(JourneySearchOutcome outcome, String label) {
	}

	record KindRow(String label, long total, List<Long> counts) {
	}

	record DayRow(LocalDate day, boolean recorded, long total, List<Long> counts) {
	}

	record LabeledCount(String label, long count) {
	}

	static JourneySearchAnalyticsView from(JourneySearchAnalyticsSummary summary) {
		List<JourneySearchOutcome> outcomes = List.of(JourneySearchOutcome.values());
		List<OutcomeColumn> columns = outcomes.stream()
			.map(outcome -> new OutcomeColumn(outcome, OUTCOME_LABELS.get(outcome))).toList();
		return new JourneySearchAnalyticsView(
			summary.periodDays(),
			summary.totalCount(),
			summary.recordFailureCount(),
			columns,
			summary.kindRows().stream().map(row -> new KindRow(KIND_LABELS.get(row.kind()), row.total(),
				outcomes.stream().map(row::count).toList())).toList(),
			summary.days().stream().map(row -> new DayRow(row.day(), row.recorded(), row.total(),
				outcomes.stream().map(row::count).toList())).toList(),
			summary.engineRows().stream().map(row -> new LabeledCount(
				JourneySearchRecord.UNKNOWN.equals(row.label()) ? "확인되지 않음" : row.label(), row.count())).toList(),
			summary.stairFreeRows().stream().map(row -> new LabeledCount(
				STAIR_FREE_LABELS.getOrDefault(row.label(), row.label()), row.count())).toList(),
			summary.categoryRows().stream().map(row -> new LabeledCount(
				CATEGORY_LABELS.getOrDefault(row.label(), row.label()), row.count())).toList());
	}
}
