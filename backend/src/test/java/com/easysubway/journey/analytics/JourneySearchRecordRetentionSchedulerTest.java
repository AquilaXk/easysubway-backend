package com.easysubway.journey.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

@DisplayName("Journey V3 검색 기록 보존 스케줄러")
class JourneySearchRecordRetentionSchedulerTest {

	// 서울 기준 2026-10-05 12:00
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-05T03:00:00Z"), ZoneId.of("Asia/Seoul"));

	@Test
	@DisplayName("관리자 화면 최대 조회 기간(90일)보다 길게 보존한다")
	void retentionCoversWidestAdminWindow() {
		assertThat(JourneySearchRecordRetentionScheduler.RETENTION_DAYS).isGreaterThanOrEqualTo(90);
	}

	@Test
	@DisplayName("보존 기간 이전 날짜 기준으로 만료 기록을 삭제한다")
	void purgesRecordsBeforeRetentionCutoff() {
		var cutoffs = new ArrayList<LocalDate>();
		var store = new StubStore(cutoffs, false);

		new JourneySearchRecordRetentionScheduler(store, new SimpleMeterRegistry(), CLOCK).purgeExpiredRecords();

		assertThat(cutoffs).containsExactly(LocalDate.parse("2026-10-05").minusDays(90));
	}

	@Test
	@DisplayName("삭제 실패는 지표로 드러내고 예외를 전파하지 않는다")
	void recordsFailureMetric() {
		var meters = new SimpleMeterRegistry();
		var scheduler = new JourneySearchRecordRetentionScheduler(new StubStore(new ArrayList<>(), true), meters, CLOCK);

		scheduler.purgeExpiredRecords();

		assertThat(meters.counter(JourneySearchRecordRetentionScheduler.FAILURE_METRIC).count()).isEqualTo(1);
	}

	@Test
	@DisplayName("매일 03:40 UTC에 기동한다")
	void declaresDefaultSchedule() throws NoSuchMethodException {
		Scheduled scheduled = JourneySearchRecordRetentionScheduler.class
			.getDeclaredMethod("purgeExpiredRecords").getAnnotation(Scheduled.class);

		assertThat(scheduled.cron()).isEqualTo("${easysubway.journey.search-records.retention.cron:0 40 3 * * *}");
		assertThat(scheduled.zone()).isEqualTo("UTC");
	}

	private record StubStore(List<LocalDate> cutoffs, boolean fail) implements JourneySearchRecordStore {
		@Override
		public void save(JourneySearchRecord record) {
		}

		@Override
		public List<JourneySearchAggregateRow> aggregate(LocalDate fromInclusive, LocalDate toInclusive) {
			return List.of();
		}

		@Override
		public int purgeRecordedBefore(LocalDate cutoff) {
			cutoffs.add(cutoff);
			if (fail) throw new IllegalStateException("database unavailable");
			return 0;
		}
	}
}
