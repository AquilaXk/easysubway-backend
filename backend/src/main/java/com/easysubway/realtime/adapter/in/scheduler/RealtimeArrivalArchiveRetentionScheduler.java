package com.easysubway.realtime.adapter.in.scheduler;

import com.easysubway.realtime.application.port.out.RealtimeArrivalArchivePort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("prod | staging | release | prod-like")
public class RealtimeArrivalArchiveRetentionScheduler {
	private static final Logger log = LoggerFactory.getLogger(RealtimeArrivalArchiveRetentionScheduler.class);
	private static final String PROVIDER_ID = "seoul-topis";
	private static final String PURGE_OPERATION = "delete-expired";

	private final RealtimeArrivalArchivePort archivePort;
	private final Counter purgeFailureCounter;
	private final Clock clock;

	@Autowired
	public RealtimeArrivalArchiveRetentionScheduler(
		RealtimeArrivalArchivePort archivePort,
		MeterRegistry meterRegistry
	) {
		this(archivePort, meterRegistry, Clock.systemUTC());
	}

	RealtimeArrivalArchiveRetentionScheduler(
		RealtimeArrivalArchivePort archivePort,
		MeterRegistry meterRegistry,
		Clock clock
	) {
		this.archivePort = archivePort;
		this.clock = clock;
		this.purgeFailureCounter = Counter.builder("easysubway.realtime.archive.purge.failures")
			.tag("provider", PROVIDER_ID)
			.tag("operation", PURGE_OPERATION)
			.register(meterRegistry);
	}

	@Scheduled(
		cron = "${easysubway.realtime.archive.purge.cron:0 20 3 * * *}",
		zone = "UTC"
	)
	void purgeExpiredObservations() {
		try {
			int deleted = archivePort.deleteExpired(clock.instant());
			log.info("Realtime arrival archive retention purge completed. deletedRows={}", deleted);
		} catch (RuntimeException exception) {
			purgeFailureCounter.increment();
			log.error(
				"Realtime arrival archive retention purge failed. providerId={} operation={} exceptionType={}",
				PROVIDER_ID,
				PURGE_OPERATION,
				exception.getClass().getSimpleName(),
				exception
			);
		}
	}
}
