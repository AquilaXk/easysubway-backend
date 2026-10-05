package com.easysubway.journey.analytics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 관리자 화면이 보여 주는 최대 기간(90일)이 지난 Journey V3 검색 기록을 일 1회 지운다. */
@Component
public class JourneySearchRecordRetentionScheduler {

	public static final String FAILURE_METRIC = "easysubway.journey.search_record.purge.failures";
	static final int RETENTION_DAYS = 90;
	private static final Logger LOG = LoggerFactory.getLogger(JourneySearchRecordRetentionScheduler.class);
	private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

	private final JourneySearchRecordStore store;
	private final Clock clock;
	private final Counter failures;

	@Autowired
	public JourneySearchRecordRetentionScheduler(
		JourneySearchRecordStore store, MeterRegistry meterRegistry, ObjectProvider<Clock> clockProvider
	) {
		this(store, meterRegistry, clockProvider.getIfAvailable(() -> Clock.system(SERVICE_ZONE)));
	}

	JourneySearchRecordRetentionScheduler(JourneySearchRecordStore store, MeterRegistry meterRegistry, Clock clock) {
		this.store = store;
		this.clock = clock;
		this.failures = Counter.builder(FAILURE_METRIC)
			.description("Journey V3 검색 기록 보존 기간 삭제에 실패한 건수")
			.register(meterRegistry);
	}

	@Scheduled(
		cron = "${easysubway.journey.search-records.retention.cron:0 40 3 * * *}",
		zone = "UTC"
	)
	void purgeExpiredRecords() {
		LocalDate cutoff = LocalDate.now(clock.withZone(SERVICE_ZONE)).minusDays(RETENTION_DAYS);
		try {
			int deleted = store.deleteRecordedBefore(cutoff);
			LOG.info("Journey V3 검색 기록 보존 삭제를 마쳤습니다. deletedRows={} cutoff={}", deleted, cutoff);
		} catch (RuntimeException exception) {
			failures.increment();
			LOG.error("Journey V3 검색 기록 보존 삭제에 실패했습니다: {}", exception.getClass().getSimpleName(), exception);
		}
	}
}
