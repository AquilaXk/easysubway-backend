package com.easysubway.route.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Route V2 운영 metric")
class RouteV2MetricsTest {

	@Test
	@DisplayName("response와 purge는 bounded aggregate tag만 기록한다")
	void recordsOnlyBoundedAggregateTags() {
		var registry = new SimpleMeterRegistry();
		var metrics = new RouteV2Metrics(registry);

		metrics.recordResponse(429, "ROUTE_RATE_LIMITED");
		metrics.recordPurge(3);

		assertThat(registry.get("easysubway.route.v2.responses")
			.tags("status", "429", "code", "ROUTE_RATE_LIMITED")
			.counter()
			.count()).isEqualTo(1);
		assertThat(registry.get("easysubway.route.v2.purge.rows").summary().totalAmount()).isEqualTo(3);
		assertThat(registry.getMeters())
			.flatExtracting(meter -> meter.getId().getTags())
			.extracting(tag -> tag.getKey())
			.containsOnly("status", "code");
	}
}
