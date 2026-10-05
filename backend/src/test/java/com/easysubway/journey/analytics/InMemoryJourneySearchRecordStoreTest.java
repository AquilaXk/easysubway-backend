package com.easysubway.journey.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("메모리 Journey V3 검색 기록 저장소")
class InMemoryJourneySearchRecordStoreTest {

	@Test
	@DisplayName("기간 안의 기록만 같은 구분 기준끼리 묶어 센다")
	void aggregatesRecordsInRange() {
		var store = new InMemoryJourneySearchRecordStore();
		for (String day : List.of("2026-10-01", "2026-10-01", "2026-09-01")) {
			store.save(new JourneySearchRecord(day + java.util.UUID.randomUUID(),
				LocalDate.parse(day), JourneySearchKind.DEPART_AT, JourneySearchOutcome.FOUND, 200, null,
				"UNKNOWN", "STEP_FREE", List.of("FASTEST"), "INCLUDED"));
		}

		var rows = store.aggregate(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-02"));

		assertThat(rows).singleElement().satisfies(row -> {
			assertThat(row.count()).isEqualTo(2);
			assertThat(row.alternativeCategories()).isEqualTo("FASTEST");
			assertThat(row.day()).isEqualTo(LocalDate.parse("2026-10-01"));
		});
	}

	@Test
	@DisplayName("기준일보다 이전 날짜의 기록만 지운다")
	void deletesOnlyRecordsBeforeCutoff() {
		var store = new InMemoryJourneySearchRecordStore();
		for (String day : List.of("2026-07-01", "2026-07-02", "2026-07-03")) {
			store.save(new JourneySearchRecord(day,
				LocalDate.parse(day), JourneySearchKind.DEPART_AT, JourneySearchOutcome.FOUND, 200, null,
				"UNKNOWN", "STEP_FREE", List.of(), "INCLUDED"));
		}

		int deleted = store.deleteRecordedBefore(LocalDate.parse("2026-07-02"));

		assertThat(deleted).isEqualTo(1);
		assertThat(store.aggregate(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31")))
			.extracting(JourneySearchAggregateRow::day)
			.containsExactlyInAnyOrder(LocalDate.parse("2026-07-02"), LocalDate.parse("2026-07-03"));
	}
}
