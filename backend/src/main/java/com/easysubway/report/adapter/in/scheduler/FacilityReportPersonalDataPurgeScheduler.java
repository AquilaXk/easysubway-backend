package com.easysubway.report.adapter.in.scheduler;

import com.easysubway.report.application.port.out.PurgeFacilityReportPersonalDataPort;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("prod | staging | release | prod-like")
public class FacilityReportPersonalDataPurgeScheduler {

	private static final Logger log = LoggerFactory.getLogger(FacilityReportPersonalDataPurgeScheduler.class);
	private static final long DEFAULT_PURGE_INTERVAL_MILLIS = 86_400_000L;
	private static final Duration PURGE_SAFETY_MARGIN = Duration.ofDays(7);
	private static final int MIN_RETENTION_DAYS = 8;
	private static final int MAX_RETENTION_DAYS = 365;

	private final PurgeFacilityReportPersonalDataPort purgePort;
	private final Clock clock;
	private final int retentionDays;

	@Autowired
	public FacilityReportPersonalDataPurgeScheduler(
		PurgeFacilityReportPersonalDataPort purgePort,
		@Value("${easysubway.report.personal-data-retention-days:365}") int retentionDays
	) {
		this(purgePort, Clock.systemUTC(), retentionDays);
	}

	FacilityReportPersonalDataPurgeScheduler(
		PurgeFacilityReportPersonalDataPort purgePort,
		Clock clock,
		int retentionDays
	) {
		if (retentionDays < MIN_RETENTION_DAYS || retentionDays > MAX_RETENTION_DAYS) {
			throw new IllegalArgumentException("Facility report personal data retention days must be between 8 and 365");
		}
		this.purgePort = purgePort;
		this.clock = clock;
		this.retentionDays = retentionDays;
	}

	@Scheduled(
		fixedRate = DEFAULT_PURGE_INTERVAL_MILLIS,
		scheduler = "facilityReportPersonalDataPurgeTaskScheduler"
	)
	public void purgeExpiredPersonalData() {
		Duration safetyMargin = Duration.ofDays(Math.min(PURGE_SAFETY_MARGIN.toDays(), retentionDays - 1L));
		LocalDateTime cutoff = LocalDateTime.ofInstant(
			clock.instant()
				.minus(Duration.ofDays(retentionDays))
				.plus(safetyMargin),
			ZoneOffset.UTC
		);
		int purged = purgePort.purgePersonalDataCreatedBefore(cutoff);
		if (purged > 0) {
			log.info("Purged personal data from {} expired facility reports", purged);
		}
	}
}
