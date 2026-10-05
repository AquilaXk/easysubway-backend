package com.easysubway.journey.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Journey V3 검색 분석 집계")
class JourneySearchAnalyticsServiceTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-03T03:00:00Z"), ZoneOffset.UTC);

	private static JourneySearchAggregateRow row(String day, JourneySearchKind kind, JourneySearchOutcome outcome,
		String engine, String stairFree, String categories, long count) {
		return new JourneySearchAggregateRow(LocalDate.parse(day), kind, outcome, engine, "STEP_FREE",
			categories, stairFree, count);
	}

	@Test
	@DisplayName("탐색 종류별·결과 분류별 건수와 일별 추이를 계산하고 기록 없는 날은 비워 둔다")
	void summarizesByKindOutcomeAndDay() {
		var store = new JourneySearchRecorderTest.InMemoryStore() {
			@Override
			public List<JourneySearchAggregateRow> aggregate(LocalDate from, LocalDate to) {
				assertThat(from).isEqualTo(LocalDate.parse("2026-09-27"));
				assertThat(to).isEqualTo(LocalDate.parse("2026-10-03"));
				return List.of(
					row("2026-10-02", JourneySearchKind.DEPART_AT, JourneySearchOutcome.FOUND,
						JourneySearchRecord.UNKNOWN, "UNDETERMINED", "FASTEST", 3),
					row("2026-10-02", JourneySearchKind.DEPART_AT, JourneySearchOutcome.NO_ROUTE,
						JourneySearchRecord.UNKNOWN, JourneySearchRecord.NOT_APPLICABLE, "", 1),
					row("2026-10-03", JourneySearchKind.ARRIVE_BY, JourneySearchOutcome.TOO_COMPLEX,
						"suite/q/1", JourneySearchRecord.NOT_APPLICABLE, "", 2),
					row("2026-10-03", JourneySearchKind.ARRIVE_BY, JourneySearchOutcome.FOUND,
						"suite/q/1", JourneySearchRecord.UNKNOWN, "FASTEST,STAIR_FREE", 4)
				);
			}
		};
		var recorder = new JourneySearchRecorder(store, Runnable::run, CLOCK, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
		var service = new JourneySearchAnalyticsService(store, recorder, CLOCK);

		JourneySearchAnalyticsSummary summary = service.summarize(7);

		assertThat(summary.totalCount()).isEqualTo(10);
		assertThat(summary.days()).hasSize(7);
		assertThat(summary.days().get(0).day()).isEqualTo(LocalDate.parse("2026-09-27"));
		assertThat(summary.days().get(0).recorded()).isFalse();
		assertThat(summary.days().get(5).recorded()).isTrue();
		assertThat(summary.days().get(5).total()).isEqualTo(4);
		assertThat(summary.days().get(5).count(JourneySearchOutcome.FOUND)).isEqualTo(3);
		assertThat(summary.days().get(6).total()).isEqualTo(6);
		assertThat(summary.kindRows()).extracting(JourneySearchAnalyticsSummary.KindRow::kind)
			.containsExactly(JourneySearchKind.DEPART_AT, JourneySearchKind.ARRIVE_BY);
		var departAt = summary.kindRows().get(0);
		assertThat(departAt.total()).isEqualTo(4);
		assertThat(departAt.count(JourneySearchOutcome.FOUND)).isEqualTo(3);
		assertThat(departAt.count(JourneySearchOutcome.NO_ROUTE)).isEqualTo(1);
		assertThat(departAt.count(JourneySearchOutcome.TIMEOUT)).isZero();
		assertThat(summary.engineRows()).extracting(JourneySearchAnalyticsSummary.CountRow::label, JourneySearchAnalyticsSummary.CountRow::count)
			.containsExactly(org.assertj.core.api.Assertions.tuple("suite/q/1", 6L),
				org.assertj.core.api.Assertions.tuple(JourneySearchRecord.UNKNOWN, 4L));
		assertThat(summary.stairFreeRows()).extracting(JourneySearchAnalyticsSummary.CountRow::label, JourneySearchAnalyticsSummary.CountRow::count)
			.contains(org.assertj.core.api.Assertions.tuple("UNDETERMINED", 3L),
				org.assertj.core.api.Assertions.tuple(JourneySearchRecord.NOT_APPLICABLE, 3L),
				org.assertj.core.api.Assertions.tuple(JourneySearchRecord.UNKNOWN, 4L));
		assertThat(summary.categoryRows()).extracting(JourneySearchAnalyticsSummary.CountRow::label, JourneySearchAnalyticsSummary.CountRow::count)
			.containsExactlyInAnyOrder(org.assertj.core.api.Assertions.tuple("FASTEST", 7L),
				org.assertj.core.api.Assertions.tuple("STAIR_FREE", 4L));
		assertThat(summary.recordFailureCount()).isZero();
	}

	@Test
	@DisplayName("지원하지 않는 기간은 7일로 정규화한다")
	void normalizesDays() {
		var store = new JourneySearchRecorderTest.InMemoryStore() {
			@Override
			public List<JourneySearchAggregateRow> aggregate(LocalDate from, LocalDate to) {
				return List.of();
			}
		};
		var recorder = new JourneySearchRecorder(store, Runnable::run, CLOCK, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
		var service = new JourneySearchAnalyticsService(store, recorder, CLOCK);

		assertThat(service.summarize(5).days()).hasSize(7);
		assertThat(service.summarize(30).days()).hasSize(30);
		assertThat(service.summarize(90).days()).hasSize(90);
		assertThat(service.summarize(7).totalCount()).isZero();
	}
}
