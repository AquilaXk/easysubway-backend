package com.easysubway.architecture.fixture.defaultzone;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.TimeZone;
import java.util.function.Supplier;

final class DefaultZoneFixture {

	Clock defaultZoneClock() {
		return Clock.systemDefaultZone();
	}

	Supplier<Clock> defaultZoneClockReference() {
		return Clock::systemDefaultZone;
	}

	LocalDateTime defaultZoneNow() {
		return LocalDateTime.now();
	}

	LocalDate defaultZoneToday() {
		return LocalDate.now();
	}

	ZoneId defaultZoneId() {
		return ZoneId.systemDefault();
	}

	TimeZone defaultTimeZone() {
		return TimeZone.getDefault();
	}
}
