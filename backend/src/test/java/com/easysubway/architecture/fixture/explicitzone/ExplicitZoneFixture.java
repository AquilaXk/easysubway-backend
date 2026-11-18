package com.easysubway.architecture.fixture.explicitzone;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

final class ExplicitZoneFixture {

	LocalDateTime utcNow() {
		return LocalDateTime.now(Clock.systemUTC());
	}

	LocalDate utcToday() {
		return LocalDate.now(ZoneOffset.UTC);
	}
}
