package com.easysubway.route.adapter.in.scheduler;

import com.easysubway.route.adapter.in.web.RouteV2Metrics;
import com.easysubway.route.application.port.out.RouteV2AccessStore;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("prod | staging | release | prod-like")
public class RouteV2StatePurgeScheduler {

	private static final Logger log = LoggerFactory.getLogger(RouteV2StatePurgeScheduler.class);

	private final RouteV2AccessStore store;
	private final Clock clock;
	private final RouteV2Metrics metrics;

	@Autowired
	public RouteV2StatePurgeScheduler(RouteV2AccessStore store, RouteV2Metrics metrics) {
		this(store, Clock.systemUTC(), metrics);
	}

	RouteV2StatePurgeScheduler(RouteV2AccessStore store, Clock clock, RouteV2Metrics metrics) {
		this.store = store;
		this.clock = clock;
		this.metrics = metrics;
	}

	@Scheduled(fixedDelayString = "${easysubway.route-v2.state-purge-interval-ms:300000}")
	public void purgeExpiredState() {
		int purged = store.purgeExpired(clock.instant());
		metrics.recordPurge(purged);
		if (purged > 0) {
			log.info("Purged {} expired Route V2 state rows", purged);
		}
	}
}
