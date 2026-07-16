package com.easysubway.route.adapter.in.web;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod | staging | release | prod-like")
public class RouteV2Metrics {

	private final MeterRegistry meterRegistry;
	private final DistributionSummary purgeRows;

	public RouteV2Metrics(MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
		this.purgeRows = DistributionSummary.builder("easysubway.route.v2.purge.rows")
			.baseUnit("rows")
			.register(meterRegistry);
	}

	static RouteV2Metrics noop() {
		return new RouteV2Metrics(new SimpleMeterRegistry());
	}

	void recordResponse(int status, String code) {
		Counter.builder("easysubway.route.v2.responses")
			.tag("status", Integer.toString(status))
			.tag("code", code)
			.register(meterRegistry)
			.increment();
	}

	public void recordPurge(int rows) {
		purgeRows.record(rows);
	}
}
