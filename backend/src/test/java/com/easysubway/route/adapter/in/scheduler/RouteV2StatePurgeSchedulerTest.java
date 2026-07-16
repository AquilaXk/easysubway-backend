package com.easysubway.route.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.easysubway.route.adapter.in.web.RouteV2Metrics;
import com.easysubway.route.application.port.out.RouteV2AccessStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

@DisplayName("Route V2 ephemeral purge scheduler")
class RouteV2StatePurgeSchedulerTest {

	@Test
	@DisplayName("현재 시각 기준 purge 결과를 식별자 없는 aggregate metric으로 기록한다")
	void purgesAtCurrentTimeAndRecordsAggregate() throws Exception {
		Instant now = Instant.parse("2026-07-16T09:00:00Z");
		RouteV2AccessStore store = mock(RouteV2AccessStore.class);
		when(store.purgeExpired(now)).thenReturn(3);
		var registry = new SimpleMeterRegistry();
		var scheduler = new RouteV2StatePurgeScheduler(
			store,
			Clock.fixed(now, ZoneOffset.UTC),
			new RouteV2Metrics(registry)
		);

		scheduler.purgeExpiredState();

		verify(store).purgeExpired(now);
		assertThat(registry.get("easysubway.route.v2.purge.rows").summary().totalAmount()).isEqualTo(3);
		assertThat(RouteV2StatePurgeScheduler.class
			.getDeclaredMethod("purgeExpiredState")
			.getAnnotation(Scheduled.class)
			.fixedDelayString()).isEqualTo("${easysubway.route-v2.state-purge-interval-ms:300000}");
	}
}
