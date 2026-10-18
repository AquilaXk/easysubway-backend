package com.easysubway.architecture.fixture.scheduledzone;

import org.springframework.scheduling.annotation.Scheduled;

final class ScheduledZoneFixture {

	@Scheduled(cron = "0 10 0 * * *")
	void cronWithoutZone() {
	}

	@Scheduled(cron = "0 10 0 * * *", zone = "UTC")
	void cronWithZone() {
	}

	@Scheduled(fixedDelay = 1000)
	void fixedDelay() {
	}
}
