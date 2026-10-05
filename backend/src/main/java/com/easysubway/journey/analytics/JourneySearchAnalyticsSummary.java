package com.easysubway.journey.analytics;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 관리자 화면이 보여 줄 Journey V3 검색 분석 집계. */
public record JourneySearchAnalyticsSummary(
	int periodDays,
	long totalCount,
	List<KindRow> kindRows,
	List<DayRow> days,
	List<CountRow> engineRows,
	List<CountRow> stairFreeRows,
	List<CountRow> categoryRows,
	long recordFailureCount
) {

	public JourneySearchAnalyticsSummary {
		kindRows = List.copyOf(kindRows);
		days = List.copyOf(days);
		engineRows = List.copyOf(engineRows);
		stairFreeRows = List.copyOf(stairFreeRows);
		categoryRows = List.copyOf(categoryRows);
	}

	public record KindRow(JourneySearchKind kind, Map<JourneySearchOutcome, Long> counts) {
		public KindRow {
			counts = Map.copyOf(counts);
		}

		public long count(JourneySearchOutcome outcome) {
			return counts.getOrDefault(outcome, 0L);
		}

		public long total() {
			return counts.values().stream().mapToLong(Long::longValue).sum();
		}
	}

	/** {@code recorded}가 false이면 그 날짜에는 기록이 하나도 없다(0건과 구분한다). */
	public record DayRow(LocalDate day, boolean recorded, Map<JourneySearchOutcome, Long> counts) {
		public DayRow {
			counts = Map.copyOf(counts);
		}

		public long count(JourneySearchOutcome outcome) {
			return counts.getOrDefault(outcome, 0L);
		}

		public long total() {
			return counts.values().stream().mapToLong(Long::longValue).sum();
		}
	}

	public record CountRow(String label, long count) {
	}
}
